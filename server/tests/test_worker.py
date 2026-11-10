"""Publication: the 120/120 completion gate, durable partials, coalescing, ordering, retries and restarts."""

import dataclasses
import hashlib
import json
import os
import time

from starlette.testclient import TestClient

from server.api import create_app
from server.catalog import (
    BATCH_CONFIGURATIONS,
    CONFIGURATION_MODELS,
)
from server.hub import HubError
from server.store import Store
from server.synthetic import PLACEHOLDER_ANSWER
from server.tests.conftest import (
    FakeHub,
    make_zip,
    register,
    report_document,
    upload,
)
from server.worker import Publisher

T0 = 1793850000000
CONFIG = "gemma-mixed48-gpu-replay"


def checkpoint(answers, run_id: str = "bench120-1", queue_status: str = "running", configuration: str = CONFIG,
               status: str | None = None) -> bytes:
    """Return a cumulative snapshot ZIP with `answers` completed questions (count or positions)."""
    report = report_document(run_id=run_id, answers={configuration: answers}, queue_status=queue_status)
    if status:
        report["results"][0]["status"] = status
    return make_zip(report)


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


def status_of(client: TestClient, session: dict, secret: str) -> dict:
    return client.get(f"/v1/sessions/{session['sessionId']}", headers={"Authorization": f"Bearer {secret}"}).json()


def state_of(store: Store, session: dict, upload_id: str) -> str:
    return store.upload(session["sessionId"], upload_id)["state"]


def session_commits(hub: FakeHub, session: dict) -> list[dict]:
    return [commit for commit in hub.commits if commit["repository"] == session["repository"]]


def spooled(settings) -> list:
    return [path for path in settings.spool_dir.rglob("*.zip")]


# Completion gate -------------------------------------------------------------------------------


