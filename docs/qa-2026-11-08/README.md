# QA evaluation of all available complete mobile configurations

Generated: 2026-11-08T01:09:21.264870+00:00

Evaluated coverage: **14 complete configurations, 1680 attempted answers**. Configurations 5 (skipped) and 6 (27/120 saved) do not have complete datasets and are not represented as evaluated.

The earlier 720 QA verdicts and 720 citation audits for configurations 1, 3, 4, 7, 8 and 9 were reused only after exact payload/answer equality checks. The current run submitted 936 missing QA requests and 936 missing citation requests. Existing exact-request caches could satisfy some of those requests without model computation.

24 live-tool attempts produced no final answer. They remain in the 120-attempt denominator with deterministic score 0 and a separate `no_final_answer` label. Their raw tool calls were not judged as answers; citation auditing is not applicable. They are not LLM-judge API failures.

Judge: GPT-OSS-120B, the unchanged benchmark_mobile QA rubric and separate citation-support rubric. No model/configuration labels or timing information entered judge payloads. Live citation payloads use each answer's actual passages, verified in its final model prompt; replay payloads use frozen contexts.

Model and tokenizer revision: `b5c939de8f754692c1647ca79fbf85e8c1e70f8a`. QA rubric SHA-256: `c16cd1d181dd23e0e4c75dbfdd358d4a788b441dfd208455c15c34b2e7ee0d6c`. Citation rubric SHA-256: `a894c6ddfbd8d16d27e9977d0f2014bb504e4f247d78574df7faed458caa6dc5`. Initial temperature 0, reasoning effort low, thinking disabled, 4,096 output tokens, 16,384 context tokens. The unchanged client retries a truncated response at temperature 0.3, repetition penalty 1.1 and seed 0. The full current run logged 0 such sampling retry events; reused caches retain their original client behavior. All 1,872 new requests passed tokenizer/context checks without truncation; bounded smoke checks passed both stages.

All 14 configurations have identical question IDs, question text, gold answers/evidence and category/wording/type strata. Source archives are frozen at immutable commits with ZIP and fixture checksums. The original phone answers were preserved; no answers were regenerated.

Scores: correct=1, partial=0.5, incorrect=0, abstained=0. Unresolved judge requests remain unscored and explicit. The 120 prompts are four related wordings of 30 base questions; do not treat all 120 as independent samples.

Unresolved answer-level evaluations: **0**. Citation segment-ID mismatches: **0**.

Additional audit diagnostics: **18** factual-claim labels disagree with whether the segment has a valid citation, and **1** answer records have citation-rank differences between the app parser and the analysis parser. These counts are distinct from API/schema/ID failures. Original valid judgments are preserved; the exact affected answer keys are listed in [consistency.json](results/consistency.json). Passing schema and ID checks does not guarantee semantic correctness of a model judgment.

## Frozen-context replay

Every row contains 120 attempts. The score is the weighted sum divided by 120.

| Config | Model / retrieval | QA judged | No final | Correct | Partial | Incorrect | Abstained | Unresolved | Score /120 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | Energy mixed GPU | 120 | 0 | 61 | 24 | 35 | 0 | 0 | 0.6083 |
| 2 | LFM3 Q4_K_M Vulkan GPU | 120 | 0 | 56 | 25 | 39 | 0 | 0 | 0.5708 |
| 3 | LFM3 LiteRT GPU | 120 | 0 | 62 | 19 | 39 | 0 | 0 | 0.5958 |
| 4 | Gemma4 mixed GPU | 120 | 0 | 64 | 22 | 34 | 0 | 0 | 0.6250 |
| 7 | Energy int8 CPU | 120 | 0 | 63 | 20 | 37 | 0 | 0 | 0.6083 |
| 8 | Energy mixed CPU | 120 | 0 | 40 | 35 | 45 | 0 | 0 | 0.4792 |
| 9 | Gemma4 mixed CPU | 120 | 0 | 64 | 20 | 36 | 0 | 0 | 0.6167 |
| 10 | Energy int8 RL GPU | 120 | 0 | 63 | 20 | 37 | 0 | 0 | 0.6083 |

Citation results concern factual-claim segments. Coverage is the percentage with an in-range citation; cited support is the percentage of cited claims fully supported by those cited passages; context support is the percentage of all claims fully supported anywhere in retrieved passages. Partial support is excluded from the full-support percentages. Missing finals have no citation audit.

