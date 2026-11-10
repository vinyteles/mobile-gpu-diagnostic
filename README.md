# Mobile RAG benchmark

The new [v3 configurations 17–24](docs/BENCHMARK-V3.md) preserve Energy/LFM tool flows and add Gemma flag-based retrieval, with candidate/context retrieval metrics and QA evaluation.

Install your assigned APK and tap **Start / resume**. Each APK runs **one configuration on 120 questions**. The model decides when to search; retrieval and generation run on the phone. No Hugging Face account, token, or development tools are needed.

Use one APK per two-hour Samsung RTL reservation. All cases request **Vulkan GPU for LFM3**; query embeddings use CPU. Run A first on a new phone type, then the other cases. Colleagues can run different APKs in parallel. The base Vulkan path worked in earlier replay tests; the complete live-retrieval combinations still need physical-device validation.

| APK | Configuration | Max. answers | Fresh download | Estimated total, including **30 extra minutes** |
|---|---|---:|---:|---:|
| [A](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-A-arm64.apk) | LFM3 base Q4_K_M · MobileRAG+ · GPU | 120 | 1.94 GB | ~1 h 30 min–2 h † |
| [B](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-B-arm64.apk) | LFM3 fine-tuned Q4_K_M · MobileRAG+ · GPU | 120 | 1.94 GB | ~1 h 30 min–2 h † |
| [C](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-C-arm64.apk) | LFM3 base Q4_K_M · F6 · GPU | 120 | 1.48 GB | ~1 h 15 min–1 h 45 min † |
| [D](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-D-arm64.apk) | LFM3 fine-tuned Q4_K_M · F6 · GPU | 120 | 1.48 GB | ~1 h 15 min–1 h 45 min † |
| [E](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-E-arm64.apk) | LFM3 fine-tuned Q4_0 · MobileRAG+ · GPU | 120 | 1.89 GB | ~1 h 30 min–2 h † |
| [F](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-F-arm64.apk) | LFM3 fine-tuned Q4_0 · F6 · GPU | 120 | 1.43 GB | ~1 h 15 min–1 h 45 min † |

† These are provisional planning budgets, not measured Samsung durations. **The extra 30 minutes is already included.** Downloads, search decisions, heat and device freezes can exceed two hours; save the checkpoint and resume in another reservation if needed. Downloads include model weights, query embedders and retrieval data; the APK and working space are additional. Verified files on the same installation are reused.

**All six cases require new runs, including A:** 720 attempted answers in total. Earlier config 2 used frozen contexts and cannot serve as the live tool-calling baseline. [Earlier replay APKs](docs/REPLAY-BENCHMARK.md) remain available separately.

**Dataset and traceability**

The source is [UFGCEMIGONA/benchmark_mobile](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile), pinned to [revision `5a5aa32`](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile/tree/5a5aa32ae4087481be828e071aff169827ec2993). The full dataset has 2,508 Portuguese prompts from 627 base questions. Our unchanged sample is **30 base questions × four wording styles = 120 prompts per configuration**.

| Source category | Full dataset prompts | Selected base questions | Test prompts |
|---|---:|---:|---:|
| Regulatory standards | 1,536 | 18 | 72 |
| Internal CEMIG documents | 972 | 12 | 48 |
| **Total** | **2,508** | **30** | **120** |

Every case receives 30 `clean`, 30 `technical`, 30 `operational`, and 30 `lay` prompts, in the same order. Question types are 32 factual, 32 conditional, 28 procedural and 28 requiring multiple pieces of evidence. These are related variants of 30 questions. Deterministic selection uses seed `mobile-bench-120-v2`, independently of model quality. The app performs no training.

The [sample file](app/src/main/assets/tool-questions.json) preserves question/base-question IDs, categories, references, dataset pins and the shared tool protocol. **References and reference evidence never enter inference.** Reports preserve model-generated queries, retrieved and supplied passages, conversation prompts, model hashes and timings. Every answer is traceable to its actual input.

MobileRAG+ includes the benchmark's internal documents. [F6's published corpus](https://huggingface.co/datasets/juniorrios/cemig-nr-retrieval-assets) contains public NRs and manuals, **not internal CEMIG documents**. All 120 questions remain in each case; report regulatory/internal results separately, alongside overall scores and the four wording styles.

**Running and resuming**

1. Use an ARM64 Android 12+ phone with Vulkan support. Install one assigned APK.
2. Tap **Start / resume**. The first screen shows each download and verification. The next shows question progress; expand the configuration for metrics.
3. Keep the app visible and the phone plugged in. It prevents ordinary screen timeout while visible. Manually locking the phone, changing apps, an OS shutdown or a freeze can still interrupt it.
4. Answers are checkpointed after each completed question. Reopen and tap **Start / resume** to retain completed answers and retry the unfinished question. An interrupted question restarts from its beginning, including its tool calls.
5. Save/share the report ZIP before RTL ends, even if uploads appear healthy. Import it in another session/device to resume. A full phone freeze can prevent export until recovery; received checkpoints remain on the server.

Installing another case APK updates the same app and preserves verified downloads and results. Finish or stop before switching. Live tool runs and historical replay runs have separate saved queues and cannot import each other's reports. Cross-device resumes retain per-answer device provenance; compare different devices separately when analyzing timing.

**Result storage**

The **cellphone sends a cumulative checkpoint to our report server after every completed question**. The server durably keeps received questions. When the session's assigned case reaches all 120 completed attempts, the server uses its own Hugging Face credential to publish a public dataset. There is **no HF token in the APK or source repository**.

Published datasets appear in [vinyteles/mobilebench-index-v2](https://huggingface.co/datasets/vinyteles/mobilebench-index-v2). Each upload session has its own dataset repository; colleagues running the same case get separate sessions. Partial runs stay on the server until complete. Upload failures retry automatically; local results remain safe while app storage survives. Use **Report upload status → New upload session** while stopped to create a separate session.

[APKs and checksums](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.5.0) · [Protocol and implementation](docs/TOOL-MATRIX.md) · [Validation](VALIDATION.md)
