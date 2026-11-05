# Upload relay API (v1)

The relay lets the benchmark APK publish report ZIPs without a Hugging Face account. The phone
registers a **session**, then posts raw ZIP checkpoints. The relay validates and durably stores
each checkpoint and acknowledges it. It then publishes the checkpoint in the background to one
**server-owned public HF dataset per session**. The HF credential never leaves the server.

This contract is stable for `/v1`: field and header names below will not change incompatibly.

## Base URL and server identity

The relay runs behind a temporary Cloudflare quick tunnel, so its URL **can change** whenever
the tunnel restarts. Resolve the URL from a stable bootstrap document rather than compiling it
into the APK:

- the parent's GitHub raw bootstrap JSON (`endpoint` + `serviceInstanceId`), or
- `https://huggingface.co/datasets/vinyteles/mobilebench-index/resolve/main/endpoint.json`,
  which the relay updates itself after verifying the URL reaches it:

```json
{
  "service": "mobilebench-upload-relay",
  "url": "https://memorial-simon-bush-flickr.trycloudflare.com",
  "serviceInstanceId": "mbr_52ee5dc7e60dfcf2266e54e506bdb10b",
  "stable": false,
  "note": "Temporary Cloudflare quick-tunnel URL; it changes when the tunnel restarts. Re-read this file before uploading.",
  "verifiedAtHour": "2026-11-05T16:00Z"
}
```

Before sending a session secret to a resolved URL, the client must:

1. require `https://` (for quick tunnels, a host ending in `.trycloudflare.com`);
2. call `GET <url>/v1/health` and require `service == "mobilebench-upload-relay"` and
   `serviceInstanceId` equal to the bootstrap value. Treat a mismatch as "relay unavailable" and
   keep the checkpoint queued.

`serviceInstanceId` is created once in the relay's data directory and survives restarts and
tunnel URL changes. It is public: it identifies the server, but it is not a credential.

## Authentication model

- At registration, the **client generates** `sessionSecret`: at least 32 random bytes encoded as
  base64url without padding, 43–128 characters. Kotlin example:
  `Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes), URL_SAFE or NO_PADDING or NO_WRAP)`.
- The relay stores only `SHA-256(sessionSecret)`. The secret is bound to exactly one session
  and is never returned, logged, or shown in feeds.
- Later requests send `Authorization: Bearer <sessionSecret>`. Only that session's endpoints
  accept it.
- The installation ID is informational: it forms the `cloudRunId` suffix and does not prove
  ownership. The APK contains no shared secret.
- Send secrets over HTTPS only. Store them app-private (e.g. EncryptedSharedPreferences /
  Keystore-wrapped), and never in report ZIPs or Downloads.

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/v1/health` | none | Liveness + `serviceInstanceId` |
| GET | `/v1/ready` | none | Readiness of database, worker, HF credential, disk (200/503) |
| POST | `/v1/sessions` | none (rate-limited) | Create **or resume** a session |
| GET | `/v1/sessions/{sessionId}` | bearer | Session status: accepted vs synced, HF URL |
| POST | `/v1/sessions/{sessionId}/checkpoints` | bearer | Submit a raw report ZIP |
| GET | `/v1/sessions/{sessionId}/checkpoints/{uploadId}` | bearer | One upload's state |
| GET | `/v1/public/sessions` | none | Public JSON feed of all sessions (no secrets) |
| GET | `/` | none | Human-readable status page of the same feed |

### GET /v1/health

```json
{"service": "mobilebench-upload-relay", "apiVersion": "v1",
 "serviceInstanceId": "mbr_52ee5dc7e60dfcf2266e54e506bdb10b", "status": "ok"}
```

### GET /v1/ready

```json
{"service": "mobilebench-upload-relay", "ready": true,
 "checks": {"database": true, "workerAlive": true, "hubCredential": true, "diskSpace": true},
 "workerHeartbeatAgeS": 0.3, "uploads": {"published": 3, "superseded": 1}, "pendingBytes": 0}
