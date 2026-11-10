# V3 implementation validation — 2026-11-10

The eight profiles (17–24) built as signed ARM64 APKs. Real model files were checked against their pinned SHA-256 values before emulator inference. Energy's mixed 4/8-bit artifact was retained; the newer RL int8 model was not substituted.

| Configuration | Real-model CPU smoke with assigned RAG |
|---|---|
| 17 · Energy / MobileRAG+ | Passed |
| 18 · Energy / F6 | Passed |
| 19 · Gemma / MobileRAG+ | Passed |
| 20 · Gemma / F6 | Passed |
| 21 · Existing LFM Q4_K_M / MobileRAG+ | Passed |
| 22 · Existing LFM Q4_K_M / F6 | Passed |
| 23 · RLVR LFM Q4_0 / MobileRAG+ | Passed |
| 24 · RLVR LFM Q4_0 / F6 | Passed |

Each integration smoke generated a model request, executed the actual pinned retrieval pipeline, retained pre-compression candidate traces, supplied evidence, and produced a final answer. These assertions test execution, not answer correctness. Test reports carry `smokeTest=true` and are excluded from quality scoring. Outputs are saved locally in `reports/v3-validation/`.

Additional checks:

- 76 Python server/evaluation tests passed; one existing test requiring a missing historical example ZIP was skipped.
- Nine progress-documentation tests passed against the committed README. Pre-existing local README edits were preserved separately from the commit.
- Seven Android retrieval-parity/tool-loop tests passed, covering MobileRAG+ vectors, F6 contexts, native EmbeddingGemma parity and LFM follow-up query limits.
- Deterministic Android checks cover exact Gemma flags, malformed flags, conversational messages, Energy native calls versus application fallbacks, and invented norm-code removal.
- Energy and Gemma separately passed real LiteRT CPU interface tests with synthetic evidence and a known verification marker.
- The live relay passed 22 upload/publication checks for a v3 synthetic-test session: partial checkpoints remained stored, publication required 120/120, stale snapshots were superseded, and the published files were independently verified. The server health/readiness and public discovery path were checked.

The complete LFM tests used a four-core x86_64 emulator. A two-core attempt hit the unchanged 180-second generation timeout; it was retried with four cores, not by increasing the benchmark timeout. The emulator RAM limit was 5 GiB and APK builds used at most two workers. Gemma's routing instructions were clarified after an initial smoke exposed extra XML after its marker; the final prompt passed both retrieval combinations.

Physical-phone GPU behavior, long-run thermal/memory stability, and the actual 120-question QA/retrieval scores remain to be measured. Emulator CPU timings are not phone performance results. The judge pipeline is implemented and uses the verified unchanged v2 QA rubric; no v3 quality scores are claimed before real benchmark reports arrive.
