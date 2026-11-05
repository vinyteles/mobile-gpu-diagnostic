# Mobile GPU benchmark

Install your assigned APK and tap **Start / resume selected**. Each configuration runs **120 questions**: 30 base questions in four wording styles. No Hugging Face account, token, or development tools are needed.

Run batches in the order below on one phone; colleagues can run different batches in parallel. Keep the APK's default configuration selections.

| APK | Configurations, in execution order | Max. answers | Model download | Estimated total, including **30 extra minutes** |
|---|---|---:|---:|---:|
| [1 · Known GPUs](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.2.0/benchmark-2.2-batch-1-gpu-known-arm64.apk) | Energy-Mobile mixed 4/8-bit · LiteRT-LM GPU<br>LFM3 Q4_K_M · Vulkan GPU | 240 | 3.13 GB | **~1 h 30 min** |
| [2 · New GPUs](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.2.0/benchmark-2.2-batch-2-gpu-new-arm64.apk) | LFM3 mixed4 · LiteRT-LM GPU<br>Gemma 4 E2B-IT mixed 2/4/8-bit · LiteRT-LM GPU | 240 | 4.03 GB | **~1 h 30 min–2 h** † |
| [3 · Gemma](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.2.0/benchmark-2.2-batch-3-gemma-arm64.apk) | Gemma 4 E2B-IT Q8_0 · Vulkan GPU, if RAM permits<br>Gemma 4 E2B-IT mixed 2/4/8-bit · LiteRT-LM CPU | Up to 240 | Up to 7.64 GB | **~2 h 30 min–3 h 30 min** † |
| [4 · LFM CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.2.0/benchmark-2.2-batch-4-lfm-cpu-arm64.apk) | LFM3 Q4_K_M · llama.cpp CPU | 120 | 1.10 GB | **~2 h** |
| [5 · Energy CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.2.0/benchmark-2.2-batch-5-energy-cpu-arm64.apk) | Energy-Mobile int8 · LiteRT-LM CPU<br>Energy-Mobile mixed 4/8-bit · LiteRT-LM CPU | 240 | 5.00 GB | **~2 h 10 min** |

Times are planning estimates for a Galaxy S25 FE-class phone. **The extra 30 minutes is already included once per APK** for downloads, setup and delays. † Batches 2 and 3 are provisional budgets, not measured phone durations. Slow downloads, heat or initialization can take longer. [Estimate basis](docs/TIMING.md).

Downloads are model weights on a fresh phone; each APK adds about 58 MB, and runtime caches need extra storage. Batch 3's Q8 configuration is skipped if available RAM is insufficient. **Batches 3 and 5 may need more than one two-hour RTL reservation.**

<a id="start-a-session"></a>

1. Use an ARM64 phone with Android 12 or later, or reserve a phone through Samsung RTL using your own account.
2. Install the assigned APK, open it, and tap **Start / resume selected**. The app downloads the models and runs the questions automatically.
3. Keep the app in the foreground; it keeps the screen awake. Expand a configuration to see its metrics.
4. Before ending the reservation, tap **Stop** if it is still running and wait for **Hugging Face synced**. Use **Report upload status → Copy results link** to share your run, together with the batch, phone model and RAM.

**How results are stored:** the cellphone first sends its report checkpoints to our server on **h100-eliomar**. The server uses an **HF access key stored only on the server** to upload them to a separate public Hugging Face dataset for each session. Find the datasets in the **[HF session index](https://huggingface.co/datasets/vinyteles/mobilebench-index)**. Reports include answers, retrieved context, references, timing/memory metrics and device information. **Received by server** means queued; **Hugging Face synced** confirms publication.

If time runs out, download the session's latest report ZIP, install the unfinished batch on the next phone, choose **Import previous report ZIP**, and resume. Completed answers are retained. On the same phone, updating to another batch APK preserves models and answers. If you reuse the same installation/run/batch for a new reservation, choose **Report upload status → New upload session** while stopped.

[All APKs and checksums](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.2.0) · [Technical details](docs/TECHNICAL.md) · [Validation](VALIDATION.md)
