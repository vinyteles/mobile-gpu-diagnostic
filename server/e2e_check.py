"""Live end-to-end check: client -> relay (public URL) -> Hugging Face -> independent download.

Run: python -m server.e2e_check --base-url https://<tunnel>.trycloudflare.com --zip report.zip

Registers a `synthetic-test` session (public repo name contains "-test-"), so only use a
synthetic report ZIP (metadata.smokeTest=true). It prints no secrets.
"""

import argparse
import hashlib
import json
import secrets
import sys
import time
import uuid
from pathlib import Path

import httpx

HF = "https://huggingface.co"


def main() -> None:
    """Exercise registration retry, upload, duplicate, isolation, publication and HF retrieval."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--zip", type=Path, required=True, help="Synthetic app-generated report ZIP")
    parser.add_argument("--configuration", default="gemma-mixed48-gpu-replay")
    parser.add_argument("--timeout", type=float, default=300)
    args = parser.parse_args()
    data = args.zip.read_bytes()
    sha = hashlib.sha256(data).hexdigest()
    client = httpx.Client(base_url=args.base_url.rstrip("/"), timeout=120)
    results = []

    def check(name: str, condition: bool, detail: str = "") -> None:
        results.append(condition)
        print(f"{'PASS' if condition else 'FAIL'} {name} {detail}".rstrip(), flush=True)

    # 1. Health and identity.
    health = client.get("/v1/health").json()
    check("health", health.get("service") == "mobilebench-upload-relay", health.get("serviceInstanceId", ""))

    # 2. Registration, retried with the same secret (simulated lost response), returns the same session.
    secret = secrets.token_urlsafe(32)
    body = {"sessionSecret": secret, "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.1",
            "purpose": "synthetic-test"}
    first = client.post("/v1/sessions", json=body)
    retry = client.post("/v1/sessions", json=body)
    session = first.json()
    check("register", first.status_code == 201, session.get("repositoryUrl", ""))
    check("register retry idempotent", retry.status_code == 200 and retry.json()["sessionId"] == session["sessionId"])
    auth = {"Authorization": f"Bearer {secret}"}

    # 3. Upload a configuration snapshot, then the same bytes again (duplicate).
    headers = dict(auth, **{"Content-Type": "application/zip", "X-Content-SHA256": sha, "X-Checkpoint-Kind": "configuration",
                            "X-Configuration-Id": args.configuration, "X-Checkpoint-Created-At-Ms": str(int(time.time() * 1000)),
                            "X-App-Version": "2.1"})
    accepted = client.post(f"{session['checkpointsUrl']}", content=data, headers=headers)
    check("upload accepted", accepted.status_code == 202, accepted.text[:200] if accepted.status_code != 202 else "")
    duplicate = client.post(f"{session['checkpointsUrl']}", content=data, headers=headers)
    check("duplicate idempotent", duplicate.status_code == 200 and duplicate.json()["uploadId"] == accepted.json()["uploadId"])

    # 4. Another client's credential and a malformed ZIP are refused.
    other = secrets.token_urlsafe(32)
    forged = client.post(session["checkpointsUrl"], content=data, headers=dict(headers, Authorization=f"Bearer {other}"))
    check("foreign credential rejected", forged.status_code == 401)
    junk = b"PK\x03\x04 not really a zip"
    bad = client.post(session["checkpointsUrl"], content=junk,
                      headers=dict(headers, **{"X-Content-SHA256": hashlib.sha256(junk).hexdigest()}))
    check("malformed ZIP rejected", bad.status_code == 422, bad.json()["error"]["code"])

    # 5. Wait until the worker has published it.
    upload_url = accepted.json()["statusUrl"]
    deadline = time.monotonic() + args.timeout
    status = {}
    while time.monotonic() < deadline:
        status = client.get(upload_url, headers=auth).json()
        if status["state"] == "published":
            break
        time.sleep(5)
    check("published", status.get("state") == "published", status.get("commitUrl", ""))
    if status.get("state") != "published":
        sys.exit(1)

    # 6. Download anonymously from HF at the exact commit and verify the hash-matched pairs.
    repository, commit, run = session["repository"], status["commit"], status["cloudRunId"]
    hub = httpx.Client(follow_redirects=True, timeout=120)

    def fetch(path: str) -> bytes:
        response = hub.get(f"{HF}/datasets/{repository}/resolve/{commit}/{path}")
        response.raise_for_status()
        return response.content

    for json_path, zip_path in [("latest.json", f"runs/{run}/latest.zip"),
                                (f"runs/{run}/progress.json", f"runs/{run}/latest.zip"),
                                (f"runs/{run}/configurations/{args.configuration}.json",
                                 f"runs/{run}/configurations/{args.configuration}.zip")]:
        progress = json.loads(fetch(json_path))
        archive = fetch(zip_path)
        digest = hashlib.sha256(archive).hexdigest()
        check(f"HF pair {json_path}", progress["archivePath"] == zip_path and progress["archiveSha256"] == digest == sha)
    check("HF progress content", progress["cloudRunId"] == run and progress["smokeTest"] is True
          and progress["relay"]["clientReported"] is True, f"{progress['completedAnswers']} answers")
    session_status = client.get(session["statusUrl"], headers=auth).json()
    check("session synced", session_status["sync"]["state"] == "synced")
    feed = client.get("/v1/public/sessions").text
    check("feed lists session without secret", session["sessionId"] in feed and secret not in feed)
    print(json.dumps({"repository": repository, "repositoryUrl": session["repositoryUrl"], "commit": commit,
                      "passed": sum(results), "failed": len(results) - sum(results)}))
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