```

When a check fails, the endpoint returns 503. Uploads are still accepted while HF is
unavailable, because they wait in the durable queue.

### POST /v1/sessions — create or resume

Create a new session with a **new secret** for each new RTL reservation, batch, or fresh
installation. Persist the secret together with the response. To resume after an app restart,
or after a lost response, send the **same body again**: the relay returns the same session and
repository and never creates a duplicate.

Request (`Content-Type: application/json`, ≤ 16 KiB):

```json
{
  "sessionSecret": "3q2-7wO8x7ZcY1yQm9b3D1yQ6H1T2iWkQe7p0bqXq4E",
  "installationId": "8b1693f0-2f5c-4d4b-9a49-0f0a5c1e9f11",
  "batch": 1,
  "appVersion": "2.2",
  "purpose": "benchmark"
}
```

- `batch`: APK preset 1–5. The relay uses it for the published batch label.
- `purpose`: `benchmark` (default) or `synthetic-test`. Test sessions get `-test-` in their
  repository name and a SYNTHETIC banner in the dataset card.
- `installationId`: lowercase UUID. Its first 8 characters become the `cloudRunId` suffix,
  exactly as in APK 2.1.

Response `201 Created` (new) or `200 OK` (resumed, `"created": false`):

```json
{
  "sessionId": "s_c99cee52c84f3fe4",
  "created": true,
  "repository": "vinyteles/mobilebench-relay-20261105-b1-21b6a049c9",
  "repositoryUrl": "https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b1-21b6a049c9",
  "repositoryCreated": false,
  "batch": 1,
  "batchName": "Batch 1 · Previously tested GPUs",
  "purpose": "benchmark",
  "createdAtMs": 1793896886000,
  "statusUrl": "/v1/sessions/s_c99cee52c84f3fe4",
  "checkpointsUrl": "/v1/sessions/s_c99cee52c84f3fe4/checkpoints",
  "sync": {"state": "idle", "pendingUploads": 0, "acceptedUploads": 0, "oldestPendingReceivedAtMs": null,
           "lastPublishedAtMs": null, "lastCommit": null, "lastError": null, "nextAttemptAtMs": null},
  "latestAccepted": null,
  "latestPublished": null,
  "limits": {"maxUploadBytes": 67108864, "maxUncompressedBytes": 536870912, "uploadsPerHour": 150}
}
```

The relay chooses the repository; clients can never name a repository, path or URL. The repo
itself is created on the first published checkpoint (`repositoryCreated`).
`statusUrl` and `checkpointsUrl` are relative to the base URL, so keep them valid across
tunnel URL changes by joining them with the currently resolved base.

Errors: `400 invalid_secret | invalid_installation | invalid_batch | invalid_app_version | invalid_purpose | invalid_json`,
`409 secret_conflict` (same secret, different installation/batch/purpose),
`413 body_too_large`, `429 rate_limited | registration_quota | session_quota`.

### POST /v1/sessions/{sessionId}/checkpoints — submit a ZIP

The body is the **raw ZIP bytes** exactly as `ReportExporter.archive()` wrote them, with no
multipart wrapping and no re-compression.

| Header | Required | Value |
|---|---|---|
| `Authorization` | yes | `Bearer <sessionSecret>` |
| `Content-Type` | yes | `application/zip` (or `application/octet-stream`) |
| `Content-Length` | recommended | Byte count (fixed-length streaming mode) |
| `X-Content-SHA256` | yes | Lowercase hex SHA-256 of the body |
| `X-Checkpoint-Kind` | no | `latest` (default; routine/periodic/stop) or `configuration` (after a configuration finishes, fails or is skipped) |
| `X-Configuration-Id` | iff kind=configuration | One of the nine configuration ids, and present in `report.json` results |
| `X-Checkpoint-Created-At-Ms` | recommended | Phone epoch ms when this snapshot was made; orders snapshots. Implausible values (> 400 days old or > 1 day ahead) fall back to server receipt time |
| `X-App-Version` | recommended | `BuildConfig.VERSION_NAME` |

Response `202 Accepted` (new) or `200 OK` (identical retry, `"duplicate": true`):

```json
{
  "uploadId": "u_97d5f1f2d09ab7526de4",
  "sessionId": "s_c99cee52c84f3fe4",
  "duplicate": false,
  "state": "accepted",
  "kind": "configuration",
  "configurationId": "gemma-mixed48-gpu-replay",
  "sha256": "0c532c51cd996e3cea75900239042c5b1f9658704dcd52d40179aff4afdbf30a",
  "sizeBytes": 625092,
  "cloudRunId": "cloudtest-1184688406811-46f3dae6",
  "completedAnswers": 120,
  "clientCreatedAtMs": 1793896946000,
  "receivedAtMs": 1793896946912,
  "publishedAtMs": null,
  "commit": null,
  "repository": "vinyteles/mobilebench-relay-20261105-b1-21b6a049c9",
  "repositoryUrl": "https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b1-21b6a049c9",
  "statusUrl": "/v1/sessions/s_c99cee52c84f3fe4/checkpoints/u_97d5f1f2d09ab7526de4"
}
```

**`202`/`200` means the bytes are fsynced on the relay and recorded in its database. The phone
may delete its outbox copy**. The local report in Downloads remains the user's backup. HF
publication happens later. `state` then moves to `published` (adding `commit`, `publishedAtMs`
and `commitUrl`) or to `superseded` (a newer cumulative snapshot covering the same destination
was published instead).

Validation performed before acceptance (`422 <code>`):
`invalid_zip`, `too_many_members` (> 64), `unexpected_member` (anything other than the
ReportExporter names or `[A-Za-z0-9_.-]+.(log|trace)`; no directories or paths),
`duplicate_member`, `member_too_large` / `uncompressed_too_large` (256 MiB per member,
512 MiB total, enforced while inflating), `missing_fixture` / `fixture_mismatch` (the
`rag-replay.json` SHA-256 and `metadata.fixtureSha256` must be
`e43daa8d6887b372ad05a729c9dd53488fd29023bd228836c1e98060817c3cc4`), `missing_report`,
`invalid_report`, `protocol_mismatch` (`mobile-rag-replay-120-v2` only),
`unknown_configuration`, `duplicate_configuration`, `model_mismatch` (pinned model SHA per
configuration), `configuration_not_in_report`.
Partial reports are valid: empty `results` during downloads, partial answers, `preparation`
data, and imported cumulative results carrying earlier device provenance.

Other errors: `400 missing_checksum | checksum_mismatch | invalid_kind | invalid_configuration | empty_body | invalid_app_version`,
`401 unauthorized`, `404 unknown_session`, `408 upload_timeout`,
`409 run_quota` (32 runs per session; register a new session),
`413 upload_too_large` (64 MiB compressed), `415 unsupported_media_type`,
`429 rate_limited | server_busy | daily_volume_quota`,
`507 session_spool_full | spool_full | disk_full`, `500 internal_error`.

### GET /v1/sessions/{sessionId}

This returns the same document as registration, with live `sync`, `latestAccepted` and
`latestPublished`. Use it for the app's sync line:

- `sync.state == "pending"`: the relay has accepted the checkpoints, but they are not on HF
  yet. `lastError` and `nextAttemptAtMs` show relay-side HF retries; the phone does nothing.
- `sync.state == "synced"`: everything accepted is on HF (`lastCommit`).

### GET /v1/public/sessions

```json
{"service": "mobilebench-upload-relay", "generatedAtMs": 1793897000000, "sessions": [
  {"sessionId": "s_c99cee52c84f3fe4", "repository": "vinyteles/mobilebench-relay-…",
   "repositoryUrl": "https://huggingface.co/datasets/vinyteles/mobilebench-relay-…",
   "repositoryCreated": true, "batch": 1, "batchName": "Batch 1 · Previously tested GPUs",
   "purpose": "benchmark", "createdAtMs": 1793896886000,
   "sync": {"state": "synced", "pendingUploads": 0, "acceptedUploads": 4,
            "lastPublishedAtMs": 1793896950000, "lastCommit": "61430cae…", "retrying": false},
   "latest": {"uploadId": "u_…", "state": "published", "cloudRunId": "bench120-…-8b1693f0",
              "updatedAtMs": 1793896946000, "receivedAtMs": 1793896946912, "appVersion": "2.2",
              "smokeTest": false, "completedAnswers": 37,
              "queue": {"status": "running", "stage": "benchmark"},
              "configurations": [{"id": "gemma-mixed48-gpu-replay", "status": "running", "completed": 37, "total": 120}],
              "device": {"manufacturer": "samsung", "model": "SM-S931B", "socManufacturer": "Qualcomm",
                         "socModel": "SM8750", "androidRelease": "16"},
              "clientReported": true}}]}
