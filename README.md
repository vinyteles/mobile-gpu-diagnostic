# Mobile GPU benchmark

Standalone Android benchmark for Energy-Mobile, LFM3 1.7B, and original Gemma 4 E2B-IT. Compare generation on CPU, LiteRT-LM GPU, and llama.cpp Vulkan using the same saved retrieval contexts. Five APK presets make it easier to divide the work across two-hour Samsung RTL sessions and colleagues.

[Download APKs](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.2.0) · [Register a session](https://github.com/vinyteles/mobile-gpu-diagnostic/issues/new/choose) · [HF project index](https://huggingface.co/datasets/vinyteles/mobile-benchmark-results)

## The five APKs

Run in this order on one device to attempt all GPUs before CPU-only batches. Colleagues can run different batches in parallel on separate devices.

| APK | Configurations, in order | Maximum answers | Model downloads on a fresh device |
|---|---|---:|---:|
| 1 · Previously tested GPUs | Energy mixed 4/8-bit LiteRT GPU; LFM3 Q4_K_M Vulkan GPU | 240 | 3.13 GB |
| 2 · New LiteRT GPUs | LFM3 mixed4 LiteRT GPU; original Gemma mobile LiteRT GPU | 240 | 4.03 GB |
| 3 · Original Gemma | Gemma Q8_0 Vulkan GPU, if memory permits; Gemma mobile LiteRT CPU | 240 | Up to 7.64 GB |
| 4 · LFM CPU | LFM3 Q4_K_M llama.cpp CPU | 120 | 1.10 GB |
| 5 · Energy CPU | Energy int8 LiteRT CPU; Energy mixed 4/8-bit LiteRT CPU | 240 | 5.00 GB |

Every configuration has **120 prompts**: 30 base questions × `clean`, `technical`, `operational`, `lay`. A complete eligible suite has 1,080 answers. Original Gemma's mobile bundle uses mixed **2/4/8-bit**, which is different from Energy's mixed 4/8-bit.

The APKs share the same app identity and signing key. Installing another published batch APK updates the app, selects that batch, and preserves downloaded models and answers on the same phone. Each batch/run gets its own automatic upload session. A fresh RTL device needs downloads again. Runtime caches need additional disk space; allow 25 GB free if keeping every model and cache together.

A batch is **not guaranteed to finish in two hours**. The new GPU configurations need physical-device validation. Q8 is experimental: its 5.05 GB GGUF is skipped if available RAM is below model size plus 768 MiB; passing that check does not guarantee driver compatibility.

## Start a session

1. Download the assigned batch APK from the release and install it on an ARM64 phone with Android 12 or later, or a Samsung RTL phone.
2. Open the app and tap **Start / resume selected**. Keep the batch's preselected configurations. Models download and verify automatically; questions, contexts and references are included.
3. Keep the app in the foreground. Expand configuration cards for metrics. The app keeps the screen awake while foreground; it cannot extend an RTL reservation.
4. Open **Report upload status** to see progress or copy the public HF results link. Wait for **Hugging Face synced** before ending the session when possible.

**No HF account, token, repository creation, Python, or Android Studio is needed to run the published APKs.** The server on h100-eliomar creates a public dataset repository automatically and publishes the reports. The app creates a random capability for its own session; the HF account credential stays on the server.

All sessions appear in the [public HF index](https://huggingface.co/datasets/vinyteles/mobilebench-index). Participants can also register a [session issue](https://github.com/vinyteles/mobile-gpu-diagnostic/issues/new/choose) to coordinate batch/hardware assignments. Share the public results URL, batch, phone model, RAM and Android version. Each participant uses their own Samsung account/reservation when using RTL.

Fresh installations, new runs and batch changes create separate upload sessions. Resuming the same run/batch preserves its session, including after an app restart. If the same installed app/run/batch is reused for another reservation, stop the benchmark and choose **Report upload status → New upload session** to separate it without discarding answers.

## Checkpoints and recovery

Every completed answer is saved internally. A ZIP is saved to Downloads and queued locally about every minute, after each configuration, and when stopping. Download preparation progress is included. Network work runs on a separate thread and retries automatically while the app is open.

The relay acknowledges receipt only after durable storage. **Received by server** means the checkpoint is safe in its queue; **Hugging Face synced** means publication has completed. The server retries HF outages after app disconnects and keeps hash-matched JSON/ZIP files together in one commit. Routine HF updates may be paced to about 90 seconds; final/configuration snapshots receive priority. Neither upload interval is a guaranteed maximum when connectivity fails.

Each public HF session repository contains:

- `latest.json`: latest progress, device, batch, completed counts and checksum.
- `runs/<run-id>/progress.json` and `runs/<run-id>/latest.zip`: cumulative report.
- `runs/<run-id>/configurations/<configuration>.zip`: cumulative configuration snapshot.

Public reports contain questions, retrieved passages, reference answers, generated answers, metrics and device metadata. The phone's scoped capability is encrypted with Android Keystore and excluded from reports. Synthetic transport tests are explicitly labelled and must not be counted as model-quality results.

If a reservation ends, download its latest ZIP to the next device, use **Import previous report ZIP**, and resume the unfinished batch. Original answer/device provenance is preserved. A new device needs model downloads again. Keep a local ZIP backup when possible, especially if uploads are pending.

## Server and monitoring

The CPU-only upload relay is deployed on h100-eliomar. See [server/API.md](server/API.md) and [server/DEPLOYMENT.md](server/DEPLOYMENT.md) for its protocol, quotas, tests, credential renewal and operator commands. Its API, HF worker and tunnel run separately from the Claude implementation agent.

The initial HTTPS ingress uses a **temporary Cloudflare quick tunnel**. The server publishes its current verified URL to the HF index. The APK reads `relay-endpoint.json` from this GitHub repository, then follows the HF discovery document while checking the pinned service identity before sending a session capability. Tunnel changes can therefore be discovered without rebuilding APKs. If the VM, tunnel or HF index is unavailable, local checkpoints remain queued and retry. The server credential currently expires November 26, 2026 and must be renewed by the operator; participants do not manage it.

For a local archive of one or several public session reports, the optional watcher verifies ZIP checksums at immutable HF commits:

```sh
python -m pip install -r scripts/requirements-monitor.txt
python scripts/watch-hf-benchmark.py \
  --repository vinyteles/SESSION-DATASET-NAME \
  --hours 24 --output reports/hf-monitor
```

Repeat `--repository` for several sessions or use `--sessions-file` with a JSON list of repository names/manifest objects. Public monitoring requires no HF login. It records progress and stale updates locally and does not send chat notifications. Phone/server uploads work independently of the watcher.

Assign batches to colleagues to avoid accidental duplicate work. Keep timings separate by **phone, SoC, RAM, Android version, model hash, runtime version, prompt hash, thermals and engine reuse**. Parallel sessions increase coverage; different hardware is not directly comparable. The four variants of one base question are related observations.

The older `scripts/create-hf-session.py` helper remains available for manually managed datasets; the 2.2 APKs use automatic relay sessions.

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
./scripts/build-batch-apks.sh
```

The five ARM64 APKs and checksums appear in `artifacts/batches-2.2/`. Build one preset with `./gradlew -PbenchmarkProfile=1 :app:assembleRelease`. A normal debug build supports x86_64 for emulator tests as well as ARM64.

```sh
./gradlew -PbenchmarkProfile=1 :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Published batch APKs share one signing key. A locally built APK uses your local debug key and may not update a published installation. No signing keys or account tokens are committed.

The 120-question fixture is bundled. Regenerating it requires the original local benchmark retrieval outputs and tokenizer inputs; `scripts/build-balanced-replay.py --help` lists those paths. Model weights, native dependency checkouts, build caches and personal report archives are excluded from Git.

See `VALIDATION.md` for verification and the remaining physical GPU checks.
