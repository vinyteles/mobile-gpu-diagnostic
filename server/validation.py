"""Validate untrusted report ZIPs without extracting them, and summarize them like CloudUploader.

Example: a valid archive yields
    ReportSummary(local_run_id="bench120-1793850219685", smoke_test=False, completed_answers=37,
                  configurations=[{"id": "gemma-mixed48-gpu-replay", "status": "running", ...}],
                  completed_questions={"gemma-mixed48-gpu-replay": 0x1f...}, ...)

completed_questions maps each configuration to a bitmap of fixture question positions whose
answer status is "completed": bit i set <=> questions[i].id of rag-replay.json was answered.
"""

import dataclasses
import hashlib
import json
import re
import zipfile
from pathlib import Path

from server.catalog import (
    BATCH_CONFIGURATIONS,
    CONFIGURATION_MODELS,
    FIXED_MEMBERS,
    FIXTURE_SHA256,
    LOG_MEMBER,
    PROTOCOL_ID,
    QUESTION_COUNT,
    TERMINAL_QUEUE_STATES,
)
from server.config import Limits

RUN_ID = re.compile(r"[A-Za-z0-9_-]{1,80}")
MAX_DEVICE_JSON_BYTES = 32 * 1024
# Fixture question id -> position, parsed once from the first verified rag-replay.json.
_QUESTION_INDEX: dict[str, int] = {}