| Config | Audits | Unresolved | Factual claims | Citation coverage | Cited support | Context support |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 120 | 0 | 210 | 94.8% | 96.0% | 97.1% |
| 2 | 120 | 0 | 609 | 72.4% | 70.1% | 83.1% |
| 3 | 120 | 0 | 454 | 80.0% | 74.1% | 88.5% |
| 4 | 120 | 0 | 212 | 96.7% | 97.6% | 97.6% |
| 7 | 120 | 0 | 196 | 96.9% | 95.3% | 95.9% |
| 8 | 120 | 0 | 200 | 90.5% | 76.8% | 90.0% |
| 9 | 120 | 0 | 211 | 97.6% | 97.1% | 97.2% |
| 10 | 120 | 0 | 197 | 97.0% | 97.4% | 98.0% |

QA by domain and wording (mean score, including any missing-final failures in each stratum):

| Config | Regulatory /72 | Internal /48 | Clean /30 | Technical /30 | Operational /30 | Lay /30 |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 0.562 | 0.677 | 0.617 | 0.567 | 0.550 | 0.700 |
| 2 | 0.535 | 0.625 | 0.533 | 0.600 | 0.617 | 0.533 |
| 3 | 0.562 | 0.646 | 0.617 | 0.567 | 0.617 | 0.583 |
| 4 | 0.590 | 0.677 | 0.617 | 0.600 | 0.633 | 0.650 |
| 7 | 0.576 | 0.656 | 0.600 | 0.617 | 0.600 | 0.617 |
| 8 | 0.486 | 0.469 | 0.583 | 0.400 | 0.383 | 0.550 |
| 9 | 0.569 | 0.688 | 0.617 | 0.617 | 0.583 | 0.650 |
| 10 | 0.556 | 0.688 | 0.617 | 0.583 | 0.600 | 0.633 |

## Live retrieval

Every row contains 120 attempts. The score is the weighted sum divided by 120.

| Config | Model / retrieval | QA judged | No final | Correct | Partial | Incorrect | Abstained | Unresolved | Score /120 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 11 | LFM3 base Q4_K_M / MobileRAG+ | 117 | 3 | 40 | 16 | 55 | 6 | 0 | 0.4000 |
| 12 | LFM3 tuned Q4_K_M / MobileRAG+ | 118 | 2 | 58 | 13 | 47 | 0 | 0 | 0.5375 |
| 13 | LFM3 base Q4_K_M / F6 | 111 | 9 | 36 | 9 | 61 | 5 | 0 | 0.3375 |
| 14 | LFM3 tuned Q4_K_M / F6 | 113 | 7 | 39 | 10 | 64 | 0 | 0 | 0.3667 |
| 15 | LFM3 tuned Q4_0 / MobileRAG+ | 120 | 0 | 43 | 14 | 63 | 0 | 0 | 0.4167 |
| 16 | LFM3 tuned Q4_0 / F6 | 117 | 3 | 38 | 10 | 67 | 2 | 0 | 0.3583 |

Citation results concern factual-claim segments. Coverage is the percentage with an in-range citation; cited support is the percentage of cited claims fully supported by those cited passages; context support is the percentage of all claims fully supported anywhere in retrieved passages. Partial support is excluded from the full-support percentages. Missing finals have no citation audit.

| Config | Audits | Unresolved | Factual claims | Citation coverage | Cited support | Context support |
|---|---:|---:|---:|---:|---:|---:|
| 11 | 117 | 0 | 508 | 34.1% | 53.2% | 77.8% |
| 12 | 118 | 0 | 343 | 0.9% | 0.0% | 74.6% |
| 13 | 111 | 0 | 380 | 23.2% | 84.1% | 80.8% |
| 14 | 113 | 0 | 292 | 0.0% | n/a | 76.7% |
| 15 | 120 | 0 | 288 | 2.4% | 71.4% | 71.9% |
| 16 | 117 | 0 | 236 | 0.8% | 100.0% | 73.7% |

QA by domain and wording (mean score, including any missing-final failures in each stratum):

| Config | Regulatory /72 | Internal /48 | Clean /30 | Technical /30 | Operational /30 | Lay /30 |
|---|---:|---:|---:|---:|---:|---:|
| 11 | 0.382 | 0.427 | 0.333 | 0.350 | 0.483 | 0.433 |
| 12 | 0.549 | 0.521 | 0.617 | 0.500 | 0.533 | 0.500 |
| 13 | 0.486 | 0.115 | 0.400 | 0.317 | 0.350 | 0.283 |
| 14 | 0.535 | 0.115 | 0.433 | 0.417 | 0.417 | 0.200 |
| 15 | 0.396 | 0.448 | 0.417 | 0.383 | 0.467 | 0.400 |
| 16 | 0.514 | 0.125 | 0.483 | 0.383 | 0.300 | 0.267 |

