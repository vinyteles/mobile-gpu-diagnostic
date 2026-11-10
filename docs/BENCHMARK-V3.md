# Benchmark v3: configurations 17–24

V3 compares complete pipelines. Tool policies differ deliberately; results do not isolate model weights alone.
Nemotron belongs to v4 and is not included. F6 retains its published NR/manual corpus, without internal CEMIG documents.

| Configuration | Answer model | RAG | Answer backend | Tool policy |
|---|---|---|---|---|
| 17 | Energy-Mobile mixed 4/8-bit | MobileRAG+ | LiteRT-LM GPU | Native `searchDocuments`, one search |
| 18 | Energy-Mobile mixed 4/8-bit | F6 | LiteRT-LM GPU | Native `searchDocuments`, one search |
| 19 | Gemma 4 E2B-IT mixed 2/4/8-bit | MobileRAG+ | LiteRT-LM GPU | `<call_tool>`, original question, one search |
| 20 | Gemma 4 E2B-IT mixed 2/4/8-bit | F6 | LiteRT-LM GPU | `<call_tool>`, original question, one search |
| 21 | LFM3 fine-tuned Q4_K_M (v2 weights) | MobileRAG+ | llama.cpp Vulkan | Existing `buscar_norma`, up to two searches |
| 22 | LFM3 fine-tuned Q4_K_M (v2 weights) | F6 | llama.cpp Vulkan | Existing `buscar_norma`, up to two searches |
| 23 | LFM3 RLVR v3s400 Q4_0 | MobileRAG+ | llama.cpp Vulkan | Existing `buscar_norma`, up to two searches |
| 24 | LFM3 RLVR v3s400 Q4_0 | F6 | llama.cpp Vulkan | Existing `buscar_norma`, up to two searches |

All query embeddings run on CPU. MobileRAG+ retains Qwen3 retrieval embeddings and Granite SCR; F6 retains EmbeddingGemma and its compression method. Native GPU operator placement and long-run stability require physical-phone validation.

## Protocol and model identity

- Protocol: `mobile-rag-pipelines-120-v3`; APK version 3.0, version code 12.
- Display configurations 17–24 map to Gradle profiles 16–23 and relay wire batches 116–123. Historical IDs are unchanged.
- V3 has a separate resume queue, configuration IDs and fixture hash. V2 answers cannot satisfy v3 progress.
- The original 120 questions, reference answers, categories and wording variants remain unchanged: 72 regulatory and 48 internal prompts. This is a rerun with new pipelines, not a new held-out dataset.
- Each turn is limited to 512 output tokens and a 4096-token context. LFM can use three generation turns. Gemma uses a decision turn and, when requested, a fresh grounded-answer turn.
- Model URLs, revisions, file sizes and SHA-256 checksums are pinned in `ModelCatalog.kt`. Downloads are verified before inference.
- Energy is the original mixed 4/8-bit artifact (`63a7374c…`), not main's newer RL int8 artifact.
- RLVR is `v1.1-rlvr/cemig-lfm3-1.7b-nr-v3s400-Q4_0.gguf` at `682e55452123b5451fea11be0fc46ab2a4aa1c17`, SHA-256 `689735f30a195be407fc56c5c4b7b27e3043c37b5ff2e58480477cda5992d6d8`.
- Comparisons between the existing Q4_K_M LFM and new Q4_0 RLVR change both training and quantization.

## Tool behavior

Energy's adapter follows `app-mobile/main` at `f1812f9`: it registers the native `searchDocuments(query)` schema, parses native/inline calls, removes invented norm codes from the effective query, and disables tools for the answer. The selected RAG replaces the chat app's retrieval implementation. Document-title citations are retained. English instructions in the adapter describe the same behavior; this is not a byte-identical port of the chat UI prompt/history.

Energy retains the chat application's fallback policy: a direct answer to a question of six or more words, an initial unsupported refusal, or an empty decision triggers an original-question search. Every such search is labeled `application_fallback`; it does not count as a model-generated tool call. Malformed calls are recorded as failures. The answer stage uses only the supplied passages. Infrastructure failures remain errors.

