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

<a id="start-a-session"></a>

1. Use an ARM64 phone with Android 12 or later, or reserve a phone through Samsung RTL using your own account.
2. Install the assigned APK, open it, and tap **Start / resume**. The app downloads its model and runs the questions automatically.
3. Keep the app in the foreground; it keeps the screen awake. Expand the configuration to see its metrics.
4. Before ending the reservation, tap **Stop** if it is still running and wait for **Hugging Face synced**. Use **Report upload status → Copy results link** to share your run, together with the configuration number, phone model and RAM.

**How results are stored:** the cellphone first sends its report checkpoints to our server on **h100-eliomar**. The server uses an **HF access key stored only on the server** to upload them to a separate public Hugging Face dataset for each session. Find the datasets in the **[HF session index](https://huggingface.co/datasets/vinyteles/mobilebench-index)**. Reports include answers, retrieved context, references, timing/memory metrics and device information. **Received by server** means queued; **Hugging Face synced** confirms publication.

If time runs out, download the session's latest report ZIP, install the same configuration APK on the next phone, choose **Import previous report ZIP**, and resume. Completed answers are retained. On the same phone, installing another configuration APK updates the same app and preserves models and answers; finish or stop before switching. Reports can include earlier configurations, but each APK runs only its assigned configuration. If you reuse the same installation/run/configuration for a new reservation, choose **Report upload status → New upload session** while stopped.

[All APKs and checksums](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.3.0) · [Technical details](docs/TECHNICAL.md) · [Validation](VALIDATION.md)
