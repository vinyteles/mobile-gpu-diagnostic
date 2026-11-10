"""API behavior: registration idempotency, isolation, intake validation, limits and the public feed."""

import asyncio
import hashlib
import json
import os
import secrets
import uuid

import httpx
from starlette.testclient import TestClient

from server.api import create_app
from server.catalog import BATCH_NAMES
from server.store import Store
from server.tests.conftest import (
    make_settings,
    make_zip,
    register,
    report_document,
    upload,
)


def test_health_and_ready(client, store):
    assert client.get("/v1/health").json()["service"] == "mobilebench-upload-relay"
    response = client.get("/v1/ready")
    assert response.status_code == 503 and response.json()["checks"]["workerAlive"] is False
    store.set_state("worker", {"heartbeatAtMs": 10**13, "credentialError": None})
    assert client.get("/v1/ready").status_code == 200
    store.set_state("worker", {"heartbeatAtMs": 10**13, "credentialError": "Hub HTTP 401"})
    assert client.get("/v1/ready").json()["checks"]["hubCredential"] is False


def test_service_instance_id_persists_across_restart(settings, store):
    first = TestClient(create_app(settings, store)).get("/v1/health").json()["serviceInstanceId"]
    assert first.startswith("mbr_") and len(first) == 36
    restarted = TestClient(create_app(settings, Store(settings.database_path)))
    assert restarted.get("/v1/health").json()["serviceInstanceId"] == first


def test_registration_is_idempotent_and_server_owned(client):
    installation = str(uuid.uuid4())
    first, secret = register(client, batch=2, purpose="benchmark", installation=installation)
    assert first["created"] is True
    assert first["repository"].startswith("vinyteles/mobilebench-relay-") and "-b2-" in first["repository"]
    again, _ = register(client, batch=2, purpose="benchmark", secret=secret, installation=installation)
    assert again["created"] is False and again["sessionId"] == first["sessionId"]
    assert again["repository"] == first["repository"]
    assert secret not in json.dumps(first)
    # Same secret for different parameters is a conflict, not a second repository.
    body = {"sessionSecret": secret, "installationId": installation, "batch": 3, "appVersion": "2.2"}
    assert client.post("/v1/sessions", json=body).status_code == 409


def test_same_installation_can_hold_several_sessions(client):
    installation = str(uuid.uuid4())
    one, _ = register(client, batch=1, installation=installation)
    two, _ = register(client, batch=1, installation=installation)
    assert one["sessionId"] != two["sessionId"] and one["repository"] != two["repository"]


def test_single_configuration_profiles_accept_uploads_and_preserve_legacy_sessions(client):
    installation = str(uuid.uuid4())
    legacy, _ = register(client, batch=1, installation=installation)
    data = make_zip()
    sessions = []
    for profile in range(101, 110):
        session, secret = register(client, batch=profile, installation=installation)
        assert session["batchName"] == BATCH_NAMES[profile]
        assert session["sessionId"] != legacy["sessionId"]
        assert upload(client, session, secret, data).status_code == 202
        sessions.append(session["sessionId"])
    assert len(set(sessions)) == 9
    assert legacy["batchName"] == "Batch 1 · Previously tested GPUs"


def test_registration_rejects_weak_or_malformed_input(client):
    good = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.2"}
    for field, value in [("sessionSecret", "short"), ("installationId", "not-a-uuid"), ("batch", 9), ("batch", True), ("batch", 101.0), ("batch", 124),
                         ("appVersion", "x" * 50), ("purpose", "other")]:
        response = client.post("/v1/sessions", json=dict(good, **{field: value}))
        assert response.status_code == 400, field
    assert client.post("/v1/sessions", content=b"[]").status_code == 400
    assert client.post("/v1/sessions", content=b"x" * 20000).status_code == 413


def test_registration_rate_limits(tmp_path):
    settings = make_settings(tmp_path, registrations_per_ip_per_hour=2)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    register(client)
    register(client)
    body = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.2"}
    response = client.post("/v1/sessions", json=body)
    assert response.status_code == 429 and int(response.headers["Retry-After"]) > 0
    assert response.json()["error"]["retryable"] is True


def test_global_daily_registration_quota(tmp_path):
    settings = make_settings(tmp_path, registrations_per_day=1)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    register(client)
    body = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.2"}
    assert client.post("/v1/sessions", json=body).json()["error"]["code"] == "registration_quota"


def test_forwarded_address_trusted_only_from_configured_proxy(tmp_path):
    settings = make_settings(tmp_path, registrations_per_ip_per_hour=1)
    # TestClient's peer is "testclient", which is not a trusted proxy: rotating the header must not bypass limits.
    client = TestClient(create_app(settings, Store(settings.database_path)))
    for index in range(2):
        body = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.2"}
        response = client.post("/v1/sessions", json=body, headers={"CF-Connecting-IP": f"203.0.113.{index}"})
    assert response.status_code == 429


