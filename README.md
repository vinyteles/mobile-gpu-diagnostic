# Mobile GPU benchmark

Install your assigned APK and tap **Start / resume**. Each APK runs **one configuration with 120 questions**: 30 base questions in four wording styles. No Hugging Face account, token, or development tools are needed.

Use one APK per two-hour RTL reservation. Run configurations in the order below on one phone; colleagues can run different configurations in parallel. The known-working GPU paths come first.

| APK | Configuration | Max. answers | Model download | Estimated total, including **30 extra minutes** |
|---|---|---:|---:|---:|
| [1 · Energy mixed GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-1-energy-mixed48-gpu-arm64.apk) | Energy-Mobile mixed 4/8-bit · LiteRT-LM GPU | 120 | 2.03 GB | **~1 h** |
| [2 · LFM Vulkan GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-2-lfm-vulkan-gpu-arm64.apk) | LFM3 Q4_K_M · Vulkan GPU | 120 | 1.10 GB | **~1 h 10 min** |
| [3 · LFM LiteRT GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-3-lfm-litert-gpu-arm64.apk) | LFM3 mixed4 · LiteRT-LM GPU | 120 | 1.44 GB | **~1 h–1 h 15 min †** |
| [4 · Gemma mobile GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-4-gemma-mobile-gpu-arm64.apk) | Gemma 4 E2B-IT mixed 2/4/8-bit · LiteRT-LM GPU | 120 | 2.59 GB | **~1 h–1 h 15 min †** |
| [5 · Gemma Q8 GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-5-gemma-q8-gpu-arm64.apk) | Gemma 4 E2B-IT Q8_0 · Vulkan GPU, if RAM permits | Up to 120 | Up to 5.05 GB | **~1 h 30 min–2 h †** |
| [6 · LFM CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-6-lfm-cpu-arm64.apk) | LFM3 Q4_K_M · llama.cpp CPU | 120 | 1.10 GB | **~2 h** |
| [7 · Energy int8 CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-7-energy-int8-cpu-arm64.apk) | Energy-Mobile int8 · LiteRT-LM CPU | 120 | 2.97 GB | **~1 h 30 min** |
| [8 · Energy mixed CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-8-energy-mixed48-cpu-arm64.apk) | Energy-Mobile mixed 4/8-bit · LiteRT-LM CPU | 120 | 2.03 GB | **~1 h 10 min** |
| [9 · Gemma mobile CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.3.0/benchmark-2.3-config-9-gemma-mobile-cpu-arm64.apk) | Gemma 4 E2B-IT mixed 2/4/8-bit · LiteRT-LM CPU | 120 | 2.59 GB | **~1 h 30 min–2 h †** |

Times are planning estimates for a Galaxy S25 FE-class phone. **The extra 30 minutes is already included per APK** for downloads, setup and delays. † Configurations 3, 4, 5 and 9 have provisional budgets, not measured phone durations. Slow downloads, heat or initialization can take longer. Configurations 5, 6 and 9 have little margin within two hours; use checkpoint/resume if needed. [Estimate basis](docs/TIMING.md).

Downloads are model weights on a fresh phone; each APK adds about 58 MB, and runtime caches need extra storage. Configuration 5 is skipped if available RAM is insufficient.

**Dataset and test sample**

We use [UFGCEMIGONA/benchmark_mobile](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile), pinned to [revision `5a5aa32`](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile/tree/5a5aa32ae4087481be828e071aff169827ec2993). It contains **2,508 Portuguese prompts from 627 base questions**, each with four wording variants. Our fixed evaluation sample contains **30 base questions × 4 variants = 120 prompts per configuration**. All nine configurations receive the same sample in the same order, allowing up to **1,080 generated answers** across the complete test battery.

| Source category | Full dataset prompts | Selected base questions | Test prompts per configuration |
|---|---:|---:|---:|
| Regulatory standards (`normas_regulamentadoras`) | 1,536 | 18 | 72 |
| Internal documents (`documentos_internos`) | 972 | 12 | 48 |
| **Total** | **2,508** | **30** | **120** |

