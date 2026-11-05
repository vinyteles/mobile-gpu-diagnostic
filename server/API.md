# Upload relay API (v1)

The relay lets the benchmark APK publish report ZIPs without a Hugging Face account. The phone
registers a **session**, then posts a cumulative raw ZIP checkpoint after every completed
question (plus stop, final and preparation snapshots). The relay validates and durably stores
each checkpoint and acknowledges it. **Partial reports never leave the relay.** Only when the
session's assigned configuration(s) hold all 120 fixture questions completed does the relay
publish the report, in the background, to one **server-owned public HF dataset per session**.
The HF credential never leaves the server.

This contract is stable for `/v1`: field and header names below will not change incompatibly.

## Base URL and server identity

The relay runs behind a temporary Cloudflare quick tunnel, so its URL **can change** whenever
the tunnel restarts. Resolve the URL from a stable bootstrap document rather than compiling it
into the APK:

- the project's GitHub raw bootstrap JSON (`relay-endpoint-v2.json`: `endpoint` + `serviceInstanceId`), or
- `https://huggingface.co/datasets/vinyteles/mobilebench-index-v2/resolve/main/endpoint.json`,
  which the relay updates itself after verifying the URL reaches it:

```json
{
  "service": "mobilebench-upload-relay",
  "url": "https://example-words-here.trycloudflare.com",
  "serviceInstanceId": "mbr_0123456789abcdef0123456789abcdef",
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
A relay deployed with fresh state has a new `serviceInstanceId`, so clients bound to another
deployment treat it as a different service.

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
 "serviceInstanceId": "mbr_0123456789abcdef0123456789abcdef", "status": "ok"}
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

Create a new session with a **new secret** for each new RTL reservation, configuration, or fresh
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

- `batch`: stable APK profile ID. Version 2.3 configurations 1–9 send **101–109**, respectively. Legacy IDs **1–5** remain supported with their original v2.2 batch labels. The field name stays `batch` for compatibility; changing an APK profile creates a separate session. The request/response example below uses legacy profile 1.
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
  "assignedConfigurations": ["gemma-mixed48-gpu-replay", "lfm3-q4km-vulkan-replay"],
  "purpose": "benchmark",
  "createdAtMs": 1793896886000,
  "statusUrl": "/v1/sessions/s_c99cee52c84f3fe4",
  "checkpointsUrl": "/v1/sessions/s_c99cee52c84f3fe4/checkpoints",
  "sync": {"state": "idle", "pendingUploads": 0, "storedUploads": 0, "acceptedUploads": 0,
           "receivedAnswers": 0, "expectedAnswers": 240, "oldestPendingReceivedAtMs": null,
           "lastPublishedAtMs": null, "lastCommit": null, "lastError": null, "nextAttemptAtMs": null},
  "latestAccepted": null,
  "latestPublished": null,
  "limits": {"maxUploadBytes": 67108864, "maxUncompressedBytes": 536870912, "uploadsPerHour": 1200}
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
| `X-Checkpoint-Kind` | no | `latest` (default; per-question/periodic/stop/final) or `configuration` (after a configuration finishes, fails or is skipped) |
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
  "savedAnswers": 120,
  "requiredAnswers": 120,
  "complete": true,
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
may delete its outbox copy**. The local report in Downloads remains the user's backup.

#### Completion gate and upload states

The relay decides publication from the received `report.json`, never from headers, queue status
or client counts. For each configuration assigned to the session's `batch`, it counts the
**distinct fixture question ids** (`rag-replay.json` `questions[].id`) whose answer has
`status == "completed"`:

| `batch` | Assigned configurations (required answers) |
|---|---|
| 101–109 | the single configuration at position 1–9 of the catalog: `gemma-mixed48-gpu-replay`, `lfm3-q4km-vulkan-replay`, `lfm3-mixed4-litert-gpu-replay`, `gemma-base-mixed248-gpu-replay`, `gemma-base-q8-vulkan-replay`, `lfm3-q4km-cpu-replay`, `gemma-int8-cpu-replay`, `gemma-mixed48-cpu-replay`, `gemma-base-mixed248-cpu-replay` (120) |
| legacy 1 / 2 / 3 / 4 / 5 | configurations 1+2 / 3+4 / 5+9 / 6 / 7+8 (240, 240, 240, 120, 240) |

Results for other (imported) configurations are kept in the ZIP but never count.

- `savedAnswers` / `requiredAnswers` / `complete`: this upload's count of distinct completed
  assigned questions, the requirement (120 × assigned configurations), and whether it is met.
- `state`:
  - `stored` — partial report. Durable on the relay (fsync + SQLite), **never sent to HF**, also
    for `stopped`/`error` queue states and `configuration` snapshots of failed/skipped runs.
  - `accepted` — complete report waiting for HF publication.
  - `published` — on HF (adds `commit`, `publishedAtMs`, `commitUrl`).
  - `superseded` — another retained snapshot of the same run is newer and holds every completed
    question of this one, so this blob was released. A snapshot is never superseded by one that
    lacks any of its completed questions, so the most complete partial is always kept and late or
    older snapshots cannot roll progress back.

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
`latestPublished`. Use it for the app's sync line, e.g. "Server saved 42/120; HF publishes at 120":

- `sync.receivedAnswers` / `sync.expectedAnswers`: distinct completed assigned questions held by
  the relay for the session's newest run, and the publication requirement.
  `latestAccepted.savedAnswers` / `requiredAnswers` give the same numbers for the newest snapshot;
  `latestAccepted.configurations` remains as a per-configuration fallback.
- `sync.state`:
  - `idle`: nothing uploaded yet.
  - `collecting`: partial reports are stored on the relay only (`storedUploads`); nothing is on HF.
  - `pending`: a complete report awaits HF publication (`pendingUploads`). `lastError` and
    `nextAttemptAtMs` show relay-side HF retries; the phone does nothing.
  - `synced`: complete reports are on HF (`lastCommit`).

### GET /v1/public/sessions

```json
{"service": "mobilebench-upload-relay", "generatedAtMs": 1793897000000, "sessions": [
  {"sessionId": "s_c99cee52c84f3fe4", "repository": "vinyteles/mobilebench-relay-…",
   "repositoryUrl": "https://huggingface.co/datasets/vinyteles/mobilebench-relay-…",
   "repositoryCreated": true, "batch": 1, "batchName": "Batch 1 · Previously tested GPUs",
   "purpose": "benchmark", "createdAtMs": 1793896886000,
   "sync": {"state": "collecting", "pendingUploads": 0, "storedUploads": 1, "acceptedUploads": 37,
            "receivedAnswers": 37, "expectedAnswers": 240,
            "lastPublishedAtMs": null, "lastCommit": null, "retrying": false},
   "latest": {"uploadId": "u_…", "state": "stored", "cloudRunId": "bench120-…-8b1693f0",
              "updatedAtMs": 1793896946000, "receivedAtMs": 1793896946912, "appVersion": "2.2",
              "smokeTest": false, "completedAnswers": 37, "savedAnswers": 37, "requiredAnswers": 240,
              "complete": false,
              "queue": {"status": "running", "stage": "benchmark"},
              "configurations": [{"id": "gemma-mixed48-gpu-replay", "status": "running", "completed": 37, "total": 120}],
              "device": {"manufacturer": "samsung", "model": "SM-S931B", "socManufacturer": "Qualcomm",
                         "socModel": "SM8750", "androidRelease": "16"},
              "clientReported": true}}]}
