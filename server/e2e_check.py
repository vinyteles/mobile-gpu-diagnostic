"""Live completion-gate check: client -> relay (public URL) -> Hugging Face -> independent download.

Run: python -m server.e2e_check --base-url https://<tunnel>.trycloudflare.com [--restart-command "..."]

It registers a `synthetic-test` session (public repo name contains "-test-") and sends synthetic
cumulative snapshots (metadata.smokeTest = true, placeholder answers, no model output):

    1, 42, 119 answers, a stopped 119 snapshot and an errored 119 configuration snapshot
        -> all "stored"; the HF repository must not exist
    optional restart of the relay processes -> the saved progress must still be 119/120
    120 answers -> published; the JSON/ZIP pair is downloaded anonymously at the commit and hash-checked
    a stale 119 snapshot after publication -> superseded; HF still holds the 120 report

It prints no secrets.
"""

import argparse
import hashlib
import json
import secrets
import shlex
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx

from server.catalog import BATCH_CONFIGURATIONS
from server.synthetic import (
    FIXTURE_PATH,
    fixture_question_ids,
    synthetic_report,
    synthetic_zip,
)

HF = "https://huggingface.co"


class LiveCheck:
    """Relay client plus PASS/FAIL bookkeeping for one synthetic session."""

    def __init__(self, base_url: str, fixture: bytes, batch: int):
        """Prepare the HTTP clients and the synthetic report inputs.

        Args:
            base_url: Public relay URL.
            fixture: rag-replay.json bytes.
            batch: Single-configuration profile (101..110) used for the session.
        """
        self.client = httpx.Client(base_url=base_url.rstrip("/"), timeout=120)
        self.hub = httpx.Client(follow_redirects=True, timeout=120)
        self.fixture = fixture
        self.question_ids = fixture_question_ids(fixture)
        self.batch = batch
        self.configuration = BATCH_CONFIGURATIONS[batch][0]
        self.run_id = f"relay-live-{int(time.time())}"
        self.results = []
        self.session = {}
        self.auth = {}

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        """Record and print one PASS/FAIL line."""
        self.results.append(bool(condition))
        print(f"{'PASS' if condition else 'FAIL'} {name} {detail}".rstrip(), flush=True)
        return bool(condition)

    def snapshot(self, answers: int, queue_status: str = "running", status: str | None = None) -> bytes:
        """Return a cumulative synthetic ZIP with the first `answers` questions completed."""
        report = synthetic_report(self.run_id, {self.configuration: answers}, self.question_ids, queue_status)
        if status:
            report["results"][0]["status"] = status
        return synthetic_zip(report, self.fixture)

    def send(self, data: bytes, created_at_ms: int, kind: str = "latest") -> httpx.Response:
        """POST one checkpoint with the APK's headers."""
        headers = dict(self.auth, **{"Content-Type": "application/zip", "X-Content-SHA256": hashlib.sha256(data).hexdigest(),
                                     "X-Checkpoint-Kind": kind, "X-Checkpoint-Created-At-Ms": str(created_at_ms),
                                     "X-App-Version": "synthetic"})
        if kind == "configuration":
            headers["X-Configuration-Id"] = self.configuration
        return self.client.post(self.session["checkpointsUrl"], content=data, headers=headers)

    def status(self) -> dict:
        """Return the authenticated session status."""
        return self.client.get(self.session["statusUrl"], headers=self.auth).json()

    def hub_repository_exists(self) -> bool:
        """Return whether the session's dataset is visible on HF (anonymous request)."""
        response = self.hub.get(f"{HF}/api/datasets/{self.session['repository']}")
        return response.status_code == 200

    def wait_for_upload(self, upload_url: str, state: str, timeout: float) -> dict:
        """Poll one upload until it reaches `state` or the timeout expires."""
        deadline = time.monotonic() + timeout
        document = {}
        while time.monotonic() < deadline:
            document = self.client.get(upload_url, headers=self.auth).json()
            if document.get("state") == state:
                break
            time.sleep(5)
        return document

    def fetch(self, commit: str, path: str) -> bytes:
        """Download one file of the session dataset at an exact commit, anonymously."""
        response = self.hub.get(f"{HF}/datasets/{self.session['repository']}/resolve/{commit}/{path}")
        response.raise_for_status()
        return response.content


