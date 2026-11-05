"""Publication: atomic JSON/ZIP pairs, coalescing, ordering, retries and restart recovery."""

import hashlib
import json

from starlette.testclient import TestClient

from server.api import create_app
from server.hub import HubError
from server.store import Store
from server.tests.conftest import (
    FakeHub,
    make_zip,
    register,
    report_document,
    upload,
)
from server.worker import Publisher

T0 = 1793850000000


def checkpoint(answers: int, run_id: str = "bench120-1", queue_status: str = "running") -> bytes:
    return make_zip(report_document(run_id=run_id, answers={"gemma-mixed48-gpu-replay": answers}, queue_status=queue_status))


def drain(publisher: Publisher, store: Store, rounds: int = 10) -> None:
    """Run worker rounds, ignoring retry backoff."""
    for _ in range(rounds):
        store.connection().execute("UPDATE sessions SET next_attempt_at_ms = 0")
        publisher.tick()


def assert_pair(files: dict, json_path: str, zip_path: str) -> dict:
    """Check that a progress JSON names and hashes the ZIP committed with it."""
    progress = json.loads(files[json_path])
    assert progress["archivePath"] == zip_path
    assert progress["archiveSha256"] == hashlib.sha256(files[zip_path]).hexdigest()
    return progress


def setup(settings, store):
    client = TestClient(create_app(settings, store))
    hub = FakeHub()
    return client, hub, Publisher(settings, store, hub)


def test_publishes_hash_matched_pair_in_one_commit(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    data = checkpoint(5)
    accepted = upload(client, session, secret, data, created_at_ms=T0).json()
    drain(publisher, store)
    assert len(hub.commits) == 1
    commit = hub.commits[0]
    run = accepted["cloudRunId"]
    assert set(commit["files"]) == {"README.md", "latest.json", f"runs/{run}/progress.json", f"runs/{run}/latest.zip"}
    assert commit["files"][f"runs/{run}/latest.zip"] == data
    progress = assert_pair(commit["files"], "latest.json", f"runs/{run}/latest.zip")
    assert progress["cloudRunId"] == run and progress["completedAnswers"] == 5 and progress["updatedAtMs"] == T0
    assert progress["batch"] == "Batch 1 · Previously tested GPUs" and progress["smokeTest"] is True
    assert progress["relay"]["clientReported"] is True
    assert b"SYNTHETIC TEST SESSION" in commit["files"]["README.md"]
    status = client.get(f"/v1/sessions/{session['sessionId']}", headers={"Authorization": f"Bearer {secret}"}).json()
    assert status["sync"]["state"] == "synced" and status["repositoryCreated"] is True
    detail = client.get(accepted["statusUrl"], headers={"Authorization": f"Bearer {secret}"}).json()
    assert detail["state"] == "published" and detail["commit"] == commit["oid"]
    # Spool released after publication.
    assert not [p for p in settings.spool_dir.rglob("*.zip")]


def test_coalesces_routine_checkpoints(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    ids = [upload(client, session, secret, checkpoint(n), created_at_ms=T0 + n).json()["uploadId"] for n in (1, 2, 3)]
    drain(publisher, store)
    assert len(hub.commits) == 1 and json.loads(hub.commits[0]["files"]["latest.json"])["completedAnswers"] == 3
    states = {i: store.upload(session["sessionId"], i)["state"] for i in ids}
    assert states == {ids[0]: "superseded", ids[1]: "superseded", ids[2]: "published"}
    assert not [p for p in settings.spool_dir.rglob("*.zip")]


def test_late_older_snapshot_does_not_overwrite_newer(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(10), created_at_ms=T0 + 10)
    drain(publisher, store)
    late = upload(client, session, secret, checkpoint(9), created_at_ms=T0 + 9).json()
    drain(publisher, store)
    assert len(hub.commits) == 1
    assert store.upload(session["sessionId"], late["uploadId"])["state"] == "superseded"
    repo = hub.repos[session["repository"]]
    assert json.loads(repo["latest.json"])["completedAnswers"] == 10


def test_configuration_snapshots_are_preserved(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    done = make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 120}))
    config = upload(client, session, secret, done, kind="configuration", configuration="gemma-mixed48-gpu-replay",
                    created_at_ms=T0 + 100).json()
    newer = make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 120, "lfm3-q4km-vulkan-replay": 4}))
    upload(client, session, secret, newer, created_at_ms=T0 + 200)
    drain(publisher, store)
    run = config["cloudRunId"]
    repo = hub.repos[session["repository"]]
    config_progress = assert_pair(repo, f"runs/{run}/configurations/gemma-mixed48-gpu-replay.json",
                                  f"runs/{run}/configurations/gemma-mixed48-gpu-replay.zip")
    assert config_progress["completedAnswers"] == 120
    latest = assert_pair(repo, f"runs/{run}/progress.json", f"runs/{run}/latest.zip")
    assert latest["completedAnswers"] == 124
    assert json.loads(repo["latest.json"]) == latest
    # Configuration snapshot first (also advancing latest), then the newer routine checkpoint.
    assert len(hub.commits) == 2


