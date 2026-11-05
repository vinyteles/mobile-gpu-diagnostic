"""Report ZIP validation: structure, bounds, protocol, fixture and catalog checks."""

import io
import json
import zipfile

import pytest

from server.config import Limits
from server.tests.conftest import (
    EXAMPLE_ZIP,
    make_zip,
    report_document,
)
from server.validation import (
    ReportRejected,
    inspect_archive,
)

LIMITS = Limits()


def inspect_bytes(tmp_path, data: bytes, limits: Limits = LIMITS):
    path = tmp_path / "upload.zip"
    path.write_bytes(data)
    return inspect_archive(path, limits)


def rejected_code(tmp_path, data: bytes, limits: Limits = LIMITS) -> str:
    with pytest.raises(ReportRejected) as error:
        inspect_bytes(tmp_path, data, limits)
    return error.value.code


def test_valid_partial_report(tmp_path):
    report = report_document(answers={"gemma-mixed48-gpu-replay": 120, "lfm3-q4km-vulkan-replay": 7})
    summary = inspect_bytes(tmp_path, make_zip(report, extra={"worker-1.log": b"log", "native.trace": b"t"}))
    assert summary.completed_answers == 127
    assert summary.configuration_ids == ["gemma-mixed48-gpu-replay", "lfm3-q4km-vulkan-replay"]
    assert summary.configurations[0]["status"] == "completed"
    assert summary.smoke_test is True


def test_preparation_report_without_results(tmp_path):
    report = report_document(answers={}, queue_status="running")
    report["preparation"] = {"gemma-mixed48": {"phase": "downloading", "bytes": 10, "totalBytes": 100}}
    summary = inspect_bytes(tmp_path, make_zip(report))
    assert summary.completed_answers == 0 and summary.preparation["gemma-mixed48"]["phase"] == "downloading"


def test_imported_cumulative_report_with_all_configurations(tmp_path):
    from server.catalog import CONFIGURATION_MODELS
    report = report_document(answers={configuration: 120 for configuration in CONFIGURATION_MODELS}, queue_status="completed")
    report["metadata"]["importedOnDevice"] = {"model": "Other phone"}
    summary = inspect_bytes(tmp_path, make_zip(report))
    assert summary.completed_answers == 9 * 120 and summary.terminal


@pytest.mark.skipif(not EXAMPLE_ZIP.exists(), reason="app-generated example not present on this machine")
def test_genuine_app_generated_example(tmp_path):
    summary = inspect_archive(EXAMPLE_ZIP, LIMITS)
    assert summary.smoke_test is True and summary.completed_answers == 120


@pytest.mark.parametrize("name", ["../evil.log", "dir/report.log", "/abs.log", "evil.sh", "report.json/x", "a\\b.log"])
def test_rejects_unexpected_member_names(tmp_path, name):
    assert rejected_code(tmp_path, make_zip(extra={name: b"x"})) == "unexpected_member"


def test_rejects_duplicate_members(tmp_path):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("rag-replay.json", b"{}")
        with pytest.warns(UserWarning):
            archive.writestr("rag-replay.json", b"{}")
    assert rejected_code(tmp_path, buffer.getvalue()) == "duplicate_member"


def test_rejects_non_zip_and_truncated(tmp_path):
    assert rejected_code(tmp_path, b"not a zip") == "invalid_zip"
    assert rejected_code(tmp_path, make_zip()[:-200]) == "invalid_zip"


def test_rejects_corrupted_member_crc(tmp_path):
    data = bytearray(make_zip(extra={"x.log": b"A" * 5000}))
    # Flip a byte inside the stored fixture data region, far from headers.
    index = data.find(b"rag-replay.json") + 400
    data[index] ^= 0xFF
    assert rejected_code(tmp_path, bytes(data)) in ("invalid_zip",)


def test_rejects_decompression_bomb(tmp_path):
    limits = Limits(max_member_bytes=1024 * 1024, max_uncompressed_bytes=4 * 1024 * 1024)
    bomb = make_zip(extra={"bomb.log": b"\0" * (2 * 1024 * 1024)})
    assert rejected_code(tmp_path, bomb, limits) == "member_too_large"


def test_rejects_too_many_members(tmp_path):
    extra = {f"w{i}.log": b"x" for i in range(70)}
    assert rejected_code(tmp_path, make_zip(extra=extra)) == "too_many_members"


def test_rejects_protocol_and_fixture_mismatch(tmp_path):
    assert rejected_code(tmp_path, make_zip(report_document(protocolId="mobile-rag-replay-30-v1"))) == "protocol_mismatch"
    assert rejected_code(tmp_path, make_zip(report_document(fixtureSha256="0" * 64))) == "fixture_mismatch"
    assert rejected_code(tmp_path, make_zip(fixture=b'{"questions": []}')) == "fixture_mismatch"
    assert rejected_code(tmp_path, make_zip(skip=("rag-replay.json",))) == "missing_fixture"
    assert rejected_code(tmp_path, make_zip(skip=("report.json",))) == "missing_report"


def test_rejects_catalog_violations(tmp_path):
    assert rejected_code(tmp_path, make_zip(report_document(answers={"gemma-other-gpu-replay": 1}))) == "unknown_configuration"
    report = report_document()
    report["results"][0]["modelSha256"] = "1" * 64
    assert rejected_code(tmp_path, make_zip(report)) == "model_mismatch"
    report = report_document()
    report["results"].append(dict(report["results"][0]))
    assert rejected_code(tmp_path, make_zip(report)) == "duplicate_configuration"


def test_rejects_bad_run_id_and_json(tmp_path):
    assert rejected_code(tmp_path, make_zip(report_document(run_id="../../etc"))) == "invalid_report"
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        from server.tests.conftest import FIXTURE
        archive.writestr("rag-replay.json", FIXTURE)
        archive.writestr("report.json", b"{not json")
    assert rejected_code(tmp_path, buffer.getvalue()) == "invalid_report"


def test_rejects_too_many_answers(tmp_path):
    report = report_document(answers={"gemma-mixed48-gpu-replay": 121})
    assert rejected_code(tmp_path, make_zip(report)) == "invalid_report"


def test_progress_rows_match_cloud_uploader_shape(tmp_path):
    summary = inspect_bytes(tmp_path, make_zip())
    assert set(summary.configurations[0]) == {"id", "status", "phase", "completed", "total", "currentQuestion", "updatedAtMs"}
    json.dumps(summary.configurations)
