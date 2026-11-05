"""Public, capability-free session summaries for the JSON feed, HTML page and HF index.

Example feed entry:
    {"sessionId": "s_k3...", "repository": "vinyteles/mobilebench-relay-20261105-b1-k3...",
     "repositoryUrl": "https://huggingface.co/datasets/...", "batch": 1, "purpose": "benchmark",
     "sync": {"state": "collecting", ...},
     "latest": {"completedAnswers": 37, "savedAnswers": 37, "requiredAnswers": 120, "complete": false, ...}}

Feeds expose progress counts and client-reported device fields only, never answers or metrics.
"""

import json
import sqlite3

from server.catalog import (
    BATCH_CONFIGURATIONS,
    BATCH_NAMES,
    QUESTION_COUNT,
)
from server.store import Store

HF_ORIGIN = "https://huggingface.co"
DEVICE_FIELDS = ("manufacturer", "model", "socManufacturer", "socModel", "androidRelease")


def repository_url(repository: str) -> str:
    """Return the public dataset URL of a repository id."""
    return f"{HF_ORIGIN}/datasets/{repository}"


def sync_state(sync: dict) -> str:
    """Classify a session's upload state.

    Args:
        sync: Output of Store.session_sync().
    Returns:
        "idle" (nothing uploaded yet), "pending" (a complete report awaits HF publication),
        "collecting" (partial reports are stored on the relay only) or "synced" (complete reports on HF).
    """
    if sync["total"] == 0:
        return "idle"
    if sync["pending"]:
        return "pending"
    if sync["stored"] or not sync["published"]:
        return "collecting"
    return "synced"


def expected_answers(batch: int) -> int:
    """Return the answers a session must hold before HF publication (120 per assigned configuration)."""
    return QUESTION_COUNT * len(BATCH_CONFIGURATIONS[batch])


def progress_summary(upload: sqlite3.Row | None) -> dict | None:
    """Reduce a stored progress document to public, size-bounded fields.

    Args:
        upload: Upload row or None.
    Returns:
        Summary with client-reported device and counts, or None.
    """
    if upload is None:
        return None
    progress = json.loads(upload["progress_json"])
    device = progress.get("device") or {}
    queue = progress.get("queue") or {}
    return {
        "uploadId": upload["id"],
        "state": upload["state"],
        "cloudRunId": upload["cloud_run_id"],
        "updatedAtMs": progress.get("updatedAtMs"),
        "receivedAtMs": upload["received_at_ms"],
        "appVersion": progress.get("appVersion"),
        "smokeTest": progress.get("smokeTest"),
        "completedAnswers": progress.get("completedAnswers"),
        "savedAnswers": upload["saved_answers"],
        "requiredAnswers": upload["required_answers"],
        "complete": bool(upload["complete"]),
        "queue": {"status": str(queue.get("status", ""))[:40], "stage": str(queue.get("stage", ""))[:40]},
        "configurations": [
            {key: row.get(key) for key in ("id", "status", "completed", "total")} for row in progress.get("configurations", [])
        ],
        "device": {key: str(device.get(key, ""))[:80] for key in DEVICE_FIELDS},
        "clientReported": True,
    }


def public_session(store: Store, session: sqlite3.Row) -> dict:
    """Build one public feed entry; never includes secrets, installation ids or client addresses.

    Args:
        store: Database.
        session: Session row.
    Returns:
        JSON-serializable summary.
    """
    sync = store.session_sync(session["id"])
    return {
        "sessionId": session["id"],
        "repository": session["repository"],
        "repositoryUrl": repository_url(session["repository"]),
        "repositoryCreated": bool(session["repo_created"]),
        "batch": session["batch"],
        "batchName": BATCH_NAMES[session["batch"]],
        "purpose": session["purpose"],
        "createdAtMs": session["created_at_ms"],
        "sync": {
            "state": sync_state(sync),
            "pendingUploads": sync["pending"],
            "storedUploads": sync["stored"],
            "acceptedUploads": sync["total"],
            "receivedAnswers": sync["received_answers"],
            "expectedAnswers": expected_answers(session["batch"]),
            "lastPublishedAtMs": session["last_publish_at_ms"],
            "lastCommit": session["last_commit"],
            "retrying": bool(session["last_error"]),
        },
        "latest": progress_summary(sync["latest_accepted"]),
    }


def public_feed(store: Store, limit: int = 500) -> list[dict]:
    """Return public summaries of the newest sessions.

    Args:
        store: Database.
        limit: Maximum sessions listed.
    Returns:
        Feed entries, newest session first.
    """
    return [public_session(store, session) for session in store.all_sessions(limit)]