def test_complete_report_publishes_hash_matched_pair_in_one_commit(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    data = checkpoint(120, queue_status="completed")
    accepted = upload(client, session, secret, data, created_at_ms=T0).json()
    assert accepted["state"] == "accepted" and accepted["complete"] is True
    assert (accepted["savedAnswers"], accepted["requiredAnswers"]) == (120, 120)
    drain(publisher, store)
    assert len(hub.commits) == 1
    commit = hub.commits[0]
    run = accepted["cloudRunId"]
    assert set(commit["files"]) == {"README.md", "latest.json", f"runs/{run}/progress.json", f"runs/{run}/latest.zip"}
    assert commit["files"][f"runs/{run}/latest.zip"] == data
    progress = assert_pair(commit["files"], "latest.json", f"runs/{run}/latest.zip")
    assert progress["cloudRunId"] == run and progress["completedAnswers"] == 120 and progress["updatedAtMs"] == T0
    assert progress["batch"] == "Config 1 · Energy mixed 4/8-bit GPU" and progress["smokeTest"] is True
    assert progress["relay"]["clientReported"] is True
    assert b"SYNTHETIC TEST SESSION" in commit["files"]["README.md"]
    assert "complete (120/120 answers)" in commit["message"]
    status = status_of(client, session, secret)
    assert status["sync"]["state"] == "synced" and status["repositoryCreated"] is True
    detail = client.get(accepted["statusUrl"], headers={"Authorization": f"Bearer {secret}"}).json()
    assert detail["state"] == "published" and detail["commit"] == commit["oid"]
    assert not spooled(settings)


def test_partial_reports_stay_on_relay_including_stop_error_and_configuration_snapshots(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    sent = [
        upload(client, session, secret, checkpoint(1), created_at_ms=T0 + 1),
        upload(client, session, secret, checkpoint(42), created_at_ms=T0 + 42),
        upload(client, session, secret, checkpoint(119), created_at_ms=T0 + 119),
        upload(client, session, secret, checkpoint(119, queue_status="stopped"), created_at_ms=T0 + 120),
        upload(client, session, secret, checkpoint(119, queue_status="error", status="error"), created_at_ms=T0 + 121),
        upload(client, session, secret, checkpoint(119, queue_status="completed", status="skipped"), kind="configuration",
               configuration=CONFIG, created_at_ms=T0 + 122),
    ]
    for response in sent:
        assert response.status_code == 202 and response.json()["state"] == "stored"
        assert response.json()["complete"] is False
    drain(publisher, store)
    publisher.publish_index()

    # Nothing about this session's answers went to HF; only the index (progress metadata) was committed.
    assert session_commits(hub, session) == [] and session["repository"] not in hub.repos
    for commit in hub.commits:
        assert all(PLACEHOLDER_ANSWER.encode() not in content for content in commit["files"].values())
    index = json.loads(hub.repos["vinyteles/mobilebench-index-v2"]["sessions.json"])
    assert index[0]["latest"]["savedAnswers"] == 119 and index[0]["sync"]["state"] == "collecting"

    # The relay still holds the most complete partial durably and reports it as collecting.
    status = status_of(client, session, secret)
    assert status["sync"]["state"] == "collecting" and status["sync"]["pendingUploads"] == 0
    assert status["sync"]["storedUploads"] >= 1 and status["repositoryCreated"] is False
    assert (status["latestAccepted"]["savedAnswers"], status["latestAccepted"]["requiredAnswers"]) == (119, 120)
    assert (status["sync"]["receivedAnswers"], status["sync"]["expectedAnswers"]) == (119, 120)
    assert status["assignedConfigurations"] == [CONFIG]
    assert spooled(settings)


def test_partial_progress_survives_restart_and_maintenance_then_publishes(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    stored = upload(client, session, secret, checkpoint(42), created_at_ms=T0 + 42).json()
    drain(publisher, store)
    blob = store.upload(session["sessionId"], stored["uploadId"])["blob_path"]

    # Fresh API/worker processes over the same files; janitor runs with the blob looking hours old.
    old = time.time() - 6 * 3600
    os.utime(blob, (old, old))
    restarted_store = Store(settings.database_path)
    restarted_client = TestClient(create_app(settings, restarted_store))
    restarted = Publisher(settings, restarted_store, hub)
    restarted.clean_spool()
    drain(restarted, restarted_store)
    assert os.path.exists(blob) and hub.commits == []
    status = status_of(restarted_client, session, secret)
    assert status["latestAccepted"]["savedAnswers"] == 42 and status["sync"]["state"] == "collecting"

    upload(restarted_client, session, secret, checkpoint(120), created_at_ms=T0 + 120)
    drain(restarted, restarted_store)
    assert len(hub.commits) == 1 and json.loads(hub.commits[0]["files"]["latest.json"])["completedAnswers"] == 120
    assert state_of(restarted_store, session, stored["uploadId"]) == "superseded"
    assert status_of(restarted_client, session, secret)["sync"]["state"] == "synced"
    assert not spooled(settings)


def test_partials_coalesce_to_the_most_complete_snapshot(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    ids = [upload(client, session, secret, checkpoint(n), created_at_ms=T0 + n).json()["uploadId"] for n in range(1, 6)]
    drain(publisher, store)
    states = [state_of(store, session, upload_id) for upload_id in ids]
    assert states == ["superseded"] * 4 + ["stored"]
    assert len(spooled(settings)) == 1 and hub.commits == []


def test_out_of_order_and_duplicates_never_lose_or_regress_completed_questions(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    forty_two = checkpoint(42)
    first = upload(client, session, secret, forty_two, created_at_ms=T0 + 42).json()
    late = upload(client, session, secret, checkpoint(41), created_at_ms=T0 + 41).json()
    duplicate = upload(client, session, secret, forty_two, created_at_ms=T0 + 42)
    assert duplicate.status_code == 200 and duplicate.json()["uploadId"] == first["uploadId"]
    # Newer snapshot that lacks question 0: the older one keeps the only copy of that answer.
    gap = upload(client, session, secret, checkpoint(range(1, 46)), created_at_ms=T0 + 50).json()
    drain(publisher, store)
    assert state_of(store, session, late["uploadId"]) == "superseded"
    assert state_of(store, session, first["uploadId"]) == "stored"
    assert state_of(store, session, gap["uploadId"]) == "stored"
    assert status_of(client, session, secret)["latestAccepted"]["savedAnswers"] == 45
    assert status_of(client, session, secret)["sync"]["receivedAnswers"] == 45

    complete = upload(client, session, secret, checkpoint(120), created_at_ms=T0 + 120).json()
    drain(publisher, store)
    assert [state_of(store, session, u["uploadId"]) for u in (first, gap, complete)] == ["superseded", "superseded",
                                                                                          "published"]
    # A stale 119 snapshot delivered after publication changes nothing on HF.
    stale = upload(client, session, secret, checkpoint(119), created_at_ms=T0 + 119).json()
    drain(publisher, store)
    assert state_of(store, session, stale["uploadId"]) == "superseded" and len(hub.commits) == 1
    assert json.loads(hub.repos[session["repository"]]["latest.json"])["completedAnswers"] == 120
    assert status_of(client, session, secret)["latestAccepted"]["savedAnswers"] == 120


def test_every_single_configuration_profile_publishes_only_at_its_own_120(settings, store):
    client, hub, publisher = setup(settings, store)
    for profile in range(101, 110):
        session, secret = register(client, batch=profile)
        assigned = BATCH_CONFIGURATIONS[profile][0]
        others = {configuration: 120 for configuration in CONFIGURATION_MODELS if configuration != assigned and configuration.endswith("-replay")}
        almost = report_document(run_id=f"run-{profile}", answers={**others, assigned: 119})
        upload(client, session, secret, make_zip(almost), created_at_ms=T0 + 1)
        drain(publisher, store)
        assert session["repository"] not in hub.repos, profile
        done = report_document(run_id=f"run-{profile}", answers={**others, assigned: 120})
        upload(client, session, secret, make_zip(done), created_at_ms=T0 + 2)
        drain(publisher, store)
        latest = json.loads(hub.repos[session["repository"]]["latest.json"])
        assert latest["batch"] == session["batchName"] and status_of(client, session, secret)["sync"]["state"] == "synced"


def test_legacy_group_requires_every_assigned_configuration(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=1)
    first, second = BATCH_CONFIGURATIONS[1]
    one_done = make_zip(report_document(answers={first: 120, second: 0}, queue_status="running"))
    config = upload(client, session, secret, one_done, kind="configuration", configuration=first, created_at_ms=T0 + 1)
    assert config.json()["state"] == "stored" and config.json()["requiredAnswers"] == 240
    stopped = make_zip(report_document(answers={first: 120, second: 119}, queue_status="stopped"))
    upload(client, session, secret, stopped, created_at_ms=T0 + 2)
    drain(publisher, store)
    assert session_commits(hub, session) == []
    status = status_of(client, session, secret)
    assert status["latestAccepted"]["savedAnswers"] == 239 and status["sync"]["state"] == "collecting"
    assert (status["sync"]["receivedAnswers"], status["sync"]["expectedAnswers"]) == (239, 240)

    both = make_zip(report_document(answers={first: 120, second: 120}, queue_status="completed"))
    upload(client, session, secret, both, created_at_ms=T0 + 3)
    drain(publisher, store)
    files = hub.repos[session["repository"]]
    assert json.loads(files["latest.json"])["completedAnswers"] == 240
    assert not [path for path in files if "/configurations/" in path]
    assert state_of(store, session, config.json()["uploadId"]) == "superseded"


# Ordering of complete snapshots ----------------------------------------------------------------


def test_coalesces_complete_routine_checkpoints(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    statuses = ("running", "stopped", "completed")
    ids = [upload(client, session, secret, checkpoint(120, queue_status=status), created_at_ms=T0 + n).json()["uploadId"]
           for n, status in enumerate(statuses)]
    drain(publisher, store)
    assert len(hub.commits) == 1 and json.loads(hub.commits[0]["files"]["latest.json"])["queue"]["status"] == "completed"
    assert [state_of(store, session, i) for i in ids] == ["superseded", "superseded", "published"]
    assert not spooled(settings)


def test_late_older_complete_snapshot_does_not_overwrite_newer(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120, queue_status="completed"), created_at_ms=T0 + 10)
    drain(publisher, store)
    late = upload(client, session, secret, checkpoint(120), created_at_ms=T0 + 9).json()
    drain(publisher, store)
    assert len(hub.commits) == 1 and state_of(store, session, late["uploadId"]) == "superseded"
    assert json.loads(hub.repos[session["repository"]]["latest.json"])["queue"]["status"] == "completed"


def test_configuration_snapshots_are_preserved(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    done = make_zip(report_document(answers={CONFIG: 120}))
    config = upload(client, session, secret, done, kind="configuration", configuration=CONFIG,
                    created_at_ms=T0 + 100).json()
    newer = make_zip(report_document(answers={CONFIG: 120, "lfm3-q4km-vulkan-replay": 4}))
    upload(client, session, secret, newer, created_at_ms=T0 + 200)
    drain(publisher, store)
    run = config["cloudRunId"]
    repo = hub.repos[session["repository"]]
    config_progress = assert_pair(repo, f"runs/{run}/configurations/{CONFIG}.json",
                                  f"runs/{run}/configurations/{CONFIG}.zip")
    assert config_progress["completedAnswers"] == 120
    latest = assert_pair(repo, f"runs/{run}/progress.json", f"runs/{run}/latest.zip")
    assert latest["completedAnswers"] == 124
    assert json.loads(repo["latest.json"]) == latest
    # Configuration snapshot first (also advancing latest), then the newer routine checkpoint.
    assert len(hub.commits) == 2


def test_late_configuration_snapshot_keeps_newer_latest(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120, queue_status="completed"), created_at_ms=T0 + 50)
    drain(publisher, store)
    config = upload(client, session, secret, checkpoint(120), kind="configuration", configuration=CONFIG,
                    created_at_ms=T0 + 40).json()
    drain(publisher, store)
    files = hub.commits[-1]["files"]
    run = config["cloudRunId"]
    assert set(files) == {f"runs/{run}/configurations/{CONFIG}.zip", f"runs/{run}/configurations/{CONFIG}.json"}
    assert json.loads(hub.repos[session["repository"]]["latest.json"])["queue"]["status"] == "completed"


def test_latest_json_tracks_newest_run(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120, run_id="run-b"), created_at_ms=T0 + 300)
    drain(publisher, store)
    older_run = upload(client, session, secret, checkpoint(120, run_id="run-a", queue_status="completed"),
                       created_at_ms=T0 + 100).json()
    drain(publisher, store)
    repo = hub.repos[session["repository"]]
    assert json.loads(repo["latest.json"])["localRunId"] == "run-b"
    assert json.loads(repo[f"runs/{older_run['cloudRunId']}/progress.json"])["queue"]["status"] == "completed"


# Failures, restarts, pacing and the index ------------------------------------------------------


def test_hub_failure_retries_with_backoff(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120))
    hub.fail_next(HubError("Hub HTTP 503", status=503), HubError("Hub transport error (ConnectError)"))
    publisher.tick()
    row = store.session(session["sessionId"])
    assert row["failures"] == 1 and row["next_attempt_at_ms"] > 0 and "503" in row["last_error"]
    # In backoff: another tick does nothing.
    publisher.tick()
    assert store.session(session["sessionId"])["failures"] == 1
    status = status_of(client, session, secret)
    assert status["sync"]["state"] == "pending" and status["sync"]["lastError"]
    drain(publisher, store)
    row = store.session(session["sessionId"])
    assert row["failures"] == 0 and row["last_error"] is None and len(hub.commits) == 1


def test_credential_error_reported_in_readiness(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120))
    hub.fail_next(HubError("Hub HTTP 401", status=401, credential=True))
    publisher.tick()
    assert client.get("/v1/ready").json()["checks"]["hubCredential"] is False
    drain(publisher, store)
    assert client.get("/v1/ready").json()["checks"]["hubCredential"] is True


def test_queue_recovery_after_restart(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120), created_at_ms=T0)
    hub.fail_next(HubError("Hub HTTP 500", status=500))
    publisher.tick()
    assert hub.commits == []
    # New process: fresh database connections and publisher, same files on disk.
    restarted_store = Store(settings.database_path)
    restarted = Publisher(settings, restarted_store, hub)
    drain(restarted, restarted_store)
    assert len(hub.commits) == 1 and json.loads(hub.commits[0]["files"]["latest.json"])["completedAnswers"] == 120


def test_terminal_checkpoint_bypasses_pacing(settings, store):
    paced = dataclasses.replace(settings, min_latest_interval_s=3600)
    client = TestClient(create_app(paced, store))
    hub = FakeHub()
    publisher = Publisher(paced, store, hub)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120), created_at_ms=T0 + 1)
    drain(publisher, store)
    upload(client, session, secret, checkpoint(120, queue_status="running"), created_at_ms=T0 + 2)
    drain(publisher, store)
    assert len(hub.commits) == 1  # paced
    upload(client, session, secret, checkpoint(120, queue_status="completed"), created_at_ms=T0 + 3)
    drain(publisher, store)
    assert len(hub.commits) == 2
    assert json.loads(hub.commits[1]["files"]["latest.json"])["queue"]["status"] == "completed"


def test_concurrent_sessions_publish_to_separate_repositories(settings, store):
    client, hub, publisher = setup(settings, store)
    sessions = [register(client, batch=profile) for profile in (101, 102, 103, 104)]
    for index, (session, secret) in enumerate(sessions):
        assigned = BATCH_CONFIGURATIONS[session["batch"]][0]
        upload(client, session, secret, checkpoint(120, run_id=f"run-{index}", configuration=assigned))
    drain(publisher, store)
    assert len(hub.repos) == 4
    for index, (session, _) in enumerate(sessions):
        assert json.loads(hub.repos[session["repository"]]["latest.json"])["localRunId"] == f"run-{index}"


def test_index_publication_has_no_secrets(settings, store):
    client, hub, publisher = setup(settings, store)
    session, secret = register(client, batch=101)
    upload(client, session, secret, checkpoint(120))
    drain(publisher, store)
    publisher.publish_index()
    files = hub.repos["vinyteles/mobilebench-index-v2"]
    assert "vinyteles/mobilebench-index" not in hub.repos
    sessions = json.loads(files["sessions.json"])
    assert sessions[0]["repository"] == session["repository"] and sessions[0]["sync"]["state"] == "synced"
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
