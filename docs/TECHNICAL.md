# Technical reference

For the APK assignments and quick start, see the [README](../README.md).

## Checkpoints and recovery

Every completed answer is saved internally. The worker then notifies the controller, which saves a cumulative ZIP to Downloads and queues it before acknowledging the next question. Enqueue schedules an immediate asynchronous upload attempt. Additional snapshots are queued about every minute while waiting, after each configuration, and when stopping. Download preparation progress is included. Network failures never require waiting for a remote acknowledgement before the next question. Offline/busy uploads coalesce cumulative snapshots without discarding completed answers. Total wall time includes per-question export overhead; inference timing fields retain their existing definitions.

The relay acknowledges receipt only after durable storage. **Server saved N / 120 answers** means partial progress is on server disk. Incomplete reports are retained there, including stopped/error runs; they are not automatically published to HF. The server validates that all 120 distinct question IDs for the assigned configuration are completed before publishing. **Hugging Face synced** means publication has completed. HF failures retry after app disconnects, and hash-matched JSON/ZIP files are committed together. Public index metadata can show progress before report publication.

After completion, each public HF session repository contains:

- `latest.json`: latest progress, device, batch, completed counts and checksum.
- `runs/<run-id>/progress.json` and `runs/<run-id>/latest.zip`: cumulative report.
- `runs/<run-id>/configurations/<configuration>.zip`: cumulative configuration snapshot.

Wire field `batch` uses IDs 101–109 for configuration APKs 1–9. IDs 1–5 keep their original v2.2 batch meanings, so upgrades create separate upload sessions without relabelling historical data. Reports remain cumulative when continuing the same run across APK updates or importing a report.

Public reports contain questions, retrieved passages, reference answers, generated answers, metrics and device metadata. The phone's scoped capability is encrypted with Android Keystore and excluded from reports. Version 2.4 uses separate upload preferences, session files and outbox storage, preserving the old files as backup. On launch, an existing current run is re-exported to the replacement service without starting inference. Synthetic transport tests are explicitly labelled and must not be counted as model-quality results.

For another reservation, transfer a downloaded ZIP to the next device, use **Import previous report ZIP**, and resume the unfinished configuration. An operator can recover a stored partial ZIP from server disk; incomplete reports do not yet have an HF ZIP. Original answer/device provenance is preserved. A new device needs model downloads again. Keep a local ZIP backup when possible, especially if uploads are pending.

## Server and monitoring

The upload relay runs on a separate server. See [server/API.md](../server/API.md) and [server/DEPLOYMENT.md](../server/DEPLOYMENT.md) for its protocol, quotas, tests, credential renewal and operator commands. Its API, HF worker and tunnel run separately from the Claude implementation agent. Version 2.4 uses a replacement deployment and a new `mobilebench-index-v2` discovery dataset; the old server cannot overwrite its endpoint. Public configuration does not identify the host.

The initial HTTPS ingress uses a **temporary Cloudflare quick tunnel**. The server publishes its current verified URL to the HF index. The APK reads `relay-endpoint-v2.json` from this GitHub repository, then follows the HF discovery document while checking the pinned service identity before sending a session capability. Tunnel changes can therefore be discovered without rebuilding APKs. If the server, tunnel or HF index is unavailable, local checkpoints remain queued and retry. The server credential currently expires November 26, 2026 and must be renewed by the operator; participants do not manage it.

For a local archive of one or several public session reports, the optional watcher verifies ZIP checksums at immutable HF commits:

```sh
python -m pip install -r scripts/requirements-monitor.txt
python scripts/watch-hf-benchmark.py \
  --repository vinyteles/SESSION-DATASET-NAME \
  --hours 24 --output reports/hf-monitor
```

Repeat `--repository` for several sessions or use `--sessions-file` with a JSON list of repository names/manifest objects. Public monitoring requires no HF login. It records progress and stale updates locally and does not send chat notifications. Phone/server uploads work independently of the watcher.

