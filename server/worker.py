"""Background publisher: moves accepted checkpoints from the spool to Hugging Face.

Run: python -m server.worker [--config path.toml]

Each published snapshot writes its ZIP and its progress JSON (whose archiveSha256 is the
ZIP's SHA-256) in one HF commit. Destination slots per session repository:

    latest            -> latest.json                                  (newest snapshot of any run)
    run:<run>         -> runs/<run>/latest.zip + runs/<run>/progress.json
    config:<run>:<id> -> runs/<run>/configurations/<id>.zip + <id>.json

A slot is only written by a snapshot newer than what it already holds, so retried or late
uploads cannot roll progress back. Routine checkpoints of one run are coalesced to the newest.
"""

import argparse
import hashlib
import json
import logging
import random
import signal
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

from server import SERVICE_NAME
from server.catalog import BATCH_NAMES
from server.config import (
    Settings,
    configure_logging,
    load_settings,
)
from server.feed import (
    public_feed,
    repository_url,
)
from server.hub import (
    HubClient,
    HubError,
)
from server.store import (
    Store,
    now_ms,
    order_key,
)

LOGGER = logging.getLogger("mobilebench.worker")
OLDEST = (-1, -1, -1)
ORPHAN_AGE_S = 2 * 3600


class Publisher:
    """Publish pending uploads; one instance per worker process."""

    def __init__(self, settings: Settings, store: Store, hub: HubClient):
        """Bind settings, database and Hub client.

        Args:
            settings: Service settings.
            store: Shared database.
            hub: Hub client (a fake in tests).
        """
        self.settings = settings
        self.store = store
        self.hub = hub
        self.stop = threading.Event()
        self.last_index_at = 0.0
        self.last_janitor_at = 0.0
        self.credential_error = None

    def run_forever(self) -> None:
        """Loop until stopped; every step survives Hub outages and restarts."""
        LOGGER.info("worker started")
        # Verify the credential up front so readiness reflects it before the first upload.
        try:
            self.hub.client()
        except HubError as error:
            self.credential_error = str(error)
            LOGGER.error("credential check failed: %s", error)
        while not self.stop.is_set():
            try:
                self.tick()
            except Exception:
                LOGGER.exception("worker tick failed")
            self.stop.wait(self.settings.poll_interval_s)
        LOGGER.info("worker stopped")

    def tick(self) -> None:
        """Run one scheduling round."""
        # 1. Publish due sessions concurrently; each session is handled by one thread at a time.
        due = self.store.sessions_with_pending(now_ms())
        if due:
            with ThreadPoolExecutor(max_workers=self.settings.worker_threads) as pool:
                list(pool.map(self.process_session, [session["id"] for session in due]))

        # 2. Refresh the public index and release spool space periodically.
        if time.monotonic() - self.last_index_at >= self.settings.index_interval_s:
            self.last_index_at = time.monotonic()
            self.publish_index()
        if time.monotonic() - self.last_janitor_at >= 600:
            self.last_janitor_at = time.monotonic()
            self.clean_spool()
        self.heartbeat()

    def heartbeat(self) -> None:
        """Record liveness and the last credential problem for the readiness endpoint."""
        self.store.set_state("worker", {"heartbeatAtMs": now_ms(), "credentialError": self.credential_error})

    def process_session(self, session_id: str) -> None:
        """Coalesce and publish at most one snapshot of one session.

        Args:
            session_id: Session with pending uploads.
        """
        session = self.store.session(session_id)
        try:
            # 1. Drop snapshots already covered by newer pending or published ones.
            pending = self.store.pending_uploads(session_id)
            published = self.store.publications(session_id)
            covered, job = select_work(pending, published)
            if covered:
                self.store.mark_superseded(covered)
                self.release_blobs()
            if job is None:
                return

            # 2. Pace routine checkpoints; configuration and final snapshots go out immediately.
            last = session["last_publish_at_ms"] or 0
            if job["kind"] == "latest" and not job["terminal"]:
                if now_ms() - last < self.settings.min_latest_interval_s * 1000:
                    return

            # 3. Create the repository on first use, then commit the hash-matched pair(s).
            self.publish(session, job, published)
        except HubError as error:
            self.fail(session, str(error), error.retry_after_s, error.credential)
        except Exception as error:
            LOGGER.exception("unexpected publication error for session %s", session_id)
            self.fail(session, f"internal error ({type(error).__name__})", None, False)

    def publish(self, session: sqlite3.Row, upload: sqlite3.Row, published: dict) -> None:
        """Commit one upload to every slot it advances.

        Args:
            session: Session row.
            upload: Pending upload chosen by select_work().
            published: Current slot -> order key map.
        """
        # 1. Re-verify the spooled bytes before publishing them.
        blob = Path(upload["blob_path"])
        if file_sha256(blob) != upload["sha256"]:
            raise RuntimeError("spooled blob no longer matches its checksum")

        # 2. Compute files and slots; nothing to do means a newer snapshot already won.
        files, slots = publication_files(upload, published, blob)
        if not slots:
            self.store.mark_superseded([upload["id"]])
            self.release_blobs()
            return
        if not session["repo_created"]:
            self.hub.ensure_repo(session["repository"])
            files["README.md"] = dataset_card(session).encode()

        # 3. Commit, then record success before deleting the spooled blob.
        label = upload["configuration_id"] or ("final" if upload["terminal"] else "checkpoint")
        message = f"Relay {upload['cloud_run_id']}: {label} ({upload['completed_answers']} answers)"
        commit = self.hub.commit(session["repository"], files, message)
        if not session["repo_created"]:
            self.store.set_repo_created(session["id"])
        self.store.mark_published(upload, slots, commit)
        self.credential_error = None
        self.release_blobs()
        LOGGER.info("published upload %s session %s slots %s commit %s", upload["id"], session["id"], ",".join(slots), commit)

    def fail(self, session: sqlite3.Row, error: str, retry_after_s: float | None, credential: bool) -> None:
        """Schedule a retry with exponential backoff; pending data is kept.

        Args:
            session: Session row (failure count before this attempt).
            error: Sanitized error text.
            retry_after_s: Hub-requested delay, if any.
            credential: Whether the credential was rejected.
        """
        delay = min(self.settings.retry_max_s, self.settings.retry_base_s * 2 ** min(session["failures"], 8))
        delay = max(delay * random.uniform(0.8, 1.2), retry_after_s or 0)
        self.store.record_failure(session["id"], error, now_ms() + int(delay * 1000))
        if credential:
            self.credential_error = error
            self.heartbeat()
        LOGGER.warning("publication failed for session %s: %s; retry in %.0f s", session["id"], error, delay)

    def release_blobs(self) -> None:
        """Delete spool files of published or superseded uploads."""
        for row in self.store.releasable_blobs():
            Path(row["blob_path"]).unlink(missing_ok=True)
            self.store.clear_blob(row["id"])

    def clean_spool(self) -> None:
        """Release finished blobs and remove old orphans left by interrupted requests."""
        self.release_blobs()
        keep = self.store.pending_blob_paths()
        cutoff = time.time() - ORPHAN_AGE_S
        for path in self.settings.spool_dir.rglob("*"):
            if path.is_file() and str(path) not in keep and path.stat().st_mtime < cutoff:
                LOGGER.info("removing orphan spool file %s", path.name)
                path.unlink(missing_ok=True)

    def publish_index(self) -> None:
        """Publish the public session list and the verified relay endpoint to the index dataset."""
        try:
            # 1. Build the documents; skip the commit when nothing changed.
            sessions = public_feed(self.store)
            endpoint = verified_endpoint(self.settings.endpoint_file)
            documents = {
                "sessions.json": sessions,
                "endpoint.json": endpoint,
            }
            digest = hashlib.sha256(json.dumps(documents, sort_keys=True).encode()).hexdigest()
            previous, _ = self.store.get_state("index")
            if previous and previous.get("digest") == digest:
                return

            # 2. Commit sessions.json, endpoint.json and a README together.
            files = {name: (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode() for name, value in documents.items()}
            files["README.md"] = index_card(self.settings).encode()
            self.hub.ensure_repo(self.settings.index_repo)
            commit = self.hub.commit(self.settings.index_repo, files, f"Relay index: {len(sessions)} sessions")
            self.store.set_state("index", {"digest": digest, "commit": commit, "sessions": len(sessions)})
            LOGGER.info("published index with %d sessions, commit %s", len(sessions), commit)
        except HubError as error:
            LOGGER.warning("index publication failed: %s", error)
            if error.credential:
                self.credential_error = str(error)


def select_work(pending: list[sqlite3.Row], published: dict) -> tuple[list[str], sqlite3.Row | None]:
    """Choose what to supersede and the next upload to publish for one session.

    Args:
        pending: Accepted uploads, oldest snapshot first.
        published: Slot -> order key of what HF already holds.
    Returns:
        (ids covered by newer data, next upload or None). Configuration snapshots go first,
        oldest first; otherwise the newest routine checkpoint.
    """
    # 1. Newest pending key per run and per configuration slot.
    newest_run = {}
    newest_config = {}
    for upload in pending:
        newest_run[upload["cloud_run_id"]] = order_key(upload)
        if upload["kind"] == "configuration":
            newest_config[config_slot(upload)] = order_key(upload)

    # 2. A routine checkpoint is covered by any newer snapshot of its run (pending or published);
    #    a configuration snapshot only by a newer snapshot of the same configuration slot.
    covered = []
    configurations = []
    latest = []
    for upload in pending:
        key = order_key(upload)
        if upload["kind"] == "configuration":
            slot = config_slot(upload)
            if key < newest_config[slot] or key <= published.get(slot, OLDEST):
                covered.append(upload["id"])
            else:
                configurations.append(upload)
        elif key < newest_run[upload["cloud_run_id"]] or key <= published.get(run_slot(upload), OLDEST):
            covered.append(upload["id"])
        else:
            latest.append(upload)
    job = configurations[0] if configurations else (latest[0] if latest else None)
    return covered, job


def publication_files(upload: sqlite3.Row, published: dict, blob: Path) -> tuple[dict[str, bytes | Path], list[str]]:
    """Return repository files and slots an upload advances.

    Args:
        upload: Upload to publish.
        published: Slot -> order key map.
        blob: Spooled ZIP path.
    Returns:
        (path -> content, slots). Each progress JSON carries archiveSha256 of the ZIP next to it.
    """
    key = order_key(upload)
    run = upload["cloud_run_id"]
    progress = json.loads(upload["progress_json"])
    files = {}
    slots = []

    # 1. Configuration snapshot: its own ZIP and progress sidecar.
    if upload["kind"] == "configuration" and key > published.get(config_slot(upload), OLDEST):
        path = f"runs/{run}/configurations/{upload['configuration_id']}"
        files[f"{path}.zip"] = blob
        files[f"{path}.json"] = progress_bytes(progress, f"{path}.zip")
        slots.append(config_slot(upload))

    # 2. Run checkpoint and, when newest overall, the repository-level latest.json.
    if key > published.get(run_slot(upload), OLDEST):
        files[f"runs/{run}/latest.zip"] = blob
        files[f"runs/{run}/progress.json"] = progress_bytes(progress, f"runs/{run}/latest.zip")
        slots.append(run_slot(upload))
        if key > published.get("latest", OLDEST):
            files["latest.json"] = files[f"runs/{run}/progress.json"]
            slots.append("latest")
    return files, slots


def run_slot(upload: sqlite3.Row) -> str:
    """Slot name of a run's latest checkpoint."""
    return f"run:{upload['cloud_run_id']}"


def config_slot(upload: sqlite3.Row) -> str:
    """Slot name of a configuration snapshot."""
    return f"config:{upload['cloud_run_id']}:{upload['configuration_id']}"


def progress_bytes(progress: dict, archive_path: str) -> bytes:
    """Serialize progress.json for one archive path, matching CloudUploader's pretty JSON."""
    return (json.dumps(dict(progress, archivePath=archive_path), indent=2, ensure_ascii=False) + "\n").encode()


def file_sha256(path: Path) -> str:
    """Return the SHA-256 of a file."""
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verified_endpoint(endpoint_file: Path) -> dict:
    """Read the active tunnel URL and confirm it reaches this service.

    Args:
        endpoint_file: File written by the tunnel supervisor (one https URL).
    Returns:
        Endpoint document; "url" is null when no verified endpoint exists.
    """
    document = {
        "service": SERVICE_NAME,
        "url": None,
        "serviceInstanceId": None,
        "stable": False,
        "note": "Temporary Cloudflare quick-tunnel URL; it changes when the tunnel restarts. Re-read this file before uploading.",
    }
    try:
        url = endpoint_file.read_text().strip()
        if not url.startswith("https://"):
            return document
        response = httpx.get(f"{url}/v1/health", timeout=15)
        if response.status_code == 200 and response.json().get("service") == SERVICE_NAME:
            # Coarse timestamp keeps the index commit rate low while still showing freshness.
            document.update(url=url, serviceInstanceId=response.json().get("serviceInstanceId"),
                            verifiedAtHour=time.strftime("%Y-%m-%dT%H:00Z", time.gmtime()))
    except (OSError, ValueError, httpx.HTTPError):
        pass
    return document


def dataset_card(session: sqlite3.Row) -> str:
    """Return README.md for a session repository."""
    synthetic = session["purpose"] == "synthetic-test"
    return (
        "---\npretty_name: Mobile benchmark relay session\ntags:\n- mobilebench-relay\n---\n\n"
        f"# Mobile benchmark session `{session['id']}`\n\n"
        + ("**SYNTHETIC TEST SESSION.** Transport validation only; contains no model-quality or performance evidence.\n\n"
           if synthetic else "")
        + f"- Batch: {BATCH_NAMES[session['batch']]}\n"
        "- Uploaded by the mobile benchmark APK through the upload relay; this repository is created and written by the relay.\n"
        "- Device details and metrics are **reported by the client app** and are not authenticated hardware evidence.\n\n"
        "Layout: `latest.json`, `runs/<run>/progress.json`, `runs/<run>/latest.zip`, "
        "`runs/<run>/configurations/<configuration>.zip` (+ `.json`). Each progress JSON's `archiveSha256` "
        "matches the ZIP committed with it.\n"
    )


def index_card(settings: Settings) -> str:
    """Return README.md for the public index repository."""
    return (
        "---\npretty_name: Mobile benchmark relay index\ntags:\n- mobilebench-relay\n---\n\n"
        "# Mobile benchmark relay index\n\n"
        f"- `sessions.json`: every relay session and its public dataset (`{settings.namespace}/{settings.repo_prefix}-*`).\n"
        "  Device fields and counts are client-reported. Sessions with purpose `synthetic-test` are transport tests.\n"
        "- `endpoint.json`: the currently verified relay URL. It is a temporary tunnel URL and can change.\n\n"
        f"Session repositories: {repository_url(settings.namespace + '/' + settings.repo_prefix + '-…')}\n"
    )


def main() -> None:
    """Start the worker process."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    settings = load_settings(args.config)
    configure_logging(settings, "worker")
    publisher = Publisher(settings, Store(settings.database_path), HubClient(settings.token_file, settings.namespace))
    signal.signal(signal.SIGTERM, lambda *_: publisher.stop.set())
    signal.signal(signal.SIGINT, lambda *_: publisher.stop.set())
    publisher.run_forever()


if __name__ == "__main__":
    main()