## Artifacts

- [Configuration summary](results/summary_by_configuration.csv): QA, citation support, coverage and timing.
- [Domain, wording and question-type breakdowns](results/breakdowns.csv).
- [Base-question scores](results/base_question_scores.csv): aggregates over each question’s four wordings.
- [Consistency checks and affected answer keys](results/consistency.json).
- [Immutable source commits and complete ZIP checksums](sources.json).

Full per-answer results, original judgments, frozen inputs, caches, validation logs and execution provenance are retained in the evaluation archive `all-configurations-qa-20261107` and its remote mirror `mobilebench-qa-20261107`.

## Source datasets

Each archive link is pinned to the exact evaluated commit. Full SHA-256 checksums are in `sources.json`.

| Configuration | Original archive | SHA-256 prefix |
|---|---|---|
| 1 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b101-c9e32afafd/resolve/9aeccc585e808f0f55507e217212d982bcff069b/runs/bench120-1793909547726-45d0ed03/latest.zip) | `35ddb8b0038d270c` |
| 2 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261106-b102-0cbfec5261/resolve/cc081216948ca4764991f91b00da1da6d6a6cbb1/runs/bench120-1793931533082-82073237/latest.zip) | `1964572ff52e7bc9` |
| 3 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b103-321b824629/resolve/0ed9b7907844995bf6a63c092d77171a5aaec594/runs/bench120-1793904111834-6d11cbc5/latest.zip) | `bcdf5c87cbc9b420` |
| 4 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b104-5c152caa09/resolve/45dcc4f84e1ced6e33ede60747b93da75f3238cc/runs/bench120-1793909377570-6870af4d/latest.zip) | `a4cc3c37f0630e4b` |
| 7 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b107-9ca108d65f/resolve/b78848d09f9d40c193ce1372774502dbc9ebfc5d/runs/bench120-1793909877340-ddc76d7b/latest.zip) | `72ebd0e7820b08e5` |
| 8 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b108-0a6895bd26/resolve/0886d20419c244b9ffbf8ec49b7c349daa016e1f/runs/bench120-1793909547726-d2385585/latest.zip) | `25428799ee828102` |
| 9 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261105-b109-b79adc16ae/resolve/a1fff4672db751474b3a82a4983e599664bc53c2/runs/bench120-1793915701584-16fd95ad/latest.zip) | `ea369704318a15c7` |
| 10 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-import-config10-energy-int8-rl-gpu-6b28bf164068/resolve/e05a644c3bfe0ccc2293b66c0b505fe93c3a6e48/runs/bench120-1793979549858/benchmark-config8-energy-gpu-SM-S731N.zip) | `6b28bf164068082e` |
| 11 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261106-b110-9e7b33193b/resolve/92b88d2defbee57d77352000673c48d7a49f5906/runs/bench120-1794004230103-427d0e5c/latest.zip) | `b0eb894b26ebc90f` |
| 12 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261107-b111-f6c3ae0688/resolve/0915103c58f2f0e7be6a71473edd0d6b32238d3b/runs/bench120-1794091396343-6c165589/latest.zip) | `8961d3101c2761ba` |
| 13 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261107-b112-5de3244491/resolve/7d90ea62f5b2ac81b81a9d0211eb3609e9918a70/runs/bench120-1794094625812-1ff9a2b9/latest.zip) | `8e5435bd1694a7c0` |
| 14 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261107-b113-4e169fffd3/resolve/71052ee680b434b4cebd782a65ce2136476816cb/runs/bench120-1794094744444-d24ffae4/latest.zip) | `eaee76084e4afa01` |
| 15 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261107-b114-4843228fe1/resolve/ea78dcedfccefb3f095f4ef518b22cef4bdb3446/runs/bench120-1794095070760-ef69ce0c/latest.zip) | `388863a64f81d2ba` |
| 16 | [Download pinned report](https://huggingface.co/datasets/vinyteles/mobilebench-relay-20261108-b115-be05f52778/resolve/c0da75e9ba4ce559b992705403ca4423e3f7a17f/runs/bench120-1794097067223-e911017d/latest.zip) | `c3df9e1acfef7b45` |

Raw hardware/model declarations remain client-reported. QA scores are model judgments. Frozen-context and live-retrieval protocols are reported separately. F6’s corpus does not contain the internal documents; use the category breakdown when comparing retrieval settings.