class ReportRejected(Exception):
    """A client report that must not be accepted. `code` is stable API vocabulary."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclasses.dataclass
class ReportSummary:
    """Facts derived from report.json; all of them are client-reported, not device-attested."""

    local_run_id: str
    smoke_test: bool
    completed_answers: int
    configuration_ids: list[str]
    configurations: list[dict]
    queue: dict | None
    preparation: dict | None
    device: dict | None
    app_version: str | None
    uncompressed_bytes: int
    completed_questions: dict[str, int] = dataclasses.field(default_factory=dict)

    @property
    def terminal(self) -> bool:
        return (self.queue or {}).get("status") in TERMINAL_QUEUE_STATES


@dataclasses.dataclass(frozen=True)
class Completion:
    """How far a report is toward publication for one session's assigned configurations.

    Example: a Config 1 session with 42 answered questions gives
        Completion(saved_answers=42, required_answers=120, complete=False)
    """

    saved_answers: int
    required_answers: int
    complete: bool


def inspect_archive(path: Path, limits: Limits) -> ReportSummary:
    """Check archive structure, member contents and the benchmark contract.

    Args:
        path: Spooled ZIP file received from the client.
        limits: Member count and size bounds.
    Returns:
        A summary used to build progress.json and the public feed.
    Raises:
        ReportRejected: for any structural, size, protocol, fixture or catalog violation.
    """
    try:
        with zipfile.ZipFile(path) as archive:
            # 1. Check the central directory before reading any data.
            infos = archive.infolist()
            check_members(infos, limits)

            # 2. Inflate every member with a hard byte budget; zipfile verifies each CRC at the end.
            contents = {}
            total = 0
            for info in infos:
                data, size = read_member(archive, info, limits, limits.max_uncompressed_bytes - total)
                total += size
                if info.filename in ("report.json", "rag-replay.json"):
                    contents[info.filename] = data
    except zipfile.BadZipFile as error:
        raise ReportRejected("invalid_zip", f"Not a readable ZIP archive ({error}).") from None
    except (zipfile.LargeZipFile, NotImplementedError, RuntimeError, EOFError, OSError):
        raise ReportRejected("invalid_zip", "ZIP archive uses unsupported or corrupt structures.") from None

    # 3. Require the frozen benchmark inputs, byte-identical to the app asset.
    if "rag-replay.json" not in contents:
        raise ReportRejected("missing_fixture", "rag-replay.json is required.")
    if hashlib.sha256(contents["rag-replay.json"]).hexdigest() != FIXTURE_SHA256:
        raise ReportRejected("fixture_mismatch", "rag-replay.json does not match the frozen benchmark fixture.")
    if "report.json" not in contents:
        raise ReportRejected("missing_report", "report.json is required.")

    # 4. Validate report.json against the protocol and configuration catalog.
    question_index = fixture_question_index(contents["rag-replay.json"])
    summary = summarize_report(contents["report.json"], question_index)
    summary.uncompressed_bytes = total
    return summary


def fixture_question_index(fixture: bytes) -> dict[str, int]:
    """Return question id -> position of the frozen fixture, parsing it only once per process.

    Args:
        fixture: rag-replay.json bytes already verified against FIXTURE_SHA256.
    Returns:
        Mapping with QUESTION_COUNT entries, e.g. {"85ddfcebb34fe97a5567bb69": 0, ...}.
    """
    if not _QUESTION_INDEX:
        questions = json.loads(fixture)["questions"]
        index = {question["id"]: position for position, question in enumerate(questions)}
        if len(index) != QUESTION_COUNT:
            raise RuntimeError("the frozen fixture does not list exactly QUESTION_COUNT distinct questions")
        _QUESTION_INDEX.update(index)
    return _QUESTION_INDEX


def assess_completion(completed_questions: dict[str, int], batch: int) -> Completion:
    """Count distinct completed fixture questions of a session's assigned configurations.

    Args:
        completed_questions: Configuration id -> completed-question bitmap (ReportSummary field).
        batch: Session batch/profile id; selects the assigned configurations.
    Returns:
        Saved and required answer counts; complete only when every assigned configuration has all
        QUESTION_COUNT questions completed. Unassigned (imported) configurations never count.
    """
    assigned = BATCH_CONFIGURATIONS[batch]
    saved = sum(completed_questions.get(configuration, 0).bit_count() for configuration in assigned)
    required = QUESTION_COUNT * len(assigned)
    return Completion(saved_answers=saved, required_answers=required, complete=saved == required)


def check_members(infos: list[zipfile.ZipInfo], limits: Limits) -> None:
    """Reject unknown names, paths, duplicates, encryption and declared oversize members.

    Args:
        infos: Central directory entries.
        limits: Count and size bounds.
    """
    if not infos:
        raise ReportRejected("invalid_zip", "Archive is empty.")
    if len(infos) > limits.max_members:
        raise ReportRejected("too_many_members", f"At most {limits.max_members} archive members are allowed.")
    names = set()
    declared = 0
    for info in infos:
        name = info.filename
        if name not in FIXED_MEMBERS and not LOG_MEMBER.fullmatch(name):
            raise ReportRejected("unexpected_member", "Archive contains a member name outside the report contract.")
        if name in names:
            raise ReportRejected("duplicate_member", "Archive contains duplicate member names.")
        names.add(name)
        if info.flag_bits & 0x1:
            raise ReportRejected("invalid_zip", "Encrypted members are not accepted.")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            raise ReportRejected("invalid_zip", "Only stored or deflated members are accepted.")
        if info.file_size > limits.max_member_bytes:
            raise ReportRejected("member_too_large", f"A member exceeds {limits.max_member_bytes} bytes.")
        declared += info.file_size
    if declared > limits.max_uncompressed_bytes:
        raise ReportRejected("uncompressed_too_large", f"Archive expands beyond {limits.max_uncompressed_bytes} bytes.")


def read_member(archive: zipfile.ZipFile, info: zipfile.ZipInfo, limits: Limits, budget: int) -> tuple[bytes | None, int]:
    """Inflate one member in chunks, never trusting its declared size.

    Args:
        archive: Open archive.
        info: Member to read.
        limits: Per-member bound.
        budget: Remaining total uncompressed byte budget.
    Returns:
        The bytes for report/fixture members (None for others) and the inflated size.
    """
    keep = info.filename in ("report.json", "rag-replay.json")
    chunks = []
    size = 0
    with archive.open(info) as stream:
        while chunk := stream.read(1024 * 1024):
            size += len(chunk)
            if size > limits.max_member_bytes or size > budget:
                raise ReportRejected("uncompressed_too_large", "Archive expands beyond the configured limit.")
            if keep:
                chunks.append(chunk)
    return (b"".join(chunks) if keep else None), size


def summarize_report(raw: bytes, question_index: dict[str, int]) -> ReportSummary:
    """Parse report.json and compute progress rows exactly like CloudUploader.progress().

    Args:
        raw: report.json bytes.
        question_index: Fixture question id -> position (see fixture_question_index).
    Returns:
        Report summary (uncompressed_bytes is filled by the caller).
    """
    # 1. Parse the top-level structure.
    try:
        report = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ReportRejected("invalid_report", "report.json is not valid JSON.") from None
    if not isinstance(report, dict) or not isinstance(report.get("metadata"), dict):
        raise ReportRejected("invalid_report", "report.json needs a metadata object.")
    metadata = report["metadata"]
    results = report.get("results")
    queue = report.get("queue")
    preparation = report.get("preparation")
    if not isinstance(results, list) or not isinstance(queue, (dict, type(None))) or not isinstance(preparation, (dict, type(None))):
        raise ReportRejected("invalid_report", "report.json has malformed results, queue or preparation.")

    # 2. Check protocol, fixture digest and run identity.
    if metadata.get("protocolId") != PROTOCOL_ID:
        raise ReportRejected("protocol_mismatch", f"Only protocol {PROTOCOL_ID} is accepted.")
    if metadata.get("fixtureSha256") != FIXTURE_SHA256:
        raise ReportRejected("fixture_mismatch", "metadata.fixtureSha256 does not match the frozen fixture.")
    run_id = metadata.get("runId")
    if not isinstance(run_id, str) or not RUN_ID.fullmatch(run_id):
        raise ReportRejected("invalid_report", "metadata.runId must match [A-Za-z0-9_-]{1,80}.")
    smoke_test = metadata.get("smokeTest", False)
    if not isinstance(smoke_test, bool):
        raise ReportRejected("invalid_report", "metadata.smokeTest must be a boolean.")

    # 3. Validate each configuration record; partial, empty and imported records are allowed.
    seen = set()
    rows = []
    completed_questions = {}
    completed_total = 0
    for record in results:
        row, bitmap = summarize_configuration(record, seen, question_index)
        rows.append(row)
        completed_questions[row["id"]] = bitmap
        completed_total += row["completed"]

    # 4. Keep device metadata only when it is a reasonably sized object.
    device = metadata.get("device")
    if not isinstance(device, dict) or len(json.dumps(device)) > MAX_DEVICE_JSON_BYTES:
        device = None
    attempts = metadata.get("executionAttempts")
    app_version = None
    if isinstance(attempts, list) and attempts and isinstance(attempts[-1], dict):
        app_version = attempts[-1].get("appVersion") if isinstance(attempts[-1].get("appVersion"), str) else None
    return ReportSummary(
        local_run_id=run_id,
        smoke_test=smoke_test,
        completed_answers=completed_total,
        configuration_ids=[row["id"] for row in rows],
        configurations=rows,
        queue=queue,
        preparation=preparation,
        device=device,
        app_version=app_version,
        uncompressed_bytes=0,
        completed_questions=completed_questions,
    )


def summarize_configuration(record: object, seen: set[str], question_index: dict[str, int]) -> tuple[dict, int]:
    """Validate one results[] record and return its progress row and completed-question bitmap.

    Args:
        record: Untrusted results entry.
        seen: Configuration ids already encountered (updated in place).
        question_index: Fixture question id -> position.
    Returns:
        (row with id, status, phase, completed, total, currentQuestion and updatedAtMs,
         bitmap of fixture positions whose answer is "completed"; unknown question ids are not counted).
    """
    # 1. Identity: a known configuration, listed once, with the pinned model checksum.
    if not isinstance(record, dict):
        raise ReportRejected("invalid_report", "Each results entry must be an object.")
    configuration = record.get("id")
    if configuration not in CONFIGURATION_MODELS:
        raise ReportRejected("unknown_configuration", "results contains an unknown configuration id.")
    if configuration in seen:
        raise ReportRejected("duplicate_configuration", "results lists a configuration more than once.")
    seen.add(configuration)
    if record.get("modelSha256") != CONFIGURATION_MODELS[configuration]:
        raise ReportRejected("model_mismatch", f"{configuration} reports an unexpected model checksum.")

    # 2. Answers: optional during preparation, at most one per frozen question.
    answers = record.get("answers") or []
    if not isinstance(answers, list) or len(answers) > QUESTION_COUNT:
        raise ReportRejected("invalid_report", f"{configuration} has malformed or too many answers.")
    question_ids = set()
    completed = 0
    bitmap = 0
    for answer in answers:
        if not isinstance(answer, dict) or not isinstance(answer.get("questionId"), str):
            raise ReportRejected("invalid_report", f"{configuration} has a malformed answer.")
        if answer["questionId"] in question_ids:
            raise ReportRejected("invalid_report", f"{configuration} repeats a question id.")
        question_ids.add(answer["questionId"])
        if answer.get("status") == "completed":
            completed += 1
            if answer["questionId"] in question_index:
                bitmap |= 1 << question_index[answer["questionId"]]

    # 3. Mirror CloudUploader's optString/optInt/optLong defaults.
    row = {
        "id": configuration,
        "status": text_field(record.get("status")),
        "phase": text_field(record.get("phase")),
        "completed": completed,
        "total": int_field(record.get("totalQuestions"), QUESTION_COUNT),
        "currentQuestion": int_field(record.get("currentQuestion"), 0),
        "updatedAtMs": int_field(record.get("updatedAtMs"), 0),
    }
    return row, bitmap


def text_field(value: object) -> str:
    """Return a short string like JSONObject.optString (missing -> empty).

    Args:
        value: Untrusted JSON value.
    Returns:
        String truncated to 200 characters.
    """
    return value[:200] if isinstance(value, str) else ""


def int_field(value: object, default: int) -> int:
    """Return an integer like JSONObject.optInt/optLong, or the default.

    Args:
        value: Untrusted JSON value.
        default: Value used when missing or not an integer.
    Returns:
        Integer value.
    """
    return value if isinstance(value, int) and not isinstance(value, bool) else default


def build_progress(summary: ReportSummary, cloud_run_id: str, created_at_ms: int, app_version: str,
                   batch_name: str, archive_sha256: str, relay: dict) -> dict:
    """Build the progress.json document published next to an archive.

    Args:
        summary: Validated report summary.
        cloud_run_id: Run id plus installation prefix, as in CloudUploader.
        created_at_ms: Phone timestamp of the checkpoint (or server receipt time when implausible).
        app_version: APK version reported by the client.
        batch_name: Server-side batch label of the session.
        archive_sha256: SHA-256 of the exact ZIP bytes received.
        relay: Server provenance block (session id, receipt time, client-reported flag).
    Returns:
        progress dict; archivePath is set at publication time.
    """
    return {
        "schemaVersion": 1,
        "cloudRunId": cloud_run_id,
        "localRunId": summary.local_run_id,
        "updatedAtMs": created_at_ms,
        "appVersion": app_version,
        "batch": batch_name,
        "smokeTest": summary.smoke_test,
        "device": summary.device,
        "queue": summary.queue,
        "completedAnswers": summary.completed_answers,
        "configurations": summary.configurations,
        "preparation": summary.preparation,
        "archivePath": None,
        "archiveSha256": archive_sha256,
        "relay": relay,
    }