def test_forwarded_address_used_from_trusted_proxy(tmp_path):
    settings = make_settings(tmp_path, registrations_per_ip_per_hour=1)
    client = TestClient(create_app(settings, Store(settings.database_path)), client=("127.0.0.1", 5000))
    for index in range(2):
        body = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1, "appVersion": "2.2"}
        response = client.post("/v1/sessions", json=body, headers={"CF-Connecting-IP": f"203.0.113.{index}"})
        assert response.status_code == 201


def test_upload_accept_and_duplicate(client):
    session, secret = register(client)
    data = make_zip()
    response = upload(client, session, secret, data, created_at_ms=1793850219685)
    assert response.status_code == 202, response.text
    accepted = response.json()
    assert accepted["state"] == "stored" and accepted["sha256"] == hashlib.sha256(data).hexdigest()
    assert accepted["cloudRunId"].startswith("bench120-1-")
    again = upload(client, session, secret, data, created_at_ms=1793850219685)
    assert again.status_code == 200 and again.json()["duplicate"] is True
    assert again.json()["uploadId"] == accepted["uploadId"]
    status = client.get(f"/v1/sessions/{session['sessionId']}", headers={"Authorization": f"Bearer {secret}"}).json()
    assert status["sync"]["state"] == "collecting" and status["sync"]["pendingUploads"] == 0
    assert status["sync"]["storedUploads"] == 1 and status["latestAccepted"]["savedAnswers"] == 3
    detail = client.get(accepted["statusUrl"], headers={"Authorization": f"Bearer {secret}"})
    assert detail.json()["state"] == "stored"


def test_upload_reports_saved_progress_and_eligibility(client):
    session, secret = register(client, batch=104)
    assert session["assignedConfigurations"] == ["gemma-base-mixed248-gpu-replay"]
    partial = upload(client, session, secret, make_zip(report_document(answers={"gemma-base-mixed248-gpu-replay": 42})))
    body = partial.json()
    assert partial.status_code == 202 and body["state"] == "stored"
    assert (body["savedAnswers"], body["requiredAnswers"], body["complete"]) == (42, 120, False)
    # A client-side "completed" status or queue state does not make a report complete.
    report = report_document(answers={"gemma-base-mixed248-gpu-replay": 119}, queue_status="completed")
    report["results"][0]["status"] = "completed"
    body = upload(client, session, secret, make_zip(report)).json()
    assert body["state"] == "stored" and body["savedAnswers"] == 119
    complete = report_document(answers={"gemma-base-mixed248-gpu-replay": 120})
    body = upload(client, session, secret, make_zip(complete)).json()
    assert body["state"] == "accepted" and body["complete"] is True
    status = client.get(session["statusUrl"], headers={"Authorization": f"Bearer {secret}"}).json()
    assert status["sync"]["state"] == "pending" and status["sync"]["pendingUploads"] == 1


def test_session_isolation(client):
    alice, alice_secret = register(client)
    bob, bob_secret = register(client)
    data = make_zip()
    assert upload(client, alice, bob_secret, data).status_code == 401
    assert upload(client, alice, "", data).status_code == 401
    assert client.get(f"/v1/sessions/{alice['sessionId']}", headers={"Authorization": f"Bearer {bob_secret}"}).status_code == 401
    assert client.get(f"/v1/sessions/{alice['sessionId']}").status_code == 401
    unknown = dict(alice, sessionId="s_" + "0" * 16)
    assert upload(client, unknown, alice_secret, data).status_code == 404
    accepted = upload(client, alice, alice_secret, data).json()
    path = accepted["statusUrl"].replace(alice["sessionId"], bob["sessionId"])
    assert client.get(path, headers={"Authorization": f"Bearer {bob_secret}"}).status_code == 404


def test_upload_rejections(tmp_path):
    settings = make_settings(tmp_path, max_upload_bytes=300_000)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    session, secret = register(client)
    data = make_zip()
    assert upload(client, session, secret, data, sha="0" * 64).json()["error"]["code"] == "checksum_mismatch"
    assert upload(client, session, secret, b"junk").status_code == 422
    big = make_zip(extra={"big.log": os.urandom(200_000)})
    response = upload(client, session, secret, big)
    assert response.status_code == 413
    bad_type = client.post(f"/v1/sessions/{session['sessionId']}/checkpoints", content=data,
                           headers={"Authorization": f"Bearer {secret}", "Content-Type": "text/plain",
                                    "X-Content-SHA256": hashlib.sha256(data).hexdigest()})
    assert bad_type.status_code == 415
    assert upload(client, session, secret, data, kind="configuration").status_code == 400
    assert upload(client, session, secret, data, kind="configuration", configuration="../../x").status_code == 400
    response = upload(client, session, secret, data, kind="configuration", configuration="gemma-int8-cpu-replay")
    assert response.json()["error"]["code"] == "configuration_not_in_report"
    # Nothing rejected stays in the spool.
    assert not [p for p in settings.spool_dir.rglob("*") if p.is_file()]


