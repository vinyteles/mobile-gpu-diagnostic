"""Shared fixtures: isolated settings, a fake Hub, and synthetic report ZIP builders."""

import dataclasses
import hashlib
import secrets
import uuid
from pathlib import Path

import pytest
from starlette.testclient import TestClient

from server.api import create_app
from server.config import (
    Limits,
    Settings,
)
from server.hub import (
    HubClient,
    HubError,
)
from server.store import Store
from server.synthetic import (
    FIXTURE_PATH,
    fixture_question_ids,
    synthetic_report,
    synthetic_zip,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE = FIXTURE_PATH.read_bytes()
QUESTION_IDS = fixture_question_ids(FIXTURE)
EXAMPLE_ZIP = Path.home() / ".local/state/mobile-upload-agent/examples/report.zip"


class FakeHub(HubClient):
    """In-memory Hub: records commits and can fail on demand."""

    def __init__(self, namespace: str = "vinyteles"):
        super().__init__(Path("/nonexistent"), namespace)
        self.repos = {}
        self.commits = []
        self.failures = []

    def fail_next(self, *errors: HubError) -> None:
        """Queue errors raised by the next Hub calls."""
        self.failures.extend(errors)

    def maybe_fail(self) -> None:
        if self.failures:
            raise self.failures.pop(0)

    def ensure_repo(self, repository: str) -> None:
        self.check_namespace(repository)
        self.maybe_fail()
        self.repos.setdefault(repository, {})

    def commit(self, repository: str, files: dict, message: str) -> str:
        self.check_namespace(repository)
        self.maybe_fail()
        assert repository in self.repos, "commit before repository creation"
        contents = {path: (value.read_bytes() if isinstance(value, Path) else value) for path, value in files.items()}
        self.repos[repository].update(contents)
        oid = hashlib.sha1(f"{len(self.commits)}{message}".encode()).hexdigest()
        self.commits.append({"repository": repository, "files": contents, "message": message, "oid": oid})
        return oid


def make_settings(tmp_path: Path, **limit_overrides) -> Settings:
    """Settings rooted in a temporary directory with immediate worker pacing."""
    data = tmp_path / "var"
    for directory in (data, data / "spool", tmp_path / "logs", tmp_path / "run"):
        directory.mkdir(parents=True, exist_ok=True)
    return Settings(
        data_dir=data,
        log_dir=tmp_path / "logs",
        endpoint_file=tmp_path / "run/endpoint.txt",
        token_file=tmp_path / "missing-token",
        min_latest_interval_s=0,
        retry_base_s=60,
        index_interval_s=10**9,
        limits=dataclasses.replace(Limits(min_free_disk_bytes=0), **limit_overrides),
    )


@pytest.fixture
def settings(tmp_path):
    return make_settings(tmp_path)


@pytest.fixture
def store(settings):
    return Store(settings.database_path)


@pytest.fixture
def client(settings, store):
    return TestClient(create_app(settings, store))


def report_document(run_id: str = "bench120-1", answers: dict | None = None, queue_status: str = "running",
                    smoke: bool = True, **metadata_overrides) -> dict:
    """Build a report.json document.

    Args:
        run_id: metadata.runId.
        answers: configuration id -> number of completed answers (first N fixture questions) or positions.
        queue_status: queue.status.
        smoke: metadata.smokeTest.
        metadata_overrides: Replace metadata fields (e.g. protocolId).
    Returns:
        report dict.
    """
    answers = {"gemma-mixed48-gpu-replay": 3} if answers is None else answers
    return synthetic_report(run_id, answers, QUESTION_IDS, queue_status, smoke, **metadata_overrides)


def make_zip(report: dict | None = None, extra: dict | None = None, fixture: bytes = FIXTURE,
             skip: tuple = ()) -> bytes:
    """Build a report ZIP like ReportExporter.archive() (default report: report_document())."""
    return synthetic_zip(report or report_document(), fixture, extra, skip)


def register(client: TestClient, batch: int = 1, purpose: str = "synthetic-test", secret: str | None = None,
             installation: str | None = None) -> tuple[dict, str]:
    """Register a session and return (response JSON, secret)."""
    secret = secret or secrets.token_urlsafe(32)
    body = {"sessionSecret": secret, "installationId": installation or str(uuid.uuid4()), "batch": batch,
            "appVersion": "2.2", "purpose": purpose}
    response = client.post("/v1/sessions", json=body)
    assert response.status_code in (200, 201), response.text
    return response.json(), secret


def upload(client: TestClient, session: dict, secret: str, data: bytes, kind: str = "latest",
           configuration: str | None = None, created_at_ms: int | None = None, sha: str | None = None):
    """POST a checkpoint and return the response."""
    headers = {
        "Authorization": f"Bearer {secret}",
        "Content-Type": "application/zip",
        "X-Content-SHA256": sha or hashlib.sha256(data).hexdigest(),
        "X-Checkpoint-Kind": kind,
    }
    if configuration:
        headers["X-Configuration-Id"] = configuration
    if created_at_ms:
        headers["X-Checkpoint-Created-At-Ms"] = str(created_at_ms)
    return client.post(f"/v1/sessions/{session['sessionId']}/checkpoints", content=data, headers=headers)
