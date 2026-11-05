"""HTTP API: session registration, checkpoint ZIP intake, status, and the public session feed.

Run: python -m server.api [--config path.toml]   (binds to loopback; HTTPS comes from the tunnel)

See server/API.md for the request/response contract.
"""

import argparse
import asyncio
import collections
import hashlib
import hmac
import html
import ipaddress
import json
import logging
import os
import re
import secrets
import shutil
import time
from pathlib import Path

import anyio
import uvicorn
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import (
    HTMLResponse,
    JSONResponse,
)
from starlette.routing import Route

from server import (
    API_VERSION,
    SERVICE_NAME,
)
from server.catalog import (
    BATCH_NAMES,
    CONFIGURATION_MODELS,
)
from server.config import (
    Settings,
    configure_logging,
    load_settings,
)
from server.feed import (
    progress_summary,
    public_feed,
    repository_url,
    sync_state,
)
from server.store import (
    DuplicateRow,
    Store,
    now_ms,
)
from server.validation import (
    ReportRejected,
    build_progress,
    inspect_archive,
)

LOGGER = logging.getLogger("mobilebench.api")
SECRET = re.compile(r"[A-Za-z0-9_-]{43,128}")
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
APP_VERSION = re.compile(r"[0-9A-Za-z.+_-]{1,32}")
SHA256 = re.compile(r"[0-9a-f]{64}")
ID = re.compile(r"[a-z]_[0-9a-f]{16,20}")
PURPOSES = ("benchmark", "synthetic-test")
MAX_JSON_BODY = 16 * 1024
HOUR_MS = 3600 * 1000
DAY_MS = 24 * HOUR_MS