```

The worker also publishes the same list as `sessions.json` in
`https://huggingface.co/datasets/vinyteles/mobilebench-index` (at most every 2 minutes, only
on change). That URL stays stable when the tunnel URL changes. `sessions.json` is a list of
objects with `repository`, so it works directly with
`scripts/watch-hf-benchmark.py --sessions-file`.

Device, timing and answer fields are **client-reported**. The relay checks the report format,
the fixture and the model checksums. It does not authenticate the hardware.

## Published HF layout (unchanged from APK 2.1, plus sidecars)

```
README.md                                        dataset card (synthetic banner for test sessions)
latest.json                                      newest snapshot of any run in the session
runs/<cloudRunId>/progress.json                  newest snapshot of this run
runs/<cloudRunId>/latest.zip                     the ZIP that progress.json hashes
runs/<cloudRunId>/configurations/<id>.zip        configuration snapshot
runs/<cloudRunId>/configurations/<id>.json       its progress (new: archivePath/archiveSha256 for that ZIP)
```

Each progress JSON has the APK 2.1 fields (`schemaVersion`, `cloudRunId`, `localRunId`,
`updatedAtMs`, `appVersion`, `batch`, `smokeTest`, `device`, `queue`, `completedAnswers`,
`configurations`, `preparation`, `archivePath`, `archiveSha256`). The relay computes them from
the received ZIP, and adds a `relay` object (`sessionId`, `uploadId`, `receivedAtMs`,
`checkpointKind`, `clientCreatedAtMs`, `clientReported: true`, `note`). Every JSON is committed
**in the same HF commit** as the ZIP it hashes. A slot is only overwritten by a newer
snapshot, ordered by (`X-Checkpoint-Created-At-Ms`, completed answers, receipt time).
Routine checkpoints are coalesced, with at most one routine commit per session every 90 s.
Configuration snapshots and final checkpoints (queue status `completed`/`stopped`/`error`) are
published without the 90 s pacing.