Gemma must output exactly `<call_tool>` to request retrieval. The application sends the unmodified question to the selected retriever. Greetings can receive direct answers. A malformed marker is not a successful answer and does not trigger hidden retrieval. After retrieval, a new prompt contains only the question, evidence and answer instructions.

LFM retains the v2 live runner's XML `buscar_norma(consulta,nr)` contract, follow-up query handling, two-search limit, history, source ranking and context fitting. The published Python runner's injected initial search is not substituted for the model-controlled benchmark flow.

LiteRT 0.18.1 does not expose arbitrary-text tokenization. Its pre-generation fit uses the chat application's three-characters-per-token estimate, reserves template/output space, and records `contextTokensEstimate` plus `tokenCountMethod`. This also sizes F6 compression for LiteRT models. LFM uses its exact tokenizer. Actual LiteRT prefill/decode counts remain in runtime metrics. The estimate is not reported as an exact token count.

## Retrieval and QA evaluation

Each search records the original model query, effective query and trigger, followed by:

1. `retrievalTrace.candidateSources`: ranked candidates before compression.
2. MobileRAG+'s `expandedCandidateSources`: neighboring chunks before SCR.
3. `retrievedSources`: processed/compressed output from the retriever.
4. `sources` and `toolResponse`: passages actually supplied after prompt fitting.

References never enter inference. Evaluate downloaded phone ZIPs:

```bash
python -m benchmark_v3.evaluate prepare --output reports/v3-evaluation phone-config-17.zip phone-config-18.zip

# Use the existing cemig-mobile-eval environment and the same served GPT-OSS-120B judge.
PYTHONPATH=/path/to/cemig-mobile-eval:$PWD /path/to/evaluation-python \
  -m benchmark_v3.evaluate judge --output reports/v3-evaluation \
  --base-url http://127.0.0.1:8123/v1

python -m benchmark_v3.evaluate summarize --output reports/v3-evaluation
```

Supply one explicitly selected ZIP per configuration; duplicates, wrong models, wrong fixtures and synthetic reports are rejected. All 120 slots are retained. Unrun questions stay pending; attempted questions without final answers receive QA zero. Failed judge calls remain unresolved and the judge command exits nonzero. Requests are cached and checkpointed, so interrupted judging can resume.

The output includes per-question `rows.json`, input archive hashes, raw cached judgments and `summary.json`, grouped by configuration, category and wording. QA uses the unchanged v2 rubric (SHA-256 `c16cd1d181dd23e0e4c75dbfdd358d4a788b441dfd208455c15c34b2e7ee0d6c`) and GPT-OSS-120B settings. Citation/grounding audits recognize both numbered citations and Energy's document-title citations. Evidence coverage is judged separately before compression and in final context; full/partial/absent map to 1/0.5/0. This is a judge-based coverage score, not exhaustive qrel recall.

Exact source hit at common cutoffs 1/3/5 is retained per call, alongside actual candidate counts. With the benchmark's single reference source this equals recall against that annotated source, not all relevant documents. F6 labels do not reliably resolve exact PDF editions: such identity matches are **unresolved**, not counted as zero or silently matched by NR number. Semantic evidence assessment still applies. Verbatim evidence-span containment is reported separately as a lexical proxy; compression can correctly retain relevant information without preserving an entire quoted span. Precision and nDCG are not fabricated without exhaustive relevance labels.

Summaries separate tool-decision, retrieval, final-generation and total question time. No-search outcomes stay in question-level retrieval denominators. Per-call cutoff metrics apply to searches that actually occurred. F6's internal-document scores must be interpreted with its corpus coverage limitation.

## Build and run

```bash
bash scripts/build-v3-apks.sh
```

Eight signed ARM64 APKs and `SHA256SUMS` are written to `artifacts/v3/`. The script uses sequential builds and at most two Gradle/native workers. Each APK runs exactly its assigned configuration. Install it, verify/download its assets, then Start / resume. Installing another configuration preserves verified model downloads. Existing per-question checkpoints, report ZIP import and device provenance remain available.

The relay must include the v3 catalog before phone uploads start. The existing discovery URL and service identity are reused; v3's separate protocol and IDs distinguish it from v2. Quality evaluation starts after real phone results are available; APK smoke tests do not constitute benchmark results.
