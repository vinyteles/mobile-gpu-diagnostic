# Mobile RAG benchmark

Configuration names link directly to their APKs. Configurations **1–9** are the published
generation-only replay benchmarks. Configuration **10** is reserved for the developer-provided
**Energy-Mobile int8 · LiteRT-LM GPU** build. Configurations **11–16** run live, model-controlled retrieval.

## Configurations 1–16

| Configuration name | Model | RAG | Hardware | Backend | Progress | Download | Estimated total, including **30 extra minutes** |
|---|---|---|---|---|---:|---:|---:|
| [1 · Energy mixed GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-1-energy-mixed48-gpu-arm64.apk) | Energy-Mobile mixed 4/8-bit | MobileRAG+ (frozen contexts) | GPU | LiteRT-LM | ✅ 120/120 | 2.03 GB | ~1 h |
| [2 · LFM Vulkan GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-2-lfm-vulkan-gpu-arm64.apk) | LFM3 Q4_K_M | MobileRAG+ (frozen contexts) | GPU | llama.cpp / Vulkan | ✅ 120/120 | 1.10 GB | ~1 h 10 min |
| [3 · LFM LiteRT GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-3-lfm-litert-gpu-arm64.apk) | LFM3 mixed4 | MobileRAG+ (frozen contexts) | GPU | LiteRT-LM | ✅ 120/120 | 1.44 GB | ~1 h–1 h 15 min † |
| [4 · Gemma mobile GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-4-gemma-mobile-gpu-arm64.apk) | Gemma 4 E2B-IT mixed 2/4/8-bit | MobileRAG+ (frozen contexts) | GPU | LiteRT-LM | ✅ 120/120 | 2.59 GB | ~1 h–1 h 15 min † |
| [5 · Gemma Q8 GPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-5-gemma-q8-gpu-arm64.apk) | Gemma 4 E2B-IT Q8_0 | MobileRAG+ (frozen contexts) | GPU, if RAM permits | llama.cpp / Vulkan | ⏭️ 0/120 (skipped) | Up to 5.05 GB | ~1 h 30 min–2 h † |
| [6 · LFM CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-6-lfm-cpu-arm64.apk) | LFM3 Q4_K_M | MobileRAG+ (frozen contexts) | CPU | llama.cpp / CPU | ⏳ 27/120 (partial) | 1.10 GB | ~2 h |
| [7 · Energy int8 CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-7-energy-int8-cpu-arm64.apk) | Energy-Mobile int8 | MobileRAG+ (frozen contexts) | CPU | LiteRT-LM | ✅ 120/120 | 2.97 GB | ~1 h 30 min |
| [8 · Energy mixed CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-8-energy-mixed48-cpu-arm64.apk) | Energy-Mobile mixed 4/8-bit | MobileRAG+ (frozen contexts) | CPU | LiteRT-LM | ✅ 120/120 | 2.03 GB | ~1 h 10 min |
| [9 · Gemma mobile CPU](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.4.0/benchmark-2.4-config-9-gemma-mobile-cpu-arm64.apk) | Gemma 4 E2B-IT mixed 2/4/8-bit | MobileRAG+ (frozen contexts) | CPU | LiteRT-LM | ✅ 120/120 | 2.59 GB | ~1 h 30 min–2 h † |
| 10 · Energy int8 GPU — developer-provided (APK link pending) | Energy-Mobile int8 | Pending | GPU | LiteRT-LM | ✅ Reported by Leo (not server-verified) | Pending | Pending |
| [11 · LFM3 base / MobileRAG+](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-A-arm64.apk) | LFM3 base Q4_K_M | MobileRAG+ (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | ✅ 120/120 | 1.94 GB | ~1 h 30 min–2 h † |
| [12 · LFM3 fine-tuned / MobileRAG+](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-B-arm64.apk) | LFM3 fine-tuned Q4_K_M | MobileRAG+ (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | ✅ 120/120 | 1.94 GB | ~1 h 30 min–2 h † |
| [13 · LFM3 base / F6](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-C-arm64.apk) | LFM3 base Q4_K_M | F6 (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | ✅ 120/120 | 1.48 GB | ~1 h 15 min–1 h 45 min † |
| [14 · LFM3 fine-tuned / F6](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-D-arm64.apk) | LFM3 fine-tuned Q4_K_M | F6 (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | ✅ 120/120 | 1.48 GB | ~1 h 15 min–1 h 45 min † |
| [15 · LFM3 fine-tuned Q4_0 / MobileRAG+](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-E-arm64.apk) | LFM3 fine-tuned Q4_0 | MobileRAG+ (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | ⏳ 84/120 (partial) | 1.89 GB | ~1 h 30 min–2 h † |
| [16 · LFM3 fine-tuned Q4_0 / F6](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/download/v2.5.0/benchmark-2.5-case-F-arm64.apk) | LFM3 fine-tuned Q4_0 | F6 (live tool calls) | GPU (LLM); CPU (embeddings) | llama.cpp / Vulkan | — No server result | 1.43 GB | ~1 h 15 min–1 h 45 min † |

<!-- benchmark-progress-updated -->Progress last changed: 2026-11-08T00:17:26.503Z.

Progress comes from the [public relay index](https://huggingface.co/datasets/vinyteles/mobilebench-index-v2/blob/main/sessions.json).
Counts show the best saved checkpoint for one run; separate runs are never added together,
and earlier higher checkpoints are retained. ✅ means 120 recorded attempts, not 120 correct
answers. Synthetic tests are excluded. Configuration 10 remains manually reported by Leo.
Configuration 7 also has a [verified 120/120 result from the previous relay](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b107-9ca108d65f), retained here even though it is absent from the current index.
To refresh locally with Node.js 22, run `node scripts/update-benchmark-progress.mjs`.

Hardware describes the requested execution device. For configurations 11–16, the backend
column describes answer generation; query embeddings run on CPU.

Published APK filenames and app screens still use the original case labels:
**11 = A, 12 = B, 13 = C, 14 = D, 15 = E, 16 = F**. The configuration-name links above
point to those existing APKs.

Configurations 1–9 use the same 120 prompts with frozen MobileRAG+ contexts: up to **1,080
answers**. They measure generation, not live phone retrieval. Their times are planning estimates
for a Galaxy S25 FE-class phone, with the extra 30 minutes already included. † Configurations
3, 4, 5 and 9 have provisional budgets, not measured phone durations. Model downloads exclude
the APK and runtime caches; configuration 5 is skipped if available RAM is insufficient.
See the [replay protocol](https://github.com/vinyteles/mobile-gpu-diagnostic/blob/8c3d0213690d1ef2e59f25c843a3720bc7aeaecd/docs/REPLAY-BENCHMARK.md) and [timing basis](docs/TIMING.md).

Configuration 10 is the developer-provided Energy-Mobile int8 build, reported to use LiteRT-LM
on GPU. Its APK link, exact model hash, retrieval protocol, question count, download size and
timings are not yet recorded. It is not included in the published replay APKs or their answer total;
configuration 7 remains the existing Energy-Mobile int8 CPU baseline.

## Running live-retrieval configurations 11–16

Install your assigned APK and tap **Start / resume**. Each APK runs **one configuration on 120 questions**. The model decides when to search; retrieval and generation run on the phone. No Hugging Face account, token, or development tools are needed.

Use one APK per two-hour Samsung RTL reservation. All cases request **Vulkan GPU for LFM3**; query embeddings use CPU. Run configuration 11 first on a new phone type, then the other cases. Colleagues can run different APKs in parallel. Validate each live-retrieval configuration on its assigned phone; earlier replay results do not establish its stability.

† These are provisional planning budgets, not measured Samsung durations. **The extra 30 minutes is already included.** Downloads, search decisions, heat and device freezes can exceed two hours; save the checkpoint and resume in another reservation if needed. Downloads include model weights, query embedders and retrieval data; the APK and working space are additional. Verified files on the same installation are reused.

**The live-retrieval suite has six configurations (11–16):** 120 attempted answers each, or 720 in total. Earlier config 2 used frozen contexts and cannot serve as the live tool-calling baseline. [Earlier replay APKs](https://github.com/vinyteles/mobile-gpu-diagnostic/blob/8c3d0213690d1ef2e59f25c843a3720bc7aeaecd/docs/REPLAY-BENCHMARK.md) remain available separately.

**Dataset and traceability**

The source is [UFGCEMIGONA/benchmark_mobile](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile), pinned to [revision `5a5aa32`](https://huggingface.co/datasets/UFGCEMIGONA/benchmark_mobile/tree/5a5aa32ae4087481be828e071aff169827ec2993). The full dataset has 2,508 Portuguese prompts from 627 base questions. Our unchanged sample is **30 base questions × four wording styles = 120 prompts per configuration**.

| Source category | Full dataset prompts | Selected base questions | Test prompts |
|---|---:|---:|---:|
| Regulatory standards | 1,536 | 18 | 72 |
| Internal CEMIG documents | 972 | 12 | 48 |
| **Total** | **2,508** | **30** | **120** |

Every case receives 30 `clean`, 30 `technical`, 30 `operational`, and 30 `lay` prompts, in the same order. Question types are 32 factual, 32 conditional, 28 procedural and 28 requiring multiple pieces of evidence. These are related variants of 30 questions. Deterministic selection uses seed `mobile-bench-120-v2`, independently of model quality. The app performs no training.

The [sample file](https://github.com/vinyteles/mobile-gpu-diagnostic/blob/8c3d0213690d1ef2e59f25c843a3720bc7aeaecd/app/src/main/assets/tool-questions.json) preserves question/base-question IDs, categories, references, dataset pins and the shared tool protocol. **References and reference evidence never enter inference.** Reports preserve model-generated queries, retrieved and supplied passages, conversation prompts, model hashes and timings. Every answer is traceable to its actual input.

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

[APKs and checksums](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.5.0) · [Protocol and implementation](https://github.com/vinyteles/mobile-gpu-diagnostic/blob/8c3d0213690d1ef2e59f25c843a3720bc7aeaecd/docs/TOOL-MATRIX.md) · [Validation](https://github.com/vinyteles/mobile-gpu-diagnostic/blob/8c3d0213690d1ef2e59f25c843a3720bc7aeaecd/VALIDATION.md)