## Limits (defaults)

| Limit | Default | Response |
|---|---|---|
| ZIP size (compressed) | 64 MiB | 413 |
| Uncompressed total / per member / members | 512 MiB / 256 MiB / 64 | 422 |
| Registrations per client IP | 20 / hour | 429 |
| New sessions service-wide | 60 / day, 500 total | 429 |
| Uploads per session / per client IP | 150 / hour, 900 / hour | 429 |
| Upload volume per session | 6 GiB / day | 429 |
| Unpublished spool per session / total | 384 MiB / 3 GiB | 507 |
| Free disk kept on the volume | 5 GiB | 507 |
| Concurrent bodies being received / ZIP validations | 8 / 2 | 429 `server_busy` |
| Body idle / total time | 60 s / 900 s | 408 |
| Status/feed reads per client IP | 3600 / hour | 429 |

The client IP comes from `CF-Connecting-IP` only when the TCP peer is the local tunnel
connector (127.0.0.1). Otherwise the peer address is used.

## Retry semantics

Every error body is `{"error": {"code": "...", "message": "...", "retryable": true|false}}`.
429, 503, 507 and some 500 responses carry `Retry-After` (seconds).

| Outcome | Client action |
|---|---|
| Network error, timeout, 408, 5xx, 429, 507 | Keep the checkpoint; retry the **same bytes and headers** after `Retry-After` or backoff (30 s doubling to 5 min). Duplicates are harmless (`200 duplicate`). |
| 400 `checksum_mismatch` | Recompute the hash from the stored file and retry once; if it persists, treat the file as corrupt. |
| 401 / 404 on a session | Session secret lost or wrong: register a **new** session (new secret); do not retry the old one. |
| 409 `secret_conflict` | Bug: a secret was reused for another batch/installation. Generate a new secret. |
| 409 `run_quota` | Register a new session for further runs. |
| 413 / 415 / 422 | Not retryable: the report violates the contract. Keep the local ZIP and show the `code`. |
| Base URL unreachable or `serviceInstanceId` mismatch | Re-read the bootstrap JSON (URL may have changed); retry later. |

