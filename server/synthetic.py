"""Synthetic report ZIPs (metadata.smokeTest = true) for tests and the live completion check.

They contain no model output: every "answer" is a fixed placeholder sentence. Example:
    report = synthetic_report("live-1", {"gemma-mixed48-gpu-replay": 42})
    data = synthetic_zip(report, FIXTURE_PATH.read_bytes())
gives a cumulative snapshot with fixture questions 0..41 completed for Config 1.
"""

import io
import json
import zipfile
from collections.abc import Iterable
from pathlib import Path

from server.catalog import (
    CONFIGURATION_MODELS,
    FIXTURE_SHA256,
    PROTOCOL_ID,
    QUESTION_COUNT,
)

FIXTURE_PATH = Path(__file__).resolve().parents[1] / "app/src/main/assets/rag-replay.json"
PLACEHOLDER_ANSWER = "Synthetic smoke result; no model was run."


def fixture_question_ids(fixture: bytes) -> list[str]:
    """Return the fixture question ids in benchmark order.

    Args:
        fixture: rag-replay.json bytes.
    Returns:
        QUESTION_COUNT ids, e.g. ["85ddfcebb34fe97a5567bb69", ...].
    """
    return [question["id"] for question in json.loads(fixture)["questions"]]


def synthetic_report(run_id: str, answers: dict[str, int | Iterable[int]], question_ids: list[str],
                     queue_status: str = "running", smoke: bool = True, **metadata_overrides) -> dict:
    """Build a report.json document shaped like the APK's cumulative snapshots.

    Args:
        run_id: metadata.runId.
        answers: Configuration id -> number of completed questions (the first N fixture questions)
            or explicit fixture positions to mark completed.
        question_ids: Fixture question ids (see fixture_question_ids).
        queue_status: queue.status, e.g. "running", "stopped", "error" or "completed".
        smoke: metadata.smokeTest.
        metadata_overrides: Replace metadata fields (e.g. protocolId).
    Returns:
        report dict.
    """
    # 1. One results record per configuration, with completed answers at the chosen positions.
    results = []
    for configuration, chosen in answers.items():
        positions = range(chosen) if isinstance(chosen, int) else sorted(chosen)
        rows = [{"questionId": question_ids[position], "questionIndex": position, "status": "completed",
                 "answer": PLACEHOLDER_ANSWER, "smokeTest": True} for position in positions]
        results.append({
            "id": configuration,
            "modelSha256": CONFIGURATION_MODELS.get(configuration, "0" * 64),
            "status": "completed" if len(rows) == QUESTION_COUNT else "running",
            "phase": "generating",
            "totalQuestions": QUESTION_COUNT,
            "currentQuestion": len(rows),
            "answers": rows,
        })

    # 2. Metadata identifying a synthetic smoke run of the frozen protocol.
    metadata = {
        "runId": run_id,
        "protocolId": PROTOCOL_ID,
        "fixtureSha256": FIXTURE_SHA256,
        "smokeTest": smoke,
        "device": {"manufacturer": "Synthetic", "model": "Relay test", "socModel": "none"},
        "executionAttempts": [{"appVersion": "synthetic"}],
    }
    metadata.update(metadata_overrides)
    return {"metadata": metadata, "queue": {"status": queue_status, "stage": "benchmark"}, "results": results,
            "preparation": None}


def synthetic_zip(report: dict, fixture: bytes, extra: dict | None = None, skip: tuple = ()) -> bytes:
    """Build a report ZIP like ReportExporter.archive().

    Args:
        report: report.json content.
        fixture: rag-replay.json bytes.
        extra: Additional member name -> bytes.
        skip: Member names to omit.
    Returns:
        ZIP bytes.
    """
    members = {
        "report.json": json.dumps(report).encode(),
        "summary.txt": b"SMOKE TEST: no model inference\n",
        "rag-replay.json": fixture,
    }
    members.update(extra or {})
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in members.items():
            if name not in skip:
                archive.writestr(name, data)
    return buffer.getvalue()
