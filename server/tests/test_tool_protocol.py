"""Live retrieval sessions remain isolated from historical frozen-context reports."""

import hashlib
import json

from server.catalog import (
    BATCH_CONFIGURATIONS,
    TOOL_FIXTURE_SHA256,
    TOOL_PROTOCOL_ID,
)
from server.tests.conftest import (
    FIXTURE,
    REPO_ROOT,
    make_zip,
    register,
    report_document,
    upload,
)
from server.tests.test_validation import rejected_code

TOOL_FIXTURE = (REPO_ROOT / "app/src/main/assets/tool-questions.json").read_bytes()


def test_live_selection_preserves_ids_references_and_excludes_saved_context():
    old = json.loads(FIXTURE)
    new = json.loads(TOOL_FIXTURE)
    assert hashlib.sha256(TOOL_FIXTURE).hexdigest() == TOOL_FIXTURE_SHA256
    assert old["selection"] == new["selection"]
    assert old["dataset"] == new["dataset"]
    for before, after in zip(old["questions"], new["questions"], strict=True):
        assert before["id"] == after["id"] and before["reference"] == after["reference"]
        assert after["prompt"] == after["question"] and after["sources"] == []


def test_all_six_live_sessions_require_120_answers(client):
    for profile in range(110, 116):
        session, secret = register(client, batch=profile)
        configuration = BATCH_CONFIGURATIONS[profile][0]
        for count, state in ((119, "stored"), (120, "accepted")):
            report = report_document(answers={configuration: count}, protocolId=TOOL_PROTOCOL_ID,
                                     fixtureSha256=TOOL_FIXTURE_SHA256)
            receipt = upload(client, session, secret, make_zip(report, fixture=TOOL_FIXTURE),
                             created_at_ms=1_700_000_000_000 + count)
            assert receipt.status_code == 202, receipt.text
            assert receipt.json()["state"] == state
            assert receipt.json()["requiredAnswers"] == 120


def test_live_and_replay_configurations_cannot_share_a_fixture(tmp_path):
    live = BATCH_CONFIGURATIONS[110][0]
    replay = BATCH_CONFIGURATIONS[102][0]
    wrong_old = report_document(answers={live: 1})
    assert rejected_code(tmp_path, make_zip(wrong_old)) == "fixture_mismatch"
    wrong_new = report_document(answers={replay: 1}, protocolId=TOOL_PROTOCOL_ID,
                                fixtureSha256=TOOL_FIXTURE_SHA256)
    assert rejected_code(tmp_path, make_zip(wrong_new, fixture=TOOL_FIXTURE)) == "fixture_mismatch"
    wrong_protocol = report_document(answers={live: 1}, fixtureSha256=TOOL_FIXTURE_SHA256)
    assert rejected_code(tmp_path, make_zip(wrong_protocol, fixture=TOOL_FIXTURE)) == "fixture_mismatch"