Newer snapshots are safe to send while older ones are still queued, and in any order. The
relay never lets an older snapshot replace newer progress. Phones only need to keep the newest
routine checkpoint plus each configuration snapshot in their outbox.

## Examples

curl:

```sh
BASE=$(curl -fsSL https://huggingface.co/datasets/vinyteles/mobilebench-index/resolve/main/endpoint.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["url"])')
SECRET=$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')
curl -fsS "$BASE/v1/health"
curl -fsS -X POST "$BASE/v1/sessions" -H 'Content-Type: application/json' \
  -d "{\"sessionSecret\":\"$SECRET\",\"installationId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"batch\":1,\"appVersion\":\"2.2\",\"purpose\":\"synthetic-test\"}" \
  | tee session.json
SESSION=$(python3 -c 'import json; print(json.load(open("session.json"))["sessionId"])')
curl -fsS -X POST "$BASE/v1/sessions/$SESSION/checkpoints" \
  -H "Authorization: Bearer $SECRET" -H 'Content-Type: application/zip' \
  -H "X-Content-SHA256: $(sha256sum report.zip | cut -d' ' -f1)" \
  -H "X-Checkpoint-Created-At-Ms: $(date +%s%3N)" --data-binary @report.zip
curl -fsS -H "Authorization: Bearer $SECRET" "$BASE/v1/sessions/$SESSION"
```

(Passing the secret via `-H` puts it in the process list; this is fine for local testing, but
production clients send it from memory.)

Python: `server/e2e_check.py` is a complete client. It registers (and retries registration),
uploads a configuration snapshot and a duplicate, checks isolation and malformed-ZIP rejection,
waits for publication, and downloads the JSON+ZIP pairs anonymously from HF at the commit to
verify the checksums:

```sh
python -m server.e2e_check --base-url "$BASE" --zip examples/report.zip   # synthetic ZIP only
```

## Android integration contract (for the next APK)

1. Resolve the base URL from the bootstrap JSON. Verify `GET /v1/health` (`service`,
   `serviceInstanceId`) before sending any secret, and re-resolve after failures.
2. On first upload for a (reservation, batch, installation) combination, generate
   `sessionSecret` and persist `{secret, installationId, batch, purpose}` **before** calling
   `POST /v1/sessions`. Then persist the response (`sessionId`, `repositoryUrl`). On restart,
   resubmit the same body; this is idempotent.
3. Replace `HuggingFaceClient.publish` with `POST …/checkpoints` (raw ZIP, headers above). Use
   kind `configuration` + `X-Configuration-Id` where `CloudUploader.enqueue` receives a
   configuration, and kind `latest` otherwise. Use the existing outbox and backoff, keyed by
   file, and delete the outbox entry on `202`/`200`.
4. Show "accepted by relay" (202) separately from "synced to HF"
   (`GET /v1/sessions/{id}` → `sync.state`), and display `repositoryUrl`.
5. Do not add tokens or the session secret to report ZIPs. Synthetic reports must keep
   `smokeTest=true`, and debug builds should register with `purpose: "synthetic-test"`.