def test_late_configuration_snapshot_keeps_newer_latest(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(50), created_at_ms=T0 + 50)
    drain(publisher, store)
    config = upload(client, session, secret, checkpoint(40), kind="configuration",
                    configuration="gemma-mixed48-gpu-replay", created_at_ms=T0 + 40).json()
    drain(publisher, store)
    files = hub.commits[-1]["files"]
    run = config["cloudRunId"]
    assert set(files) == {f"runs/{run}/configurations/gemma-mixed48-gpu-replay.zip",
                          f"runs/{run}/configurations/gemma-mixed48-gpu-replay.json"}
    assert json.loads(hub.repos[session["repository"]]["latest.json"])["completedAnswers"] == 50


def test_latest_json_tracks_newest_run(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(3, run_id="run-b"), created_at_ms=T0 + 300)
    drain(publisher, store)
    older_run = upload(client, session, secret, checkpoint(80, run_id="run-a"), created_at_ms=T0 + 100).json()
    drain(publisher, store)
    repo = hub.repos[session["repository"]]
    assert json.loads(repo["latest.json"])["localRunId"] == "run-b"
    assert json.loads(repo[f"runs/{older_run['cloudRunId']}/progress.json"])["completedAnswers"] == 80


def test_hub_failure_retries_with_backoff(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(5))
    hub.fail_next(HubError("Hub HTTP 503", status=503), HubError("Hub transport error (ConnectError)"))
    publisher.tick()
    row = store.session(session["sessionId"])
    assert row["failures"] == 1 and row["next_attempt_at_ms"] > 0 and "503" in row["last_error"]
    # In backoff: another tick does nothing.
    publisher.tick()
    assert store.session(session["sessionId"])["failures"] == 1
    status = client.get(f"/v1/sessions/{session['sessionId']}", headers={"Authorization": f"Bearer {secret}"}).json()
    assert status["sync"]["state"] == "pending" and status["sync"]["lastError"]
    drain(publisher, store)
    row = store.session(session["sessionId"])
    assert row["failures"] == 0 and row["last_error"] is None and len(hub.commits) == 1


def test_credential_error_reported_in_readiness(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(5))
    hub.fail_next(HubError("Hub HTTP 401", status=401, credential=True))
    publisher.tick()
    assert client.get("/v1/ready").json()["checks"]["hubCredential"] is False
    drain(publisher, store)
    assert client.get("/v1/ready").json()["checks"]["hubCredential"] is True


def test_queue_recovery_after_restart(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(7), created_at_ms=T0)
    hub.fail_next(HubError("Hub HTTP 500", status=500))
    publisher.tick()
    assert hub.commits == []
    # New process: fresh database connections and publisher, same files on disk.
    restarted_store = Store(settings.database_path)
    restarted = Publisher(settings, restarted_store, hub)
    drain(restarted, restarted_store)
    assert len(hub.commits) == 1 and json.loads(hub.commits[0]["files"]["latest.json"])["completedAnswers"] == 7


def test_terminal_checkpoint_bypasses_pacing(settings, store):
    import dataclasses
    paced = dataclasses.replace(settings, min_latest_interval_s=3600)
    client = TestClient(create_app(paced, store))
    hub = FakeHub()
    publisher = Publisher(paced, store, hub)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(1), created_at_ms=T0 + 1)
    drain(publisher, store)
    upload(client, session, secret, checkpoint(2), created_at_ms=T0 + 2)
    drain(publisher, store)
    assert len(hub.commits) == 1  # paced
    upload(client, session, secret, checkpoint(3, queue_status="stopped"), created_at_ms=T0 + 3)
    drain(publisher, store)
    assert len(hub.commits) == 2 and json.loads(hub.commits[1]["files"]["latest.json"])["completedAnswers"] == 3


def test_concurrent_sessions_publish_to_separate_repositories(settings, store):
    client, hub, publisher = setup(settings, store)
    sessions = [register(client, batch=b) for b in (1, 2, 3, 4)]
    for index, (session, secret) in enumerate(sessions):
        upload(client, session, secret, checkpoint(index + 1, run_id=f"run-{index}"))
    drain(publisher, store)
    assert len(hub.repos) == 4
    for index, (session, _) in enumerate(sessions):
        assert json.loads(hub.repos[session["repository"]]["latest.json"])["completedAnswers"] == index + 1


def test_index_publication_has_no_secrets(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client)
    upload(client, session, secret, checkpoint(5))
    drain(publisher, store)
    publisher.publish_index()
    files = hub.repos["vinyteles/mobilebench-index"]
    sessions = json.loads(files["sessions.json"])
    assert sessions[0]["repository"] == session["repository"]
    assert secret.encode() not in files["sessions.json"]
    assert json.loads(files["endpoint.json"])["url"] is None
    count = len(hub.commits)
    publisher.publish_index()
    assert len(hub.commits) == count  # unchanged index is not recommitted


def test_hub_refuses_foreign_destinations():
    hub = FakeHub()
    for repository in ("someone/mobilebench-x", "vinyteles/other-repo", "vinyteles/mobilebench-a/b"):
        try:
            hub.ensure_repo(repository)
        except HubError:
            continue
        raise AssertionError(repository)
