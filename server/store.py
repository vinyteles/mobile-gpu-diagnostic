"""Durable SQLite state shared by the API (writer of sessions/uploads) and the worker (publisher).

Tables:
    sessions      one row per registered benchmark session and its server-owned HF repository
    uploads       one row per accepted checkpoint ZIP (see upload states below)
    publications  newest published snapshot per destination slot, so late uploads never overwrite newer data
    service_state small key/value facts (worker heartbeat, credential status, index digest)

Upload states:
    stored      partial report (assigned configurations not yet 120/120); kept on disk, never sent to HF
    accepted    complete report waiting for HF publication
    published   committed to HF (blob released)
    superseded  every completed question is also in a newer retained snapshot of the run (blob released)

Order keys compare (client_created_at_ms, completed_answers, received_at_ms) tuples.
completed_questions holds {"<configuration id>": "<hex bitmap of completed fixture positions>"}.
"""

import json
import sqlite3
import threading
import time
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    secret_hash TEXT NOT NULL UNIQUE,
    request_digest TEXT NOT NULL,
    repository TEXT NOT NULL UNIQUE,
    batch INTEGER NOT NULL,
    purpose TEXT NOT NULL,
    installation_prefix TEXT NOT NULL,
    app_version TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    registrant_key TEXT NOT NULL,
    repo_created INTEGER NOT NULL DEFAULT 0,
    failures INTEGER NOT NULL DEFAULT 0,
    next_attempt_at_ms INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_publish_at_ms INTEGER,
    last_commit TEXT
);
CREATE TABLE IF NOT EXISTS uploads (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id),
    sha256 TEXT NOT NULL,
    kind TEXT NOT NULL,
    configuration_id TEXT NOT NULL DEFAULT '',
    size_bytes INTEGER NOT NULL,
    client_created_at_ms INTEGER NOT NULL,
    received_at_ms INTEGER NOT NULL,
    cloud_run_id TEXT NOT NULL,
    completed_answers INTEGER NOT NULL,
    terminal INTEGER NOT NULL,
    smoke_test INTEGER NOT NULL,
    progress_json TEXT NOT NULL,
    saved_answers INTEGER NOT NULL DEFAULT 0,
    required_answers INTEGER NOT NULL DEFAULT 0,
    complete INTEGER NOT NULL DEFAULT 0,
    completed_questions TEXT NOT NULL DEFAULT '{}',
    blob_path TEXT,
    state TEXT NOT NULL,
    commit_oid TEXT,
    published_at_ms INTEGER,
    UNIQUE (session_id, sha256, kind, configuration_id)
);
CREATE INDEX IF NOT EXISTS uploads_by_state ON uploads (state, session_id);
CREATE INDEX IF NOT EXISTS uploads_by_session ON uploads (session_id, received_at_ms);
CREATE TABLE IF NOT EXISTS publications (
    session_id TEXT NOT NULL,
    slot TEXT NOT NULL,
    order_created_at_ms INTEGER NOT NULL,
    order_completed INTEGER NOT NULL,
    order_received_at_ms INTEGER NOT NULL,
    upload_id TEXT NOT NULL,
    commit_oid TEXT,
    PRIMARY KEY (session_id, slot)
);
CREATE TABLE IF NOT EXISTS service_state (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at_ms INTEGER NOT NULL
);
"""
# States whose spool blob must be kept, and states whose content is still authoritative.
KEPT_STATES = ("stored", "accepted")
RETAINED_STATES = ("stored", "accepted", "published")


def now_ms() -> int:
    """Return the current wall-clock time in epoch milliseconds."""
    return int(time.time() * 1000)


def order_key(upload: sqlite3.Row | dict) -> tuple[int, int, int]:
    """Return the snapshot ordering key of an upload row.

    Args:
        upload: Row with client_created_at_ms, completed_answers and received_at_ms.
    Returns:
        Tuple compared lexicographically; larger is newer.
    """
    return (upload["client_created_at_ms"], upload["completed_answers"], upload["received_at_ms"])


def encode_questions(completed_questions: dict[str, int]) -> str:
    """Serialize configuration -> completed-question bitmap for the completed_questions column.

    Args:
        completed_questions: e.g. {"gemma-mixed48-gpu-replay": 7} (questions 0, 1 and 2 completed).
    Returns:
        JSON text such as '{"gemma-mixed48-gpu-replay": "7"}'.
    """
    return json.dumps({configuration: format(bitmap, "x") for configuration, bitmap in completed_questions.items()},
                      sort_keys=True)


def decode_questions(upload: sqlite3.Row | dict) -> dict[str, int]:
    """Return the configuration -> completed-question bitmap of an upload row."""
    return {configuration: int(bitmap, 16) for configuration, bitmap in json.loads(upload["completed_questions"]).items()}


def contains_questions(newer: dict[str, int], older: dict[str, int]) -> bool:
    """Return whether every completed (configuration, question) of `older` is also completed in `newer`."""
    return all(bitmap & ~newer.get(configuration, 0) == 0 for configuration, bitmap in older.items())


class DuplicateRow(Exception):
    """A UNIQUE constraint raced with a concurrent insert of the same logical row."""


class Store:
    """Thread-safe access to the relay database; each thread uses its own connection."""

    def __init__(self, path: Path):
        """Open (and create) the database.

        Args:
            path: SQLite file path; WAL mode allows the API and worker processes to share it.
        """
        self.path = path
        self.local = threading.local()
        self.connection().executescript(SCHEMA)

    def connection(self) -> sqlite3.Connection:
        """Return this thread's connection, opening it on first use."""
        connection = getattr(self.local, "connection", None)
        if connection is None:
            connection = sqlite3.connect(self.path, timeout=30, isolation_level=None)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("PRAGMA foreign_keys=ON")
            self.local.connection = connection
        return connection

    def transaction(self):
        """Return a context manager running one IMMEDIATE (write-locked) transaction."""
        return _Transaction(self.connection())

    def query(self, sql: str, parameters: tuple = ()) -> list[sqlite3.Row]:
        """Run a read query outside any explicit transaction."""
        return self.connection().execute(sql, parameters).fetchall()

    # Sessions -------------------------------------------------------------------------------

    def session_by_secret(self, secret_hash: str) -> sqlite3.Row | None:
        """Find the session bound to a secret hash."""
        rows = self.query("SELECT * FROM sessions WHERE secret_hash = ?", (secret_hash,))
        return rows[0] if rows else None

    def session(self, session_id: str) -> sqlite3.Row | None:
        """Find a session by id."""
        rows = self.query("SELECT * FROM sessions WHERE id = ?", (session_id,))
        return rows[0] if rows else None

    def insert_session(self, row: dict) -> None:
        """Insert a new session.

        Args:
            row: Column values for the sessions table.
        Raises:
            DuplicateRow: the secret (or improbably the id/repository) is already registered.
        """
        columns = ", ".join(row)
        placeholders = ", ".join("?" for _ in row)
        try:
            with self.transaction() as connection:
                connection.execute(f"INSERT INTO sessions ({columns}) VALUES ({placeholders})", tuple(row.values()))
        except sqlite3.IntegrityError:
            raise DuplicateRow() from None

    def count_sessions(self, since_ms: int = 0) -> int:
        """Count sessions created at or after a time."""
        return self.query("SELECT COUNT(*) FROM sessions WHERE created_at_ms >= ?", (since_ms,))[0][0]

    def set_repo_created(self, session_id: str) -> None:
        """Remember that the session's HF repository exists."""
        with self.transaction() as connection:
            connection.execute("UPDATE sessions SET repo_created = 1 WHERE id = ?", (session_id,))

    def record_failure(self, session_id: str, error: str, retry_at_ms: int) -> None:
        """Record a failed publication attempt and when to retry the session."""
        with self.transaction() as connection:
            connection.execute(
                "UPDATE sessions SET failures = failures + 1, last_error = ?, next_attempt_at_ms = ? WHERE id = ?",
                (error, retry_at_ms, session_id),
            )

    # Uploads ---------------------------------------------------------------------------------

    def find_upload(self, session_id: str, sha256: str, kind: str, configuration_id: str) -> sqlite3.Row | None:
        """Find an identical earlier upload (idempotent retry)."""
        rows = self.query(
            "SELECT * FROM uploads WHERE session_id = ? AND sha256 = ? AND kind = ? AND configuration_id = ?",
            (session_id, sha256, kind, configuration_id),
        )
        return rows[0] if rows else None

    def upload(self, session_id: str, upload_id: str) -> sqlite3.Row | None:
        """Return one upload of a session."""
        rows = self.query("SELECT * FROM uploads WHERE session_id = ? AND id = ?", (session_id, upload_id))
        return rows[0] if rows else None

    def insert_upload(self, row: dict) -> None:
        """Persist an accepted upload; the caller has already fsynced its blob.

        Args:
            row: Column values for the uploads table.
        Raises:
            DuplicateRow: an identical upload was accepted concurrently.
        """
        columns = ", ".join(row)
        placeholders = ", ".join("?" for _ in row)
        try:
            with self.transaction() as connection:
                connection.execute(f"INSERT INTO uploads ({columns}) VALUES ({placeholders})", tuple(row.values()))
        except sqlite3.IntegrityError:
            raise DuplicateRow() from None

    def upload_usage(self, session_id: str, since_ms: int) -> tuple[int, int]:
        """Return (count, bytes) of a session's accepted uploads since a time."""
        row = self.query(
            "SELECT COUNT(*), COALESCE(SUM(size_bytes), 0) FROM uploads WHERE session_id = ? AND received_at_ms >= ?",
            (session_id, since_ms),
        )[0]
        return row[0], row[1]

    def pending_bytes(self, session_id: str | None = None) -> int:
        """Return spooled bytes still kept (stored partials and unpublished complete reports)."""
        if session_id is None:
            return self.query(
                "SELECT COALESCE(SUM(size_bytes), 0) FROM uploads WHERE state IN ('stored', 'accepted')"
            )[0][0]
        return self.query(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM uploads WHERE state IN ('stored', 'accepted') AND session_id = ?",
            (session_id,),
        )[0][0]

    def run_ids(self, session_id: str) -> set[str]:
        """Return the distinct cloud run ids already accepted for a session."""
        return {row[0] for row in self.query("SELECT DISTINCT cloud_run_id FROM uploads WHERE session_id = ?", (session_id,))}

    def retained_uploads(self, session_id: str) -> list[sqlite3.Row]:
        """Return a session's stored, accepted and published uploads, oldest snapshot first."""
        rows = self.query(
            "SELECT * FROM uploads WHERE session_id = ? AND state IN ('stored', 'accepted', 'published')", (session_id,)
        )
        return sorted(rows, key=order_key)

    def sessions_with_pending(self, at_ms: int) -> list[sqlite3.Row]:
        """Return sessions holding stored or accepted uploads that are not in retry backoff."""
        return self.query(
            "SELECT * FROM sessions WHERE next_attempt_at_ms <= ? AND id IN "
            "(SELECT DISTINCT session_id FROM uploads WHERE state IN ('stored', 'accepted')) ORDER BY next_attempt_at_ms",
            (at_ms,),
        )

    def mark_superseded(self, upload_ids: list[str]) -> None:
        """Mark stored/accepted uploads whose completed questions are all held by a newer retained snapshot."""
        with self.transaction() as connection:
            connection.executemany(
                "UPDATE uploads SET state = 'superseded' WHERE id = ? AND state IN ('stored', 'accepted')",
                [(i,) for i in upload_ids],
            )

    def publications(self, session_id: str) -> dict[str, tuple[int, int, int]]:
        """Return the published order key of each slot of a session."""
        rows = self.query("SELECT * FROM publications WHERE session_id = ?", (session_id,))
        return {
            row["slot"]: (row["order_created_at_ms"], row["order_completed"], row["order_received_at_ms"]) for row in rows
        }

    def mark_published(self, upload: sqlite3.Row, slots: list[str], commit_oid: str) -> None:
        """Record a successful HF commit atomically: upload state, slot keys and session sync facts.

        Args:
            upload: Published upload row.
            slots: Destination slots updated by the commit.
            commit_oid: HF commit id.
        """
        published_at = now_ms()
        created, completed, received = order_key(upload)
        with self.transaction() as connection:
            connection.execute(
                "UPDATE uploads SET state = 'published', commit_oid = ?, published_at_ms = ? WHERE id = ?",
                (commit_oid, published_at, upload["id"]),
            )
            for slot in slots:
                connection.execute(
                    "INSERT INTO publications VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (session_id, slot) DO UPDATE SET "
                    "order_created_at_ms = excluded.order_created_at_ms, order_completed = excluded.order_completed, "
                    "order_received_at_ms = excluded.order_received_at_ms, upload_id = excluded.upload_id, "
                    "commit_oid = excluded.commit_oid",
                    (upload["session_id"], slot, created, completed, received, upload["id"], commit_oid),
                )
            connection.execute(
                "UPDATE sessions SET failures = 0, last_error = NULL, next_attempt_at_ms = 0, last_publish_at_ms = ?, "
                "last_commit = ? WHERE id = ?",
                (published_at, commit_oid, upload["session_id"]),
            )

    def clear_blob(self, upload_id: str) -> None:
        """Forget the spool path of an upload whose blob was deleted."""
        with self.transaction() as connection:
            connection.execute("UPDATE uploads SET blob_path = NULL WHERE id = ?", (upload_id,))

    def releasable_blobs(self) -> list[sqlite3.Row]:
        """Return published/superseded uploads whose spool blob still exists in the database."""
        return self.query(
            "SELECT id, blob_path FROM uploads WHERE state IN ('published', 'superseded') AND blob_path IS NOT NULL"
        )

    def pending_blob_paths(self) -> set[str]:
        """Return spool paths that must be kept: stored partials and unpublished complete reports."""
        return {
            row[0] for row in self.query(
                "SELECT blob_path FROM uploads WHERE state IN ('stored', 'accepted') AND blob_path IS NOT NULL"
            )
        }

    # Status ----------------------------------------------------------------------------------

    def session_sync(self, session_id: str) -> dict:
        """Summarize accepted versus published uploads of one session.

        Args:
            session_id: Session id.
        Returns:
            Counts (pending = complete awaiting HF, stored = retained partials), the newest run's
            received (distinct completed, assigned) answers, timestamps and latest accepted/published rows.
        """
        pending = self.query(
            "SELECT COUNT(*), MIN(received_at_ms) FROM uploads WHERE session_id = ? AND state = 'accepted'", (session_id,)
        )[0]
        counts = {row[0]: row[1] for row in self.query(
            "SELECT state, COUNT(*) FROM uploads WHERE session_id = ? GROUP BY state", (session_id,)
        )}
        latest_accepted = self.query(
            "SELECT * FROM uploads WHERE session_id = ? ORDER BY client_created_at_ms DESC, completed_answers DESC, "
            "received_at_ms DESC LIMIT 1",
            (session_id,),
        )
        latest_published = self.query(
            "SELECT * FROM uploads WHERE session_id = ? AND state = 'published' ORDER BY published_at_ms DESC LIMIT 1",
            (session_id,),
        )
        total = self.query("SELECT COUNT(*) FROM uploads WHERE session_id = ?", (session_id,))[0][0]
        received = 0
        if latest_accepted:
            # Most distinct completed questions held for the newest run (any snapshot, including coalesced ones).
            received = self.query(
                "SELECT MAX(saved_answers) FROM uploads WHERE session_id = ? AND cloud_run_id = ?",
                (session_id, latest_accepted[0]["cloud_run_id"]),
            )[0][0]
        return {
            "received_answers": received,
            "pending": pending[0],
            "stored": counts.get("stored", 0),
            "published": counts.get("published", 0),
            "oldest_pending_received_at_ms": pending[1],
            "total": total,
            "latest_accepted": latest_accepted[0] if latest_accepted else None,
            "latest_published": latest_published[0] if latest_published else None,
        }

    def all_sessions(self, limit: int) -> list[sqlite3.Row]:
        """Return the most recently created sessions."""
        return self.query("SELECT * FROM sessions ORDER BY created_at_ms DESC LIMIT ?", (limit,))

    def counts_by_state(self) -> dict[str, int]:
        """Return the number of uploads per state."""
        return {row[0]: row[1] for row in self.query("SELECT state, COUNT(*) FROM uploads GROUP BY state")}

    def get_state(self, key: str) -> tuple[dict | None, int | None]:
        """Read a service_state entry as (JSON value, updated_at_ms)."""
        rows = self.query("SELECT value, updated_at_ms FROM service_state WHERE key = ?", (key,))
        return (json.loads(rows[0][0]), rows[0][1]) if rows else (None, None)

    def set_state(self, key: str, value: dict) -> None:
        """Write a service_state entry."""
        with self.transaction() as connection:
            connection.execute(
                "INSERT INTO service_state VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value, "
                "updated_at_ms = excluded.updated_at_ms",
                (key, json.dumps(value), now_ms()),
            )


class _Transaction:
    """BEGIN IMMEDIATE ... COMMIT, rolling back on exceptions."""

    def __init__(self, connection: sqlite3.Connection):
        self.connection = connection

    def __enter__(self) -> sqlite3.Connection:
        self.connection.execute("BEGIN IMMEDIATE")
        return self.connection

    def __exit__(self, kind, value, traceback) -> bool:
        self.connection.execute("ROLLBACK" if kind else "COMMIT")
        return False
