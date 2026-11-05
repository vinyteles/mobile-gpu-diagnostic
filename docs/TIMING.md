# Time estimate basis

These are planning estimates, not measurements of the complete 120-question APK batches. The README adds **30 minutes once per APK** for downloads, preparation, uploads and delays. This is a fixed allowance, not a measured download duration or completion guarantee.

## Previously measured configurations

Earlier Galaxy S25 FE / SM-S731N runs used 30 questions and a 256-token output limit. The current benchmark uses 120 prompts and permits 512 output tokens. We projected each earlier question's measured duration to the larger limit by adding `256 / measured decode tokens per second` only when the earlier answer hit its output limit, then averaged and multiplied by 120. This assumes capped answers consume all 256 additional tokens; actual lengths and the new question mix can differ.

| Configuration | Earlier mean answer time | Earlier limit hits | Projected generation for 120 answers |
|---|---:|---:|---:|
| Energy mixed 4/8-bit GPU | 9.1 s | 2/30 | 20.3 min |
| LFM3 Q4_K_M Vulkan GPU | 14.1 s | 11/30 | 36.3 min |
| LFM3 Q4_K_M CPU | 35.7 s | 11/30 | 82.9 min |
| Energy int8 CPU | 26.9 s | 1/30 | 56.8 min |
| Energy mixed 4/8-bit CPU | 16.7 s | 1/30 | 34.8 min |

Sources are the phone reports `rag-1793807018113`, `gpu-remaining-1793811930553`, and `lfm3-1793818773761-full`. The two Energy GPU reports were combined by question ID to cover 30 unique answers. These are physical-phone timings; synthetic transport tests and desktop/emulator inference timings were excluded. The earlier LFM CPU run was hotter than its GPU run, so its timing already reflects that session's thermal conditions.

## Batch budgets

| Batch | Estimate before the buffer | Extra time | README total |
|---|---:|---:|---:|
| 1 | 60 min, rounded up from 56.6 min | 30 min | 90 min |
| 2 | 60–90 min, provisional | 30 min | 90–120 min |
| 3 | 120–180 min, provisional | 30 min | 150–210 min |
| 4 | 90 min, rounded up from 82.9 min | 30 min | 120 min |
| 5 | 100 min, rounded up from 91.6 min | 30 min | 130 min |

Batches 2 and 3 have no matching physical-phone measurements yet. Their ranges are conservative working budgets, not fitted predictions: batch 2 allocates roughly 30–45 minutes to each new GPU configuration; batch 3 allocates roughly 60–90 minutes to each Gemma configuration. LFM LiteRT reloads its engine for every question, and Gemma Q8 may fail or be skipped. Replace these provisional budgets with observed timings after successful phone runs.

Model downloads use decimal GB and count each distinct model once per batch. They exclude the APK and runtime caches. Bandwidth is not assumed in the inference calculation; a slow connection can consume more than the fixed 30-minute allowance. A two-hour RTL reservation cannot be extended by the app; use checkpoint/import/resume when needed.