class ApiError(Exception):
    """An error response with a stable code, retry hint and optional Retry-After."""

    def __init__(self, status: int, code: str, message: str, retryable: bool = False, retry_after_s: int | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.retry_after_s = retry_after_s


class RateLimiter:
    """In-memory sliding-window counters keyed by client address or session."""

    def __init__(self):
        self.events = collections.defaultdict(collections.deque)

    def hit(self, key: str, limit: int, window_s: float) -> None:
        """Count one event, raising 429 when the window is full.

        Args:
            key: Counter name, e.g. "register:203.0.113.4".
            limit: Maximum events per window.
            window_s: Window length in seconds.
        """
        now = time.monotonic()
        events = self.events[key]
        while events and events[0] <= now - window_s:
            events.popleft()
        if len(events) >= limit:
            retry = int(events[0] + window_s - now) + 1
            raise ApiError(429, "rate_limited", "Too many requests; retry later.", True, retry)
        events.append(now)
        if len(self.events) > 20000:
            self.events = collections.defaultdict(collections.deque, {k: v for k, v in self.events.items() if v})


class Relay:
    """Request handlers sharing settings, the database and in-process limits."""

    def __init__(self, settings: Settings, store: Store):
        """Bind state.

        Args:
            settings: Service settings.
            store: Database (used only from the event-loop thread).
        """
        self.settings = settings
        self.limits = settings.limits
        self.store = store
        self.rates = RateLimiter()
        self.receiving = 0
        self.validations = asyncio.Semaphore(settings.limits.concurrent_validations)
        self.salt = load_salt(settings.data_dir / "address-salt")
        self.instance_id = load_instance_id(settings.data_dir / "service-instance-id")

    # Public endpoints ------------------------------------------------------------------------

    async def health(self, request: Request) -> JSONResponse:
        """Liveness plus the persistent public identity clients compare with the bootstrap file."""
        body = {"service": SERVICE_NAME, "apiVersion": API_VERSION, "serviceInstanceId": self.instance_id, "status": "ok"}
        return JSONResponse(body, headers={"Cache-Control": "no-store"})

    async def ready(self, request: Request) -> JSONResponse:
        """Readiness: database, worker heartbeat, credential state and disk space."""
        worker, _ = self.store.get_state("worker")
        heartbeat_age_s = (now_ms() - worker["heartbeatAtMs"]) / 1000 if worker else None
        free = shutil.disk_usage(self.settings.spool_dir).free
        checks = {
            "database": True,
            "workerAlive": heartbeat_age_s is not None and heartbeat_age_s < 120,
            "hubCredential": bool(worker) and not worker.get("credentialError"),
            "diskSpace": free > self.limits.min_free_disk_bytes,
        }
        body = {
            "service": SERVICE_NAME,
            "ready": all(checks.values()),
            "checks": checks,
            "workerHeartbeatAgeS": heartbeat_age_s,
            "uploads": self.store.counts_by_state(),
            "pendingBytes": self.store.pending_bytes(),
        }
        return JSONResponse(body, status_code=200 if body["ready"] else 503)

    async def public_sessions(self, request: Request) -> JSONResponse:
        """Public JSON feed of sessions; contains no secrets."""
        self.rates.hit(f"read:{self.client_address(request)}", self.limits.reads_per_ip_per_hour, 3600)
        return JSONResponse({"service": SERVICE_NAME, "generatedAtMs": now_ms(), "sessions": public_feed(self.store)})

    async def status_page(self, request: Request) -> HTMLResponse:
        """Human-readable version of the public feed."""
        self.rates.hit(f"read:{self.client_address(request)}", self.limits.reads_per_ip_per_hour, 3600)
        return HTMLResponse(render_status_page(public_feed(self.store)))

    # Sessions --------------------------------------------------------------------------------

    async def register(self, request: Request) -> JSONResponse:
        """Create a session, or return the existing one bound to the same secret (retry-safe)."""
        # 1. Parse and validate the small JSON body.
        body = await read_json(request)
        secret = body.get("sessionSecret")
        installation = body.get("installationId")
        batch = body.get("batch")
        app_version = body.get("appVersion")
        purpose = body.get("purpose", "benchmark")
        if not isinstance(secret, str) or not SECRET.fullmatch(secret):
            raise ApiError(400, "invalid_secret", "sessionSecret must be 43-128 base64url characters (>= 256 random bits).")
        if not isinstance(installation, str) or not UUID.fullmatch(installation):
            raise ApiError(400, "invalid_installation", "installationId must be a lowercase UUID.")
        if batch not in BATCH_NAMES or isinstance(batch, bool):
            raise ApiError(400, "invalid_batch", "batch must be an integer from 1 to 5.")
        if not isinstance(app_version, str) or not APP_VERSION.fullmatch(app_version):
            raise ApiError(400, "invalid_app_version", "appVersion must be a short version string.")
        if purpose not in PURPOSES:
            raise ApiError(400, "invalid_purpose", "purpose must be 'benchmark' or 'synthetic-test'.")
        secret_hash = hash_secret(secret)
        digest = hashlib.sha256(json.dumps([installation, batch, purpose]).encode()).hexdigest()
        address = self.client_address(request)

        # 2. A known secret resumes its session; reusing it for different parameters is a conflict.
        existing = self.store.session_by_secret(secret_hash)
        if existing is None:
            self.rates.hit(f"register:{address}", self.limits.registrations_per_ip_per_hour, 3600)
            if self.store.count_sessions() >= self.limits.max_sessions:
                raise ApiError(429, "session_quota", "The relay has reached its session limit.", True, 3600)
            if self.store.count_sessions(now_ms() - DAY_MS) >= self.limits.registrations_per_day:
                raise ApiError(429, "registration_quota", "Daily session registrations are exhausted.", True, 3600)

            # 3. Choose a server-owned destination and persist before answering.
            session_id = "s_" + secrets.token_hex(8)
            stamp = time.strftime("%Y%m%d", time.gmtime())
            kind = "test-" if purpose == "synthetic-test" else ""
            repository = f"{self.settings.namespace}/{self.settings.repo_prefix}-{kind}{stamp}-b{batch}-{secrets.token_hex(5)}"
            try:
                self.store.insert_session({
                    "id": session_id,
                    "secret_hash": secret_hash,
                    "request_digest": digest,
                    "repository": repository,
                    "batch": batch,
                    "purpose": purpose,
                    "installation_prefix": installation[:8],
                    "app_version": app_version,
                    "created_at_ms": now_ms(),
                    "registrant_key": self.address_key(address),
                })
                LOGGER.info("registered session %s batch %s purpose %s", session_id, batch, purpose)
                return JSONResponse(self.session_document(self.store.session(session_id), created=True), status_code=201)
            except DuplicateRow:
                existing = self.store.session_by_secret(secret_hash)
                if existing is None:
                    raise
        if not hmac.compare_digest(existing["request_digest"], digest):
            raise ApiError(409, "secret_conflict", "This sessionSecret is bound to a different installation, batch or purpose.")
        return JSONResponse(self.session_document(existing, created=False))

    async def session_status(self, request: Request) -> JSONResponse:
        """Authenticated session status: accepted versus synced, latest progress and HF URLs."""
        session = self.authenticate(request)
        self.rates.hit(f"read:{self.client_address(request)}", self.limits.reads_per_ip_per_hour, 3600)
        return JSONResponse(self.session_document(session, created=False), headers={"Cache-Control": "no-store"})

    async def upload_status(self, request: Request) -> JSONResponse:
        """Authenticated status of one checkpoint upload."""
        session = self.authenticate(request)
        self.rates.hit(f"read:{self.client_address(request)}", self.limits.reads_per_ip_per_hour, 3600)
        upload = self.store.upload(session["id"], request.path_params["upload_id"])
        if upload is None:
            raise ApiError(404, "unknown_upload", "No such upload in this session.")
        return JSONResponse(self.upload_document(session, upload, duplicate=False), headers={"Cache-Control": "no-store"})

    # Checkpoints -----------------------------------------------------------------------------

    async def submit_checkpoint(self, request: Request) -> JSONResponse:
        """Receive a raw report ZIP, validate it, persist it durably, then acknowledge."""
        # 1. Authenticate and check headers before reading the body.
        session = self.authenticate(request)
        kind, configuration, created_at, expected_sha, app_version = parse_checkpoint_headers(request)
        address = self.client_address(request)
        self.rates.hit(f"upload-ip:{address}", self.limits.uploads_per_ip_per_hour, 3600)
        self.rates.hit(f"upload-session:{session['id']}", self.limits.uploads_per_session_per_hour, 3600)
        declared = request.headers.get("content-length")
        if declared is not None and (not declared.isdigit() or int(declared) > self.limits.max_upload_bytes):
            raise ApiError(413, "upload_too_large", f"Report ZIPs are limited to {self.limits.max_upload_bytes} bytes.")

        # 2. Apply quotas: per-session daily bytes, pending spool bytes and free disk.
        self.check_quotas(session, int(declared or 0))
        if self.receiving >= self.limits.concurrent_receives:
            raise ApiError(429, "server_busy", "The relay is receiving other reports; retry shortly.", True, 15)
        self.receiving += 1
        part = self.settings.spool_dir / "incoming" / f"{secrets.token_hex(12)}.part"
        try:
            # 3. Stream to disk with size/time bounds, hashing as we go.
            actual_sha, size = await receive_body(request, part, self.limits)
            if actual_sha != expected_sha:
                raise ApiError(400, "checksum_mismatch", "X-Content-SHA256 does not match the received bytes.", True)

            # 4. Identical retries return the original upload without re-validation.
            duplicate = self.store.find_upload(session["id"], actual_sha, kind, configuration)
            if duplicate is not None:
                return JSONResponse(self.upload_document(session, duplicate, duplicate=True))

            # 5. Validate the archive off the event loop, with bounded concurrency.
            async with self.validations:
                summary = await anyio.to_thread.run_sync(inspect_archive, part, self.limits)
            if kind == "configuration" and configuration not in summary.configuration_ids:
                raise ApiError(422, "configuration_not_in_report", "X-Configuration-Id is not present in report.json results.")
            cloud_run_id = f"{summary.local_run_id}-{session['installation_prefix']}"
            runs = self.store.run_ids(session["id"])
            if cloud_run_id not in runs and len(runs) >= self.limits.max_runs_per_session:
                raise ApiError(409, "run_quota", "This session already holds the maximum number of runs; register a new session.")

            # 6. Build progress.json server-side and persist blob + row before acknowledging.
            received = now_ms()
            upload_id = "u_" + secrets.token_hex(10)
            relay = {
                "sessionId": session["id"],
                "uploadId": upload_id,
                "receivedAtMs": received,
                "checkpointKind": kind,
                "clientCreatedAtMs": created_at,
                "clientReported": True,
                "note": "Device, timing and answers are reported by the app; the relay did not attest the hardware.",
            }
            progress = build_progress(summary, cloud_run_id, created_at or received, app_version or session["app_version"],
                                      BATCH_NAMES[session["batch"]], actual_sha, relay)
            blob = self.settings.spool_dir / session["id"] / f"{upload_id}.zip"
            await anyio.to_thread.run_sync(persist_blob, part, blob)
            row = {
                "id": upload_id,
                "session_id": session["id"],
                "sha256": actual_sha,
                "kind": kind,
                "configuration_id": configuration,
                "size_bytes": size,
                "client_created_at_ms": created_at or received,
                "received_at_ms": received,
                "cloud_run_id": cloud_run_id,
                "completed_answers": summary.completed_answers,
                "terminal": int(summary.terminal),
                "smoke_test": int(summary.smoke_test),
                "progress_json": json.dumps(progress, ensure_ascii=False),
                "blob_path": str(blob),
                "state": "accepted",
            }
            try:
                self.store.insert_upload(row)
            except DuplicateRow:
                blob.unlink(missing_ok=True)
                duplicate = self.store.find_upload(session["id"], actual_sha, kind, configuration)
                return JSONResponse(self.upload_document(session, duplicate, duplicate=True))
            LOGGER.info("accepted upload %s session %s kind %s run %s answers %d bytes %d", upload_id, session["id"], kind,
                        cloud_run_id, summary.completed_answers, size)
            return JSONResponse(self.upload_document(session, self.store.upload(session["id"], upload_id), duplicate=False),
                                status_code=202)
        except ReportRejected as error:
            LOGGER.info("rejected upload for session %s: %s", session["id"], error.code)
            raise ApiError(422, error.code, error.message) from None
        finally:
            self.receiving -= 1
            part.unlink(missing_ok=True)

    def check_quotas(self, session, incoming: int) -> None:
        """Enforce per-session daily volume, pending spool bytes and minimum free disk.

        Args:
            session: Authenticated session row.
            incoming: Declared body size (0 when unknown; the maximum is assumed for disk checks).
        """
        incoming = incoming or self.limits.max_upload_bytes
        _, day_bytes = self.store.upload_usage(session["id"], now_ms() - DAY_MS)
        if day_bytes + incoming > self.limits.upload_bytes_per_session_per_day:
            raise ApiError(429, "daily_volume_quota", "This session reached its daily upload volume.", True, 3600)
        if self.store.pending_bytes(session["id"]) + incoming > self.limits.max_pending_bytes_per_session:
            raise ApiError(507, "session_spool_full", "Earlier reports are still being published; retry later.", True, 300)
        if self.store.pending_bytes() + incoming > self.limits.max_spool_bytes:
            raise ApiError(507, "spool_full", "The relay spool is full; retry later.", True, 300)
        if shutil.disk_usage(self.settings.spool_dir).free - incoming < self.limits.min_free_disk_bytes:
            raise ApiError(507, "disk_full", "The relay is low on disk space; retry later.", True, 600)

    # Helpers ---------------------------------------------------------------------------------

    def authenticate(self, request: Request):
        """Return the session whose secret matches the bearer credential.

        Args:
            request: Request with path parameter session_id and Authorization header.
        Returns:
            Session row.
        """
        session_id = request.path_params["session_id"]
        header = request.headers.get("authorization", "")
        if not ID.fullmatch(session_id):
            raise ApiError(404, "unknown_session", "No such session.")
        session = self.store.session(session_id)
        if session is None:
            raise ApiError(404, "unknown_session", "No such session.")
        scheme, _, secret = header.partition(" ")
        if scheme.lower() != "bearer" or not hmac.compare_digest(hash_secret(secret.strip()), session["secret_hash"]):
            raise ApiError(401, "unauthorized", "Missing or invalid session credential.")
        return session

    def client_address(self, request: Request) -> str:
        """Return the client address, trusting the proxy header only from the local tunnel connector."""
        peer = request.client.host if request.client else "unknown"
        forwarded = request.headers.get(self.settings.client_ip_header)
        if peer in self.settings.trusted_proxies and forwarded:
            try:
                return str(ipaddress.ip_address(forwarded.strip()))
            except ValueError:
                pass
        return peer

    def address_key(self, address: str) -> str:
        """Return a salted, truncated address hash for stored registration accounting."""
        return hmac.new(self.salt, address.encode(), hashlib.sha256).hexdigest()[:16]

    def session_document(self, session, created: bool) -> dict:
        """Describe a session to its owner. Never echoes the secret."""
        sync = self.store.session_sync(session["id"])
        base = f"/{API_VERSION}/sessions/{session['id']}"
        return {
            "sessionId": session["id"],
            "created": created,
            "repository": session["repository"],
            "repositoryUrl": repository_url(session["repository"]),
            "repositoryCreated": bool(session["repo_created"]),
            "batch": session["batch"],
            "batchName": BATCH_NAMES[session["batch"]],
            "purpose": session["purpose"],
            "createdAtMs": session["created_at_ms"],
            "statusUrl": base,
            "checkpointsUrl": f"{base}/checkpoints",
            "sync": {
                "state": sync_state(sync),
                "pendingUploads": sync["pending"],
                "acceptedUploads": sync["total"],
                "oldestPendingReceivedAtMs": sync["oldest_pending_received_at_ms"],
                "lastPublishedAtMs": session["last_publish_at_ms"],
                "lastCommit": session["last_commit"],
                "lastError": session["last_error"],
                "nextAttemptAtMs": session["next_attempt_at_ms"] or None,
            },
            "latestAccepted": progress_summary(sync["latest_accepted"]),
            "latestPublished": progress_summary(sync["latest_published"]),
            "limits": {
                "maxUploadBytes": self.limits.max_upload_bytes,
                "maxUncompressedBytes": self.limits.max_uncompressed_bytes,
                "uploadsPerHour": self.limits.uploads_per_session_per_hour,
            },
        }

    def upload_document(self, session, upload, duplicate: bool) -> dict:
        """Describe one upload; publication URLs are included once it is on HF."""
        document = {
            "uploadId": upload["id"],
            "sessionId": session["id"],
            "duplicate": duplicate,
            "state": upload["state"],
            "kind": upload["kind"],
            "configurationId": upload["configuration_id"] or None,
            "sha256": upload["sha256"],
            "sizeBytes": upload["size_bytes"],
            "cloudRunId": upload["cloud_run_id"],
            "completedAnswers": upload["completed_answers"],
            "clientCreatedAtMs": upload["client_created_at_ms"],
            "receivedAtMs": upload["received_at_ms"],
            "publishedAtMs": upload["published_at_ms"],
            "commit": upload["commit_oid"],
            "repository": session["repository"],
            "repositoryUrl": repository_url(session["repository"]),
            "statusUrl": f"/{API_VERSION}/sessions/{session['id']}/checkpoints/{upload['id']}",
        }
        if upload["commit_oid"]:
            document["commitUrl"] = f"{repository_url(session['repository'])}/tree/{upload['commit_oid']}"
        return document


def parse_checkpoint_headers(request: Request) -> tuple[str, str, int | None, str, str | None]:
    """Validate upload headers.

    Args:
        request: Checkpoint request.
    Returns:
        (kind, configuration id or "", client creation time or None, expected sha256, app version or None).
    """
    content_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    if content_type not in ("application/zip", "application/octet-stream"):
        raise ApiError(415, "unsupported_media_type", "Send the raw ZIP with Content-Type: application/zip.")
    kind = request.headers.get("x-checkpoint-kind", "latest")
    configuration = request.headers.get("x-configuration-id", "")
    if kind not in ("latest", "configuration"):
        raise ApiError(400, "invalid_kind", "X-Checkpoint-Kind must be 'latest' or 'configuration'.")
    if (kind == "configuration") != bool(configuration) or (configuration and configuration not in CONFIGURATION_MODELS):
        raise ApiError(400, "invalid_configuration", "configuration checkpoints need a known X-Configuration-Id; latest ones none.")
    expected = request.headers.get("x-content-sha256", "").lower()
    if not SHA256.fullmatch(expected):
        raise ApiError(400, "missing_checksum", "X-Content-SHA256 (64 lowercase hex characters) is required.")
    created = request.headers.get("x-checkpoint-created-at-ms", "")
    created_at = int(created) if created.isdigit() else None
    # Implausible phone clocks fall back to server receipt time (recorded as such in relay metadata).
    if created_at is not None and not (now_ms() - 400 * DAY_MS < created_at < now_ms() + DAY_MS):
        created_at = None
    app_version = request.headers.get("x-app-version")
    if app_version is not None and not APP_VERSION.fullmatch(app_version):
        raise ApiError(400, "invalid_app_version", "X-App-Version must be a short version string.")
    return kind, configuration, created_at, expected, app_version


async def receive_body(request: Request, part: Path, limits) -> tuple[str, int]:
    """Stream the request body to a spool file with size, idle and total time limits.

    Args:
        request: Upload request.
        part: Temporary file path.
        limits: Size and timeout limits.
    Returns:
        (sha256 hex, byte count).
    """
    part.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    digest = hashlib.sha256()
    size = 0
    deadline = time.monotonic() + limits.body_total_timeout_s
    stream = request.stream().__aiter__()
    with part.open("wb") as output:
        while True:
            timeout = min(limits.body_idle_timeout_s, deadline - time.monotonic())
            try:
                chunk = await asyncio.wait_for(stream.__anext__(), timeout=max(timeout, 0.001))
            except StopAsyncIteration:
                break
            except TimeoutError:
                raise ApiError(408, "upload_timeout", "The upload was too slow; retry.", True) from None
            size += len(chunk)
            if size > limits.max_upload_bytes:
                raise ApiError(413, "upload_too_large", f"Report ZIPs are limited to {limits.max_upload_bytes} bytes.")
            digest.update(chunk)
            output.write(chunk)
        output.flush()
        os.fsync(output.fileno())
    if size == 0:
        raise ApiError(400, "empty_body", "The request body is empty.")
    return digest.hexdigest(), size


def persist_blob(part: Path, blob: Path) -> None:
    """Move a validated upload into the session spool and fsync the directory entry.

    Args:
        part: Fsynced temporary file.
        blob: Final spool path.
    """
    blob.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.replace(part, blob)
    directory = os.open(blob.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


async def read_json(request: Request) -> dict:
    """Read a small JSON object body."""
    body = await request.body()
    if len(body) > MAX_JSON_BODY:
        raise ApiError(413, "body_too_large", "JSON bodies are limited to 16 KiB.")
    try:
        value = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ApiError(400, "invalid_json", "Body must be a JSON object.") from None
    if not isinstance(value, dict):
        raise ApiError(400, "invalid_json", "Body must be a JSON object.")
    return value


def hash_secret(secret: str) -> str:
    """Return the stored form of a high-entropy session secret."""
    return hashlib.sha256(secret.encode()).hexdigest()


def load_salt(path: Path) -> bytes:
    """Load or create the private salt used to hash client addresses."""
    if not path.exists():
        path.write_bytes(secrets.token_bytes(32))
        path.chmod(0o600)
    return path.read_bytes()


def load_instance_id(path: Path) -> str:
    """Load or create the public service identity, stable across restarts of this deployment.

    Args:
        path: File in the data directory.
    Returns:
        Identifier such as "mbr_3f1c...". It is public; it is not a credential.
    """
    if not path.exists():
        temporary = path.with_suffix(".tmp")
        temporary.write_text("mbr_" + secrets.token_hex(16) + "\n")
        os.replace(temporary, path)
    value = path.read_text().strip()
    if not re.fullmatch(r"mbr_[0-9a-f]{32}", value):
        raise RuntimeError(f"{path} is corrupt; restore it rather than generating a new identity.")
    return value


def render_status_page(sessions: list[dict]) -> str:
    """Render the public feed as a small HTML table; every value is escaped."""
    rows = []
    for item in sessions:
        latest = item["latest"] or {}
        device = latest.get("device") or {}
        rows.append(
            "<tr>" + "".join(f"<td>{html.escape(str(value))}</td>" for value in (
                time.strftime("%Y-%m-%d %H:%M", time.gmtime(item["createdAtMs"] / 1000)),
                item["batchName"],
                item["purpose"] + (" (synthetic report)" if latest.get("smokeTest") else ""),
                f"{device.get('manufacturer', '')} {device.get('model', '')} {device.get('socModel', '')}".strip(),
                latest.get("completedAnswers", ""),
                (latest.get("queue") or {}).get("status", ""),
                item["sync"]["state"] + (" (retrying)" if item["sync"]["retrying"] else ""),
            )) + f'<td><a href="{html.escape(item["repositoryUrl"])}">{html.escape(item["repository"])}</a></td></tr>'
        )
    return (
        "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
        "<title>Mobile benchmark relay</title><style>body{font-family:sans-serif;margin:16px}"
        "table{border-collapse:collapse;font-size:14px}td,th{border:1px solid #ccc;padding:4px 6px;text-align:left}</style>"
        "</head><body><h1>Mobile benchmark relay</h1><p>Sessions uploaded through this relay. Device fields and counts are "
        "reported by the app. JSON: <a href='/v1/public/sessions'>/v1/public/sessions</a>.</p><table><tr><th>Created (UTC)</th>"
        "<th>Batch</th><th>Purpose</th><th>Device (reported)</th><th>Answers</th><th>Queue</th><th>Sync</th>"
        "<th>HF dataset</th></tr>" + "".join(rows) + "</table></body></html>"
    )


async def api_error(request: Request, error: ApiError) -> JSONResponse:
    """Serialize ApiError as {"error": {...}} with Retry-After when relevant."""
    headers = {"Retry-After": str(error.retry_after_s)} if error.retry_after_s else {}
    body = {"error": {"code": error.code, "message": error.message, "retryable": error.retryable}}
    return JSONResponse(body, status_code=error.status, headers=headers)


async def internal_error(request: Request, error: Exception) -> JSONResponse:
    """Hide internal details; the client may retry."""
    LOGGER.exception("unhandled error on %s %s", request.method, request.url.path)
    body = {"error": {"code": "internal_error", "message": "Internal error; retry later.", "retryable": True}}
    return JSONResponse(body, status_code=500, headers={"Retry-After": "30"})


def create_app(settings: Settings, store: Store) -> Starlette:
    """Build the ASGI application.

    Args:
        settings: Service settings.
        store: Database.
    Returns:
        Starlette app.
    """
    relay = Relay(settings, store)
    routes = [
        Route("/", relay.status_page, methods=["GET"]),
        Route("/v1/health", relay.health, methods=["GET"]),
        Route("/v1/ready", relay.ready, methods=["GET"]),
        Route("/v1/public/sessions", relay.public_sessions, methods=["GET"]),
        Route("/v1/sessions", relay.register, methods=["POST"]),
        Route("/v1/sessions/{session_id}", relay.session_status, methods=["GET"]),
        Route("/v1/sessions/{session_id}/checkpoints", relay.submit_checkpoint, methods=["POST"]),
        Route("/v1/sessions/{session_id}/checkpoints/{upload_id}", relay.upload_status, methods=["GET"]),
    ]
    return Starlette(routes=routes, exception_handlers={ApiError: api_error, Exception: internal_error})


def main() -> None:
    """Serve the API on loopback."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    settings = load_settings(args.config)
    configure_logging(settings, "api")
    app = create_app(settings, Store(settings.database_path))
    uvicorn.run(app, host=settings.host, port=settings.port, access_log=False, proxy_headers=False,
                server_header=False, timeout_keep_alive=15, limit_concurrency=64, log_config=None)


if __name__ == "__main__":
    main()
