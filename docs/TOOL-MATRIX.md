# A–F: model-controlled retrieval

Protocol `mobile-rag-tools-120-v1`, APK 2.5, profiles 110–115 (A–F). Historical profiles 1–5 and 101–109 retain `mobile-rag-replay-120-v2`.

The developer's F8 APK has an LLM decision pass, parses `buscar_norma`, retrieves evidence and generates an answer. The published [Python F6 runner](https://huggingface.co/juniorrios/automobil-ultra-semnexo/blob/aff14d91b1b8922e67d348afbbc783759477a641/inference.py) injects the first call. This experiment executes **model-controlled calls for both retrievers**, including rewritten second queries. It adapts the retrievers under one benchmark contract; it does not reproduce F8's prompts or sampling settings verbatim.

## Shared contract

The initial input contains only the shared system/tool definition and the question. The model can answer directly or request `buscar_norma(consulta, nr)`. Only this tool executes. An NR argument absent from the search text is appended to the effective query; both forms are recorded. The tool returns numbered passages, treated as reference data.

All cases use 4,096 context tokens, greedy decoding, thinking disabled, at most two searches and three generation turns. Each turn can generate **512 tokens**, up to 1,536 across a question. The final-answer ceiling remains 512 tokens. Tool output uses the loaded model's tokenizer and at most 1,800 tokens per search; space is reserved for the next decision. Whole passages are dropped from the end when needed, with omissions recorded. References never enter inference.

A third requested search is recorded but not executed. The raw output remains with `finalAnswerAvailable=false` and `stopReason=tool_limit`; it must not count as a successful final answer. Completed rows mean finished attempts, not correct answers. Direct answers without retrieval are retained for evaluation. Invalid tool calls receive an error and consume a turn.

## Models and retrieval

| Artifact | Immutable source |
|---|---|
| Base LFM3 Q4_K_M | [LiquidAI, `cd0c74c`](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/tree/cd0c74c575b5c416f3011b5b4c298077f26ca8c2) |
| Fine-tuned LFM3 Q4_K_M and Q4_0 | [juniorrios, `aff14d9`](https://huggingface.co/juniorrios/automobil-ultra-semnexo/tree/aff14d91b1b8922e67d348afbbc783759477a641) |
| MobileRAG+ data and embedders | [MobileRAG+ assets, `5ca7668`](https://huggingface.co/vinyteles/mobilerag-plus-assets/tree/5ca76688707bd3ab73cacd3a52fd792bd430bf48) |
| F6 data | [F6 retrieval assets, `1475313`](https://huggingface.co/datasets/juniorrios/cemig-nr-retrieval-assets/tree/14753134e76c95bff4d594e24550cfb0f0271f2a) |
| F6 embedder | [EmbeddingGemma QAT Q4_0, `8dd0ca2`](https://huggingface.co/ggml-org/embeddinggemma-300M-qat-q4_0-GGUF/tree/8dd0ca2a66a8f14470acb0e2a71f801afbc5fb73) |

The base QAD Q4_0 model is excluded: its additional training would confound a quantization comparison. E/F use ordinary Q4_0 of the same fine-tuned weights as B/D.

MobileRAG+ runs Qwen3 query embeddings, EcoVector/HNSW top-5 search, two neighboring chunks on each side and Granite/int8-window SCR. F6 runs EmbeddingGemma, NR classification/gating, FTS5 BM25 plus dense reciprocal-rank fusion, manual gating and hybrid compression. F6's original 50% compression budget is preserved before the shared tool-context fit. FTS5 is bundled from [SQLite 3.53.4](https://www.sqlite.org/2026/sqlite-amalgamation-3530400.zip). Its fp16 compression vectors are memory-mapped rather than expanded in the Android Java heap.

LFM3 requests Vulkan and remains loaded across questions in its worker. Query embedders run sequentially on CPU and are released after each query. Immutable retrieval data is reused within the worker. Each generation turn clears attention/recurrent state and prefills the recorded conversation; KV state is not preserved between turns or questions. There is no app CPU fallback for the requested answer GPU backend; inspect native logs for operator placement on the physical phone.

## Reports and judging

ZIP member names remain compatible. For this protocol, `rag-replay.json` contains the pinned **question-only tool fixture**. Each answer adds `toolTrace`, actual `sources`, `retrieval`, `finalAnswerAvailable`, `toolCallCount` and `toolRequestCount`. Traces include exact prompts/hashes, generated calls, effective queries, raw retrieved passages and the passages actually supplied.

Timing separates `toolDecisionMs`, `retrievalMs`, `finalGenerationMs` and total `questionMs`. `endToEndMs` adds answer-engine load/close. Query-embedder load is part of retrieval. Per-turn native metrics remain in `toolTrace[].generation.runtimeMetrics`. Final-answer first-token time includes preceding tool work. Checkpoint/export/network overhead is outside question timing but contributes to run wall time. Freezes inside a measured question contribute to its wall time.

Apply the unchanged QA rubric and reference answers to all 720 attempts. Citation judging must use **each answer's actual sources**, not the fixture's empty list. Report overall, regulatory/internal, and each wording style. Retain failed/no-final-answer attempts in denominators and report them explicitly. Do not mix results with the old frozen-context benchmark.

## Build and checks

Use the SDK/NDK prerequisites in [TECHNICAL.md](TECHNICAL.md) and a full JDK.

```bash
scripts/prepare-native-deps.sh
python3 scripts/prepare-tool-assets.py
bash scripts/build-matrix-apks.sh
```

Signed ARM64 APKs and checksums go to `artifacts/matrix-2.5/`, using the existing package/signing identity. Models and credentials are not embedded.

Support-data preparation verifies source/corpus/SQLite checksums, then verifies generated sentence spans and stopwords against the protocol. Parity checks cover 30 MobileRAG+ vector cases, 11 F6 queries including rewrites, and native EmbeddingGemma parity. Real-model CPU smoke tests cover base + MobileRAG+, fine-tuned Q4_K_M + F6 and fine-tuned Q4_0 + F6. These checks do not establish physical Samsung GPU performance or long-run stability.

The relay rejects mixed replay/tool fixtures, retains legacy validation, and publishes only after the assigned case reaches 120 completed attempts. `python -m server.e2e_check --batch 110 --base-url ... --expect-instance ...` tests the new completion gate using a synthetic session.