def wait_until_ready(client: httpx.Client, timeout: float) -> bool:
    """Wait until the relay answers /v1/ready with 200 again (after a restart)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if client.get("/v1/ready").status_code == 200:
                return True
        except httpx.HTTPError:
            pass
        time.sleep(3)
    return False


def main() -> None:
    """Exercise the partial-storage gate, optional restart, completion publication and ordering."""
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--fixture", type=Path, default=FIXTURE_PATH)
    parser.add_argument("--batch", type=int, default=101, choices=range(101, 111))
    parser.add_argument("--expect-instance", help="Required serviceInstanceId")
    parser.add_argument("--restart-command", help="Command restarting the relay api+worker between phases")
    parser.add_argument("--settle", type=float, default=20, help="Seconds the worker gets to (not) publish partials")
    parser.add_argument("--timeout", type=float, default=300)
    args = parser.parse_args()
    live = LiveCheck(args.base_url, args.fixture.read_bytes(), args.batch)
    t0 = int(time.time() * 1000)

    # 1. Health and identity.
    health = live.client.get("/v1/health").json()
    live.check("health", health.get("service") == "mobilebench-upload-relay", health.get("serviceInstanceId", ""))
    if args.expect_instance:
        live.check("service identity", health.get("serviceInstanceId") == args.expect_instance)

    # 2. Registration, retried with the same secret (simulated lost response), returns the same session.
    secret = secrets.token_urlsafe(32)
    body = {"sessionSecret": secret, "installationId": str(uuid.uuid4()), "batch": args.batch, "appVersion": "synthetic",
            "purpose": "synthetic-test"}
    first = live.client.post("/v1/sessions", json=body)
    retry = live.client.post("/v1/sessions", json=body)
    live.session = first.json()
    live.auth = {"Authorization": f"Bearer {secret}"}
    live.check("register", first.status_code == 201, live.session.get("repositoryUrl", ""))
    live.check("register retry idempotent", retry.status_code == 200 and retry.json()["sessionId"] == live.session["sessionId"])

    # 3. Partial snapshots, including stop and error/configuration ones, are stored only.
    forty_two = live.snapshot(42)
    partials = [
        (live.snapshot(1), t0 + 1, "latest"),
        (forty_two, t0 + 42, "latest"),
        (live.snapshot(119), t0 + 119, "latest"),
        (live.snapshot(119, queue_status="stopped"), t0 + 120, "latest"),
        (live.snapshot(119, queue_status="error", status="error"), t0 + 121, "configuration"),
    ]
    for data, created, kind in partials:
        response = live.send(data, created, kind)
        document = response.json()
        live.check(f"partial {kind} stored", response.status_code == 202 and document.get("state") == "stored",
                   f"{document.get('savedAnswers')}/{document.get('requiredAnswers')}")
    duplicate = live.send(forty_two, t0 + 42)
    live.check("duplicate idempotent", duplicate.status_code == 200 and duplicate.json()["duplicate"] is True)
    time.sleep(args.settle)
    status = live.status()
    live.check("status collecting", status["sync"]["state"] == "collecting" and status["sync"]["pendingUploads"] == 0)
    live.check("received 119/120", (status["sync"]["receivedAnswers"], status["sync"]["expectedAnswers"]) == (119, 120))
    live.check("no HF repository for partials", not status["repositoryCreated"] and not live.hub_repository_exists())

    # 4. Optional restart: stored progress must survive the API and worker processes.
    if args.restart_command:
        subprocess.run(shlex.split(args.restart_command), check=True)
        live.check("relay ready after restart", wait_until_ready(live.client, 180))
        status = live.status()
        live.check("progress survived restart", status["sync"]["receivedAnswers"] == 119
                   and status["sync"]["state"] == "collecting" and not live.hub_repository_exists())

    # 5. The 120th answer completes the report; wait for publication.
    complete = live.snapshot(120, queue_status="completed")
    accepted = live.send(complete, t0 + 200)
    live.check("complete accepted", accepted.status_code == 202 and accepted.json()["state"] == "accepted")
    published = live.wait_for_upload(accepted.json()["statusUrl"], "published", args.timeout)
    if not live.check("published", published.get("state") == "published", published.get("commitUrl", "")):
        sys.exit(1)

    # 6. Download anonymously from HF at the exact commit and verify the hash-matched pairs.
    commit, run = published["commit"], published["cloudRunId"]
    sha = hashlib.sha256(complete).hexdigest()
    for json_path in ("latest.json", f"runs/{run}/progress.json"):
        progress = json.loads(live.fetch(commit, json_path))
        archive = live.fetch(commit, f"runs/{run}/latest.zip")
        digest = hashlib.sha256(archive).hexdigest()
        live.check(f"HF pair {json_path}", progress["archivePath"] == f"runs/{run}/latest.zip"
                   and progress["archiveSha256"] == digest == sha, f"{progress['completedAnswers']} answers")
    files = live.hub.get(f"{HF}/api/datasets/{live.session['repository']}/tree/{commit}?recursive=true").json()
    paths = sorted(item["path"] for item in files if item.get("type") == "file")
    live.check("HF holds only the complete report", "/configurations/" not in " ".join(paths), ", ".join(paths))
    history = live.hub.get(f"{HF}/api/datasets/{live.session['repository']}/commits/main").json()
    relay_commits = [item["title"] for item in history if item["title"].startswith("Relay ")]
    live.check("every relay commit is complete", relay_commits and all("complete (120/120" in t for t in relay_commits),
               f"{len(relay_commits)} relay commit(s)")

    # 7. A stale partial after publication is superseded and HF keeps the complete report.
    stale = live.send(live.snapshot(119), t0 + 150).json()
    stale_state = live.wait_for_upload(stale["statusUrl"], "superseded", 60).get("state")
    latest = json.loads(live.hub.get(f"{HF}/datasets/{live.session['repository']}/resolve/main/latest.json").content)
    live.check("stale snapshot superseded", stale_state == "superseded" and latest["completedAnswers"] == 120)
    status = live.status()
    live.check("session synced", status["sync"]["state"] == "synced" and status["sync"]["receivedAnswers"] == 120)
    feed = live.client.get("/v1/public/sessions").text
    live.check("feed lists session without secret", live.session["sessionId"] in feed and secret not in feed)
    print(json.dumps({"repository": live.session["repository"], "repositoryUrl": live.session["repositoryUrl"],
                      "commit": commit, "passed": sum(live.results), "failed": len(live.results) - sum(live.results)}))
    sys.exit(0 if all(live.results) else 1)


if __name__ == "__main__":
    main()