Assign configurations to colleagues to avoid accidental duplicate work. Keep timings separate by **phone, SoC, RAM, Android version, model hash, runtime version, prompt hash, thermals and engine reuse**. Parallel sessions increase coverage; different hardware is not directly comparable. The four variants of one base question are related observations.

The older `scripts/create-hf-session.py` helper remains available for manually managed datasets; the 2.4 APKs use automatic relay sessions.

## Protocol

- 4096 total context tokens; at most 512 generated tokens; greedy sampling; thinking and speculative decoding off; CPU threads 4.
- Same English system instruction requesting concise Brazilian Portuguese answers and citations, with model-specific tokenizers/chat templates.
- 72 regulatory-standard and 48 internal-document prompts; 32 factual, 32 conditional, 28 procedural and 28 multievidence.
- Frozen source dataset: `UFGCEMIGONA/benchmark_mobile`, revision `5a5aa32ae4087481be828e071aff169827ec2993`. The fixture records source and tokenizer checksums and deterministic selection rules.
- Contexts are saved desktop MobileRAG+ retrieval/SCR outputs from `cemig-mobile`. This APK measures **generation only**, not phone retrieval or embedding speed, and does not establish parity with the mobile Q8 embedding/int8 SCR path.
- All retrieved passages fit the common budget: maximum formatted inputs were Energy 1700, LFM 1739, original Gemma 1674 tokens. No passages were dropped; 512 output plus 128 extra template tokens are reserved.
- References/evidence are evaluation-only and never sent to inference. Citation checks are structural, not semantic correctness scores. An LLM judge is a separate evaluation step.

The established GPU paths were successful in previous phone runs, but runtime/settings changes still need validation. Requesting GPU does not mean every native operator uses GPU. There is no application-level CPU fallback; inspect native placement logs.

LiteRT-LM is pinned to 0.18.1; llama.cpp to `5851a8e505f4d5fbb183f2847698eb6155d176b7`. Model URLs, sizes and hashes are in `ModelCatalog.kt`.

One engine is reused per configuration/process session, except **LFM LiteRT**, which creates/closes an engine per question because of the [recurrent-state reuse issue](https://github.com/google-ai-edge/LiteRT-LM/issues/3165). `questionMs` excludes engine loading/closing; `endToEndMs` includes them. For shared engines, the first question bears load time. `endToFirstTextMs` includes applicable loading. LFM LiteRT memory while loaded is in `memoryBeforeEngineClose`; its `memoryAfter` is after unloading. Android process/system memory is reported, not dedicated GPU VRAM. Raw unexpected thinking channels are retained.

Per-question generation is limited to three minutes, lack of worker progress to six minutes, and each configuration to six hours. Worker failures retain earlier answers and advance the queue. Fixed GPU-first order, periodic checkpointing and uploads can affect thermal/wall-time comparisons.

## Build from source

Requirements: JDK 17, Android SDK 36, NDK 27.1.12297006, CMake 3.22.1, and Git. Install those SDK components, set `ANDROID_SDK_ROOT`, and put its `cmake/3.22.1/bin` directory on PATH.

```sh
./scripts/prepare-native-deps.sh
./scripts/build-config-apks.sh
```

The nine ARM64 APKs and checksums appear in `artifacts/configs-2.4/`. Build one configuration with `./gradlew -PbenchmarkProfile=1 :app:assembleRelease`. The build argument is 1–9. Each APK shows and runs only its assigned configuration, ignoring old checkbox selections. A normal debug build supports x86_64 for emulator tests as well as ARM64.

```sh
./gradlew -PbenchmarkProfile=1 :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Published configuration APKs share one signing key. A locally built APK uses your local debug key and may not update a published installation. No signing keys or account tokens are committed.

The 120-question fixture is bundled. Regenerating it requires the original local benchmark retrieval outputs and tokenizer inputs; `scripts/build-balanced-replay.py --help` lists those paths. Model weights, native dependency checkouts, build caches and personal report archives are excluded from Git.

See [VALIDATION.md](../VALIDATION.md) for verification and the remaining physical GPU checks.
