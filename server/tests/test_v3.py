"""V3 protocol isolation, completion gates, and retrieval-stage evaluation."""

import hashlib
import json
import subprocess
import sys

import pytest

from benchmark_v3.evaluate import (
    prepare,
    stage_metrics,
)
from server.catalog import (
    BATCH_CONFIGURATIONS,
    V3_FIXTURE_SHA256,
    V3_PROTOCOL_ID,
)
from server.tests.conftest import (
    REPO_ROOT,
    make_zip,
    register,
    report_document,
    upload,
)

FIXTURE = (REPO_ROOT / "app/src/main/assets/v3-questions.json").read_bytes()


def test_v3_preserves_questions_but_not_protocol():
    assert hashlib.sha256(FIXTURE).hexdigest() == V3_FIXTURE_SHA256
    original = json.loads((REPO_ROOT / "app/src/main/assets/tool-questions.json").read_bytes())
    assert json.loads(FIXTURE)["questions"] == original["questions"]
    assert json.loads(FIXTURE)["protocolId"] != original["protocolId"]


def test_all_eight_v3_configurations_complete_independently(client):
    for batch in range(116, 124):
        session, secret = register(client, batch=batch)
        identity = BATCH_CONFIGURATIONS[batch][0]
        for count, expected in ((119, "stored"), (120, "accepted")):
            report = report_document(answers={identity: count}, protocolId=V3_PROTOCOL_ID, fixtureSha256=V3_FIXTURE_SHA256)
            receipt = upload(client, session, secret, make_zip(report, fixture=FIXTURE), created_at_ms=1_700_000_000_000 + count)
            assert receipt.status_code == 202, receipt.text
            assert receipt.json()["state"] == expected


def test_document_hit_does_not_imply_evidence_survived():
    reference = {"sourceFile": "manual.pdf", "evidence": [{"text": "Required marker CEDAR-731."}]}
    raw = [{"filename": "manual.pdf", "text": "Required marker CEDAR-731."}]
    compressed = [{"filename": "manual.pdf", "text": "Other information."}]
    assert stage_metrics(raw, reference)["verbatim_evidence_span_coverage"] == 1
    assert stage_metrics(compressed, reference)["exact_source_hit"] == 1
    assert stage_metrics(compressed, reference)["verbatim_evidence_span_coverage"] == 0
    assert stage_metrics([{"filename": "NR-17 - section 1", "text": "Other"}], reference)["exact_source_hit"] is None


def test_pending_and_no_final_answers_have_different_denominators(tmp_path):
    identity = BATCH_CONFIGURATIONS[116][0]
    report = report_document(answers={identity: 2}, smoke=False, protocolId=V3_PROTOCOL_ID, fixtureSha256=V3_FIXTURE_SHA256)
    archive = tmp_path / "phone.zip"
    archive.write_bytes(make_zip(report, fixture=FIXTURE))
    output = tmp_path / "evaluation"
    prepare([archive], output)
    summary = json.loads((output / "summary.json").read_text())[0]
    assert summary["attempted"] == 2
    assert summary["pending"] == 118
    assert summary["qa"]["mean_all_attempts"] == 0
    with pytest.raises(ValueError, match="duplicate configuration"):
        prepare([archive, archive], output)


def test_synthetic_reports_never_enter_quality_scores(tmp_path):
    report = report_document(answers={BATCH_CONFIGURATIONS[116][0]: 1}, protocolId=V3_PROTOCOL_ID, fixtureSha256=V3_FIXTURE_SHA256)
    archive = tmp_path / "synthetic.zip"
    archive.write_bytes(make_zip(report, fixture=FIXTURE))
    with pytest.raises(ValueError, match="Synthetic"):
        prepare([archive], tmp_path / "output")


def test_documented_cli_order_and_raw_context_stage_separation(tmp_path):
    identity = BATCH_CONFIGURATIONS[116][0]
    report = report_document(answers={identity: 1}, smoke=False, protocolId=V3_PROTOCOL_ID, fixtureSha256=V3_FIXTURE_SHA256)
    question = json.loads(FIXTURE)["questions"][0]
    raw = {"filename": question["reference"]["sourceFile"], "text": question["reference"]["evidence"][0]["text"]}
    final = {**raw, "text": "Unrelated surviving fragment."}
    answer = report["results"][0]["answers"][0]
    answer.update(finalAnswerAvailable=True, sources=[final], toolTrace=[{
        "query": "model query", "effectiveQuery": "model query", "trigger": "model",
        "retrievalTrace": {"candidateSources": [raw]}, "retrievedSources": [final], "sources": [final],
    }])
    archive = tmp_path / "phone.zip"
    archive.write_bytes(make_zip(report, fixture=FIXTURE))
    output = tmp_path / "evaluation"
    subprocess.run([sys.executable, "-m", "benchmark_v3.evaluate", "prepare", "--output", str(output), str(archive)], cwd=REPO_ROOT, check=True)
    row = json.loads((output / "rows.json").read_text())[0]
    assert row["candidate_metrics"]["verbatim_evidence_span_coverage"] == 1
    assert row["context_metrics"]["verbatim_evidence_span_coverage"] == 0
    summary = json.loads((output / "summary.json").read_text())[0]
    assert summary["qa"]["unresolved"] == 1
    assert summary["qa"]["mean_all_attempts"] is None