```

The feeds expose progress counts and client-reported device fields only, never answers or
metrics. The worker also publishes the same list as `sessions.json` in
`https://huggingface.co/datasets/vinyteles/mobilebench-index-v2` (at most every 2 minutes, only
on change). That URL stays stable when the tunnel URL changes. `sessions.json` is a list of
objects with `repository`, so it works directly with
`scripts/watch-hf-benchmark.py --sessions-file`.

Device, timing and answer fields are **client-reported**. The relay checks the report format,
the fixture and the model checksums. It does not authenticate the hardware.

## Published HF layout (unchanged from APK 2.1, plus sidecars)

Only complete (`accepted`) reports are written here; the repository is created by the first one.

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
Complete routine checkpoints are coalesced, with at most one routine commit per session every
90 s after the first. Complete configuration snapshots and final checkpoints (queue status
`completed`/`stopped`/`error`) are published without the 90 s pacing. Every relay commit title
ends with `complete (<saved>/<required> answers)`.

## Limits (defaults)

| Limit | Default | Response |
|---|---|---|
| ZIP size (compressed) | 64 MiB | 413 |
| Uncompressed total / per member / members | 512 MiB / 256 MiB / 64 | 422 |
| Registrations per client IP | 20 / hour | 429 |
| New sessions service-wide | 60 / day, 500 total | 429 |
| Uploads per session / per client IP | 1200 / hour, 3600 / hour | 429 |
| Upload volume per session | 6 GiB / day | 429 |
| Retained spool (stored + unpublished) per session / total | 1 GiB / 8 GiB | 507 |
| Free disk kept on the volume | 5 GiB | 507 |
| Concurrent bodies being received / ZIP validations | 8 / 2 | 429 `server_busy` |
| Body idle / total time | 60 s / 900 s | 408 |
| Status/feed reads per client IP | 7200 / hour | 429 |

Upload rates are sized for one cumulative snapshot per completed question: a fast synthetic run
sends 120 snapshots in minutes, and a real run plus stop/final snapshots and retries stays far
below 1200/hour. 3600/hour per IP leaves room for several phones behind one carrier NAT. Status
polling every few seconds while running stays below 7200/hour. Byte quotas still bound abuse:
superseded snapshots are released, so a run normally keeps about one retained ZIP.

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
relay never lets an older snapshot replace newer progress and never discards a snapshot holding
a completed question that no newer retained snapshot has. Because snapshots are cumulative,
phones may coalesce their outbox to the newest routine checkpoint plus each configuration
snapshot during network outages.

## Examples

Public health checks need no credential:

```sh
curl -fsS https://YOUR-RELAY-HOST/v1/health
```

Use the Python client below for authenticated validation. It keeps its generated session
capability in memory rather than shell arguments or command history.

Python: `server/e2e_check.py` is a complete client for the completion gate. It registers a
`synthetic-test` session (and retries registration), sends synthetic cumulative snapshots with
1, 42 and 119 answers plus stopped and errored ones, checks they stay `stored` with no HF
repository, optionally restarts the relay, sends the 120th answer, waits for publication,
downloads the JSON+ZIP pair anonymously from HF at the commit to verify the checksums, and
checks a stale snapshot is superseded:

```sh
python -m server.e2e_check --base-url "$BASE" [--restart-command "server/deploy/restart.sh api worker"]
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
4. Upload a cumulative snapshot after every completed question. Show "saved on relay
   `receivedAnswers`/`expectedAnswers`" (202, `sync.state == "collecting"`) separately from
   "pending HF" and "synced to HF" (`GET /v1/sessions/{id}` → `sync.state`), and display
   `repositoryUrl` (it exists on HF only after the first complete report).
5. Do not add tokens or the session secret to report ZIPs. Synthetic reports must keep
   `smokeTest=true`, and debug builds should register with `purpose: "synthetic-test"`.
