# Mobile GPU benchmark

Standalone Android benchmark for Energy-Mobile, LFM3 1.7B, and original Gemma 4 E2B-IT. Compare generation on CPU, LiteRT-LM GPU, and llama.cpp Vulkan using the same saved retrieval contexts. Five APK presets make it easier to divide the work across two-hour Samsung RTL sessions and colleagues.

[Download APKs](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.1.0) · [Register a session](https://github.com/vinyteles/mobile-gpu-diagnostic/issues/new/choose) · [HF project index](https://huggingface.co/datasets/vinyteles/mobile-benchmark-results)

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

The APKs share the same app identity and signing key. Installing another published batch APK updates the app, selects that batch, and preserves downloaded models, answers and HF setup on the same phone. A fresh RTL device needs downloads again. Runtime caches need additional disk space; allow 25 GB free if keeping every model and cache together.

A batch is **not guaranteed to finish in two hours**. The new GPU configurations need physical-device validation. Q8 is experimental: its 5.05 GB GGUF is skipped if available RAM is below model size plus 768 MiB; passing that check does not guarantee driver compatibility.

## Start a session

1. Reserve the phone and install the relevant APK.
2. Create a **public HF dataset repository for this RTL reservation**, under your own account. For example, use a name containing the date, phone and batch. You can use the HF website or the helper below.
3. Create an HF fine-grained **write token scoped only to that repository**. In the app, open **Hugging Face upload settings**, enter `owner/repository`, paste the token and tap **Connect**. Do not share account credentials among colleagues. Private repositories are also supported when desired.
4. Tap **Start / resume selected**. The app downloads and verifies the required models, then runs the preselected configurations. Expand cards for metrics.
5. Share the public session URL through a GitHub session issue or a manifest in `sessions/`. We can then monitor that session without write access.

Keep the app in the foreground. It sets Android's keep-screen-on flag, but cannot extend an RTL reservation or guarantee continued execution after Android closes/backgrounds it. Each reservation still needs to be started by a participant; five APKs do not create an unattended chain of reservations.

## Checkpoints and recovery

The app saves each completed answer internally. It also saves a ZIP to Downloads and queues an HF checkpoint every minute while running, after each configuration, and when stopping. During downloads it publishes periodic preparation progress too. Uploads run on a separate worker; network failures retain a local outbox and retry while the app is open. Check the displayed last-sync state before ending a session.

Each HF session repository contains:

- `latest.json`: latest progress, device, batch, completed counts, and report checksum.
- `runs/<run-id>/progress.json`: per-run progress.
- `runs/<run-id>/latest.zip`: cumulative report, with all completed answers and partial diagnostics.
- `runs/<run-id>/configurations/<configuration>.zip`: cumulative snapshot after that configuration.
- `connections/`: connection checks with installation IDs and app version; no tokens.

Public uploads include the questions, retrieved passages, reference answers, generated answers, metrics, and device metadata. Credentials are encrypted with Android Keystore and excluded from source, APKs, and reports.

To continue after a reservation ends, download its latest ZIP, install the next APK on the new device, and use **Import previous report ZIP**. Matching completed answers are retained. The imported report keeps original device/engine-session provenance; new answers record the new device. Setup a new HF session repository for that reservation.

## Parallel work and monitoring

Install Python dependencies and log in to create a repository:

```sh
python -m pip install -r scripts/requirements-monitor.txt
hf auth login
python scripts/create-hf-session.py --batch 1 --device 'Galaxy S25 FE / Exynos 2400' --manifest reports/my-session.json
```

The helper creates a public session repository and prints its URL. It does not mint a token for the phone; create the repository-scoped token through HF settings. `--private` creates a private session instead.

Anyone can monitor public sessions without an HF login:

```sh
python scripts/watch-hf-benchmark.py \
  --repository alice/session-one \
  --repository bob/session-two \
  --hours 24 --output reports/hf-monitor
```

Alternatively, use `--sessions-file sessions.local.json` containing a JSON list of repository names or session manifest objects. The watcher reads progress and ZIP at the same immutable Hub commit, verifies the ZIP checksum, and saves per-session progress/events/reports locally. It labels stale updates, ignores synthetic runs by default, and does not send chat notifications. The watcher needs its computer running; phone uploads work independently of it.

Assign batches rather than accidentally repeating the same work. Keep timings separate by **phone, SoC, RAM, Android version, model hash, runtime version, prompt hash, thermals, and engine reuse**. Parallel sessions increase coverage; they do not make different hardware directly comparable. The four variants of a base question are related observations.

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

The five ARM64 APKs and checksums appear in `artifacts/batches-2.1/`. Build one preset with `./gradlew -PbenchmarkProfile=1 :app:assembleRelease`. A normal debug build supports x86_64 for emulator tests as well as ARM64.

```sh
./gradlew -PbenchmarkProfile=1 :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
```

Published batch APKs share one signing key. A locally built APK uses your local debug key and may not update a published installation. No signing keys or account tokens are committed.

The 120-question fixture is bundled. Regenerating it requires the original local benchmark retrieval outputs and tokenizer inputs; `scripts/build-balanced-replay.py --help` lists those paths. Model weights, native dependency checkouts, build caches and personal report archives are excluded from Git.

See `VALIDATION.md` for verification and the remaining physical GPU checks.