def test_session_upload_rate_limit(tmp_path):
    settings = make_settings(tmp_path, uploads_per_session_per_hour=2)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    session, secret = register(client)
    for answers in (1, 2):
        assert upload(client, session, secret, make_zip(report_document(answers={"gemma-mixed48-gpu-replay": answers}))).status_code == 202
    response = upload(client, session, secret, make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 3})))
    assert response.status_code == 429 and "Retry-After" in response.headers


def test_pending_spool_quota(tmp_path):
    settings = make_settings(tmp_path, max_pending_bytes_per_session=400_000, max_upload_bytes=300_000)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    session, secret = register(client)
    first = make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 1}))
    assert len(first) < 300_000
    assert upload(client, session, secret, first).status_code == 202
    response = upload(client, session, secret, make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 2})))
    assert response.status_code == 507 and response.json()["error"]["code"] == "session_spool_full"


def test_run_quota(tmp_path):
    settings = make_settings(tmp_path, max_runs_per_session=1)
    client = TestClient(create_app(settings, Store(settings.database_path)))
    session, secret = register(client)
    assert upload(client, session, secret, make_zip(report_document(run_id="run-a"))).status_code == 202
    assert upload(client, session, secret, make_zip(report_document(run_id="run-b"))).status_code == 409


def test_public_feed_has_no_capabilities(client):
    installation = str(uuid.uuid4())
    session, secret = register(client, installation=installation)
    upload(client, session, secret, make_zip())
    feed = client.get("/v1/public/sessions")
    text = feed.text
    assert secret not in text and installation not in text and "secret" not in text.lower()
    entry = feed.json()["sessions"][0]
    assert entry["repositoryUrl"].startswith("https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-")
    assert entry["latest"]["clientReported"] is True and entry["latest"]["smokeTest"] is True
    page = client.get("/")
    assert page.status_code == 200 and session["repository"] in page.text and secret not in page.text


def test_concurrent_independent_sessions(settings, store):
    """Six participants register and upload concurrently in ONE event loop, as in production."""
    app = create_app(settings, store)

    async def participant(client: httpx.AsyncClient, index: int) -> list[int]:
        body = {"sessionSecret": secrets.token_urlsafe(32), "installationId": str(uuid.uuid4()), "batch": 1 + index % 5,
                "appVersion": "2.2", "purpose": "synthetic-test"}
        session = (await client.post("/v1/sessions", json=body)).json()
        codes = []
        for answers in range(1, 4):
            data = make_zip(report_document(run_id=f"run-{index}", answers={"gemma-mixed48-gpu-replay": answers}))
            headers = {"Authorization": f"Bearer {body['sessionSecret']}", "Content-Type": "application/zip",
                       "X-Content-SHA256": hashlib.sha256(data).hexdigest()}
            response = await client.post(session["checkpointsUrl"], content=data, headers=headers)
            codes.append(response.status_code)
        return codes

    async def run_all() -> list[list[int]]:
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://relay") as client:
            return await asyncio.gather(*(participant(client, index) for index in range(6)))

    codes = [code for codes in asyncio.run(run_all()) for code in codes]
    assert codes == [202] * 18
    assert store.count_sessions() == 6
    repositories = {row["repository"] for row in store.all_sessions(10)}
    assert len(repositories) == 6


def test_default_limits_allow_fast_per_question_uploads_with_status_polling(tmp_path):
    """A fast synthetic run: one cumulative upload per question plus stop/final snapshots, polled throughout."""
    settings = make_settings(tmp_path)
    client = TestClient(create_app(settings, Store(settings.database_path)), client=("127.0.0.1", 5000))
    session, secret = register(client, batch=101)
    headers = {"Authorization": f"Bearer {secret}", "CF-Connecting-IP": "203.0.113.9"}
    for answers in range(1, 121):
        data = make_zip(report_document(answers={"gemma-mixed48-gpu-replay": answers}))
        assert upload(client, session, secret, data, created_at_ms=1793850000000 + answers).status_code == 202
        for _ in range(3):
            assert client.get(session["statusUrl"], headers=headers).status_code == 200
    final = make_zip(report_document(answers={"gemma-mixed48-gpu-replay": 120}, queue_status="completed"))
    assert upload(client, session, secret, final, created_at_ms=1793850000200).status_code == 202
    status = client.get(session["statusUrl"], headers=headers).json()
    assert (status["sync"]["receivedAnswers"], status["sync"]["expectedAnswers"]) == (120, 120)
    assert status["sync"]["state"] == "pending"