- **Wording styles:** 30 `clean`, 30 `technical`, 30 `operational`, and 30 `lay` prompts. Each base question appears once in every style, so these are related variants of 30 questions.
- **Question types:** 32 factual, 32 conditional, 28 procedural, and 28 requiring multiple pieces of evidence (`multievidence`).
- **Selection:** fixed quotas by source category and question type, with deterministic selection using seed `mobile-bench-120-v2`. Selection does not use model scores or answer quality. Both source files are published under Hugging Face's `train` split; the APK uses the selected subset for evaluation and performs no training.

Each prompt includes its previously retrieved MobileRAG+ context. The phone benchmarks answer generation; retrieval is not rerun. Reference answers and evidence are included for evaluation and are never sent to the model.

**Every sample is traceable.** The public [sample file](app/src/main/assets/rag-replay.json), also included in every report ZIP, preserves the original question and base-question IDs, source category/document, reference answer and evidence, exact retrieved passages with document/chunk identifiers, prompt hashes, dataset revision and input-file checksums. Generated answers carry the question ID and prompt hash, connecting each result to its exact input. The [selection script](scripts/build-balanced-replay.py) documents how the sample was built.

<a id="start-a-session"></a>

1. Use an ARM64 phone with Android 12 or later, or reserve a phone through Samsung RTL using your own account.
2. Install the assigned APK, open it, and tap **Start / resume**. The app downloads its model and runs the questions automatically.
3. Keep the app visible in the foreground; it prevents normal screen timeout while visible. Avoid locking the phone or switching apps. Expand the configuration to see its metrics.
4. Before ending the reservation, tap **Stop** if it is still running and wait for **Hugging Face synced**. Use **Report upload status → Copy results link** to share your run, together with the configuration number, phone model and RAM.

**How results are stored:** the cellphone first sends its report checkpoints to our upload server. The server uses an **HF access key stored only on the server** to upload them to a separate public Hugging Face dataset for each session. Find the datasets in the **[HF session index](https://huggingface.co/datasets/vinyteles/mobilebench-index)**. Reports include answers, retrieved context, references, timing/memory metrics and device information. **Received by server** means queued; **Hugging Face synced** confirms publication.

**Screen sleep, interruptions and recovery**

These benchmark APKs request Android's [keep-screen-on behavior](https://developer.android.com/develop/background-work/background-tasks/awake/screen-on) while their window is visible, so normal inactivity should not turn off the screen. Manual locking, switching apps, system restrictions, crashes or an RTL session ending can still interrupt the benchmark. **Continuous execution with the screen off is not guaranteed**; Android can [restrict background CPU and network activity](https://developer.android.com/training/monitoring-device-state/doze-standby).

- **Same phone, app data intact:** wake/unlock the phone and reopen the app. If progress is still advancing, let it continue. If the run has stopped, tap **Start / resume** or **Resume unfinished questions**, depending on the screen. After the app process closes, resuming inference requires this tap. Use Resume to continue the existing run; **Start a new run** begins a separate run.
- **What is retained:** every completed answer is saved locally before the next question starts. Resume skips saved completed answers. An interrupted question is retried from its beginning, with the model reloaded after process interruption; partial output and the in-memory KV cache are not used to continue token generation. For example, if questions 1–37 were saved and question 38 was interrupted, resume keeps 1–37 and retries 38.
- **New phone, cleared app data, or expired RTL reservation:** download the session's latest available report ZIP from HF or use a previously saved ZIP. Install the same configuration APK, choose **Import previous report ZIP**, then resume. Recovery retains completed answers present in that ZIP. ZIPs are queued about every minute and on stop/completion, so a cloud report can lag behind local progress; answers absent from the recovered ZIP must run again. Confirm **Hugging Face synced** and keep a ZIP backup before ending a reservation when possible.

On the same phone, installing another configuration APK updates the same app and preserves models and answers; finish or stop before switching. Reports can include earlier configurations, but each APK runs only its assigned configuration. If you reuse the same installation/run/configuration for a new reservation, choose **Report upload status → New upload session** while stopped; this changes the upload destination and keeps saved answers.

[All APKs and checksums](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.3.0) · [Technical details](docs/TECHNICAL.md) · [Validation](VALIDATION.md)
