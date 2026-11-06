# Benchmark 2.5 validation

Built November 6, 2026. Six ARM64 APKs use package `com.cemig.gpudiagnostic`, version code 11 / version 2.5, profiles 110–115 (A–F). All cases run the new `mobile-rag-tools-120-v1` protocol; all 720 attempts require new phone runs.

- Six release builds and `lintRelease` passed. Every APK's case label, version, DEX wire-profile constant, ARM64-only libraries, signing certificate, bundled fixtures and retrieval support data were verified. The signing certificate matches 2.4. Checksums accompany the release.
- Retrieval parity: all 30 MobileRAG+ vector fixtures match search hits and reduced passages (the original float32 tie tolerance is retained). All 11 F6 queries, including rewritten queries, match the published Python retrieved IDs, gate, compression budget and exact compressed context. Native EmbeddingGemma query cosine exceeds 0.9999 against the pinned Python path.
- Three real-model CPU integration tests passed: base LFM3 + MobileRAG+, tuned Q4_K_M + F6, and tuned Q4_0 + F6. Each model made an actual tool call, local retrieval returned evidence, and the model produced a final answer. These are emulator smoke checks, not Samsung GPU measurements or QA scores.
- Tool-loop checks cover a rewritten second query, citation ranks across searches, a rejected third search and direct answers without unrequested retrieval. Replay/checkpoint, report import and 120-answer queue/resume checks passed. Across the targeted Android invocations, 22 distinct checks passed; one old global-ordering assertion was updated to check the two protocol families separately and passed its rerun.
- F6's fp16 compression vectors are memory-mapped. Expanding them to float32 failed on the emulator's 192 MiB Java heap; the mapped implementation passes retrieval parity without raising the heap limit.
- Server suite: **70 passed, 1 skipped** (optional historical example ZIP absent). It covers six new profiles, the 119/120 completion gate, protocol/fixture isolation and retained legacy behavior.
- Credential/private-host scans found no such material in the six release APKs. The source scan found only the pre-existing explicit dummy credential in a test. Model weights and server credentials are not bundled.

Physical Samsung validation and the 120-question runs remain outstanding. Vulkan is requested without app CPU fallback, but new fine-tuned weights, simultaneous answer/retrieval memory use, thermal behavior and two-hour completion require the actual phone tests. The release is experimental; README time budgets are provisional.

---

# Benchmark 2.4 validation

Built November 5, 2026. Nine ARM64 APKs use package `com.cemig.gpudiagnostic`, version code 10 / version 2.4. Each runs one configuration with 120 questions. Exact checksums accompany the [v2.4.0 release](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.4.0).

- All nine release builds, debug/test builds and `lintRelease` passed. Every APK's version, label, wire profile (101–109), ARM64 architecture, signature, fixture hash and new bootstrap URL were checked. The signing certificate matches earlier releases, allowing updates that retain models and answers. Model hashes, inference implementations and 4096/512 token settings are unchanged.
- Android regression suite: **OK (31 tests)**; four optional integrations are skipped in the default invocation. A new IPC regression verifies that each completed answer queues its checkpoint before the next question starts. The single-configuration 120-answer test exercises per-question exports. The artificial full nine-configuration queue separately verifies ordering and resume without exporting all 1,080 intermediate ZIPs.
- Upload migration checks verify that new service credentials are separate and old session/outbox files remain intact. The app queues the current saved run when reopened. The existing offline retry test still verifies durable local checkpoints and distinguishes receipt from HF publication.
- Server suite in the integrated checkout: **67 passed, 1 skipped** (the optional pre-existing example ZIP was absent). Coverage includes complete-question-ID validation, all nine profile mappings, partial retention across restarts, duplicate/out-of-order snapshots, quotas, per-question upload rates and HF retry behavior.
- Live server test through public HTTPS: **24 checks passed**, including 1/42/119-answer snapshots, stopped/error partials, an API/worker restart, no HF repository before 120 answers, completion publication, immutable JSON/ZIP checksums, and a late partial that cannot regress the published report. [Synthetic evidence dataset](https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-20261105-b101-313fda32db).
- Android emulator → published GitHub bootstrap/HF discovery → replacement relay → real HF: **OK (1 test)**. A one-answer report showed `collecting`, `Server saved 1 / 120`, and no HF repository. Completing the same run to 120 published successfully. The public JSON and ZIP were fetched without authentication at commit `400dfce08495cf255aec746686dedad4edc69fc1`; their checksum matched. [Android transport evidence](https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-20261105-b101-ccd7550caa).
- Tracked source and all nine APKs were scanned for the active HF credential, token/private-key patterns and private server identifiers; none were found. The deployed server source matches the integrated `server/` tree. Public health/readiness and service identity passed.

These are synthetic control-flow, durability and transport checks, not new phone GPU performance or answer-quality results. Per-question ZIP export adds wall-time overhead; network transfer is asynchronous. This release does not diagnose or claim to fix the reported whole-phone freeze. A server checkpoint protects only answers acknowledged before a disconnect.

The relay host must remain awake and online. Services survive terminal closure, but after host reboot the operator must start them again; no automatic boot service is installed. Tunnel URL changes are discovered through the new [HF index](https://huggingface.co/datasets/vinyteles/mobilebench-index-v2). Partial reports remain on server disk; HF report files appear only when all assigned questions are complete. Credential renewal is still required before the recorded November 26, 2026 expiry.

---

# Benchmark 2.3 validation

Built November 5, 2026. Nine ARM64 APKs use package `com.cemig.gpudiagnostic`, version code 9 / version 2.3. Each APK runs exactly one configuration with 120 questions. Exact checksums accompany the [v2.3.0 release](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.3.0).

- All nine release builds, debug/test builds and `lintRelease` passed. Every APK's label, wire profile ID (101–109), ARM64 architecture, signature and fixture hash were checked. All use the same signing certificate as v2.2, so installation updates preserve app data. The questions, model hashes, inference implementations and 4096/512 token settings are unchanged.
- Android regression suite: **OK (29 tests)**; four optional integration tests are skipped in the default invocation. Coverage retains the full 1,080-answer synthetic queue, ordering, failure recovery and report import/resume.
- A new regression fills the old saved selection with all nine configurations and verifies that the default controller downloads and runs only the assigned model, produces 120 synthetic answers, and resumes without repeating or changing them. This passed for configuration 1 and, in a separate **OK (1 test)** invocation, configuration 9.
- A real debug APK update from configuration 1 to configuration 9 preserved the existing queue and report JSON files byte for byte. The preparation screen showed only configuration 9 and its required model; the migration regression also preserves existing run and credential state.
- Upload server deployment: **56 tests passed**. Registration and upload work for all nine new profile IDs; legacy IDs 1–5 retain their historical batch labels. Separate Android tests verify that old and new profile IDs use distinct encrypted session capabilities.
- Android emulator → published GitHub bootstrap/HF discovery → public relay → real HF: **OK (1 test)** using profile 101 and a 120-answer synthetic report. Its public JSON and ZIP were fetched without authentication at the same immutable HF commit and the ZIP checksum matched. [Evidence dataset](https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-20261105-b101-2a0ec28931).
- Live server health, readiness and persistent service identity passed after deployment. Model/configuration downloads, upload retry and checkpoint/import/resume remain available.

These are packaging, control-flow and transport checks. No new physical-phone GPU timing or answer-quality result is claimed. The README time budgets include 30 extra minutes per APK; configurations 5, 6 and 9 may still need another reservation. The temporary-tunnel and credential-renewal boundaries described below for v2.2 still apply.

---

# Benchmark 2.2 validation

Built November 5, 2026. Five ARM64 APKs use package `com.cemig.gpudiagnostic`, version code 8 / version 2.2. All labels, shared signatures, fixture hashes and absence of the cached HF account credential were verified. Exact asset checksums accompany the [v2.2.0 release](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.2.0).

## Automatic upload evidence

- All five release builds, debug/test builds, and `lintRelease` passed. The inference code, model artifacts, 4096/512 token settings and 120-question fixture are unchanged from 2.1.
- Android regression suite: **OK (28 tests)**, including the 1,080-answer synthetic queue, report recovery, batch selection and upload behavior. Four optional model/live-network tests are skipped in this default invocation.
- New relay tests verify encrypted capability persistence, isolation across batches/new sessions, preserving an offline checkpoint across uploader recreation, retry without duplicate registration, and distinguishing server receipt from completed HF publication.
- Android emulator → public HTTPS relay → real HF: **OK (1 test)**, using a 120-answer synthetic report and no HF credential on Android. A second invocation after adding HF endpoint discovery also passed. These are transport tests, not model speed or quality measurements.
- Independent workstation HTTPS checks passed for readiness/service identity, idempotent registration, isolation between two sessions, duplicate uploads, an older partial upload arriving after newer results, configuration snapshots, and malformed ZIP rejection. The resulting public HF JSON, latest ZIP and configuration ZIP were downloaded without authentication at one immutable commit and their checksums verified. [Evidence dataset](https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-20261105-b1-3bc9b3fd72).
- Server suite: **55 tests passed** on the upload server. Coverage includes upload validation, stream bounds, quotas, session isolation, worker retries, ordering, concurrency, catalog consistency and restart recovery. The added registration tests ensure oversized declared or chunked JSON is rejected before unbounded buffering; the deployed endpoint returned HTTP 413 in an independent live check.
- The server agent separately passed 14 live end-to-end checks and a live recovery scenario with the worker stopped and API restarted: a pending report survived and was published after the worker returned. [Recovery evidence](https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-20261105-b2-b087550d7f).
- The server's API, worker and HTTPS tunnel run in separate supervised tmux windows. Health/readiness and the service identity were rechecked after deploying the final server fix. No GPU is used by the upload server.

## Operational boundaries

The initial HTTPS address is a temporary Cloudflare quick tunnel. The HF index publishes its current URL; Android follows that discovery document while checking the server identity pinned in GitHub. Network outages retain local/server checkpoints for retry. Reboot startup is configured but has not been reboot-tested. The server HF credential expires November 26, 2026 and requires operator renewal; participants need no HF setup.

No new physical-phone GPU timing or semantic-quality result is claimed. Use these APKs for the next RTL/device runs and keep the app foreground until **Hugging Face synced** when possible.

---

# Benchmark 2.1 validation

Built November 5, 2026. Five ARM64 APKs use package `com.cemig.gpudiagnostic`, version code 7 / version 2.1. All five signatures verified and use the same certificate; their labels select batches 1–5. Each APK is 57,918,897 bytes. Exact checksums accompany the [v2.1.0 release](https://github.com/vinyteles/mobile-gpu-diagnostic/releases/tag/v2.1.0).

## Batch and checkpoint evidence

- All five release builds, the debug/test builds, and `lintRelease` passed. Model artifacts, the 120-question fixture, generation settings and inference code remain unchanged from 2.0. Every APK's fixture SHA-256 is `e43daa8d6887b372ad05a729c9dd53488fd29023bd228836c1e98060817c3cc4`.
- The emulator suite reported **OK (24 tests)**, including the complete nine-configuration queue with **1,080 synthetic answers**, ordering, import/resume, crash/timeout recovery, download integrity and checkpoint export. Three optional integration tests are skipped without supplied model/token inputs.
- The final targeted suite reported **OK (4 tests)**: encrypted credential storage/forgetting, failed-upload persistence and retry, and preset migration; the optional real-Hub test was skipped in this invocation.
- A separate real-Hub invocation reported **OK (1 test)**. Android uploaded a synthetic 120-answer ZIP and matching progress/configuration snapshots to [the public validation dataset](https://huggingface.co/datasets/vinyteles/mobilebench-validation-20261105). This validates transport, not model quality.
- The host watcher downloaded that public report **without authentication**, read JSON and ZIP at the same immutable commit, and verified the ZIP checksum. Parallel polling also handled an empty repository while processing the completed validation run.
- The HTTP failure test retained the pending ZIP across uploader recreation and succeeded on retry. It verified that the account token was not forwarded to the blob upload destination.
- An actual debug APK update from batch 1 to batch 2 reset the selected configurations to the batch 2 preset while preserving the saved queue and report files byte-for-byte. The dedicated migration regression also checks repository/credential preservation.
- Session creation was checked with a mocked HF API; Python compilation and shell syntax checks passed. Public repository creation/upload itself was exercised by the validation setup and Android integration.
- Staged source and all APKs were checked against the cached HF credential; source was also scanned for common secret patterns. Credentials and signing keys are not bundled. The temporary Android integration credential was deleted after the test.

## Remaining physical checks

The new upload/batch behavior has emulator and real-network coverage. **No new physical GPU timing or quality result is claimed.** Use the next RTL sessions to validate the nine configurations on the chosen phone. Two hours is a reservation limit, not a guaranteed batch completion time. Keep the app in the foreground and confirm its last-sync status before ending a reservation; import the latest ZIP when continuing on another device.

---

# Benchmark 2.0 validation

Built November 4, 2026. Deliverable: `artifacts/mobile-model-benchmark-2.0-arm64.apk` (57,886,129 bytes), package `com.cemig.gpudiagnostic`, version code 6 / version 2.0. SHA-256: `2abbdd590f096f5dd696f090c871c5df5af307a099c754f5dd7350132f2ea68b`. APK signature verification passed; minimum Android API 31, target 36, ARM64 release.

## Evidence

- Release/debug/test APK builds and `lintRelease` passed. Remaining lint advisories concern pinned dependency/SDK versions and existing UI conventions; there are no lint errors.
- The emulator suite reported **OK (20 tests)**, including the complete nine-configuration queue, 120 synthetic answers per configuration, GPU ordering, crash/timeout isolation, HTTP Range recovery, SHA-256 rejection, fixture categories, report import, and resume without modifying completed results. The two optional model tests are skipped in this default suite; real inference is exercised separately below.
- Final export regression reported **OK (4 tests)**: another complete synthetic queue, import/resume, invalid-report rejection, and verification that a published Downloads snapshot replaces the previous URI only after the new ZIP is readable.
- The complete synthetic queue produced **1,080 synthetic answers**; these are control-flow tests, not model-quality results. Imported reports retained all nine configurations. Resuming the completed queue left configuration records unchanged.
- Exact model-specific Jinja rendering was checked for **all 120 prompts** against LFM's pinned embedded template and the original Gemma Q8 GGUF's embedded template. Fully formatted inputs remain within 4096 context minus 512 output minus 128 template reserve. No retrieved passages were removed.
- SHA-256 verification of downloaded new artifacts matched the catalog: LFM LiteRT `327486bf…`, original Gemma mobile `18193810…`. Runtime/model revisions and exact checksums are included in APK assets and reports.

## Real inference checks

Android 15 x86_64 emulator, CPU, two actual benchmark prompts per artifact, 4096 context / 512 output:

| Artifact | Result | Engine lifetime |
|---|---|---|
| LFM3 Q4_K_M GGUF | Passed, 2 answers | One shared engine; attention/recurrent reset per question |
| LFM3 mixed4 LiteRT, ekv4096 | Passed, 2 answers | Two separate engines; both answers marked `engineReused=false`; measured load/close overhead |
| Energy-Mobile int8 LiteRT | Passed, 2 answers | One shared engine, fresh conversations |
| Energy-Mobile mixed48 LiteRT | Passed, 2 answers | One shared engine, fresh conversations |
| Original Gemma 4 E2B-IT mobile LiteRT | Passed, 2 answers | One shared engine, fresh conversations |

LFM native produced an answer longer than 256 tokens, exercising the increased output cap. These checks establish loading, generation, token metrics and session bookkeeping; they do not establish answer correctness or Samsung speed. The emulator was running other test/build processes, so its timing values must not be compared with phone benchmark numbers.

An initial original-Gemma check failed because the emulator lacked disk space to receive its file; it passed after temporary copies were removed. An initial Energy int8 load aborted with `XNNPack weight cache: cannot append buffer to cache file` when emulator storage was exhausted. Removing temporary cache/model copies resolved it. The APK now performs a conservative storage check before LiteRT initialization. Evidence of the native failure and the successful rerun is preserved in `artifacts/validation-v2.0/real-model-checks.tar`.

## Boundaries

- **No new Samsung GPU result is claimed.** Physical RTL execution is still required for all configurations under the new runtime/settings. Previously successful Energy GPU/LFM Vulkan configurations are first in the queue, followed by the three new GPUs, then CPU.
- Gemma Q8 is experimental and conditional on available system RAM. The emulator did not execute that 5.05 GB model; the application will record a skip when the conservative memory check fails. Driver/OOM failures remain possible after admission.
- This is a generation benchmark over frozen desktop MobileRAG+ retrieval/SCR contexts in `cemig-mobile`; it does not run retrieval on the phone or establish equivalence with the mobile Q8 embedding/int8 SCR path.
- The 120 wording variants represent 30 base questions, not 120 independent underlying facts. Shared base questions and fixed configuration order matter in statistical/thermal comparisons.
- References are evaluation only; no LLM judge was run as part of APK creation.
- LFM LiteRT is reloaded for every question because of the documented recurrent-state reuse issue. Its engine load/close and pre-close memory must be included when comparing full cost. Raw unexpected thinking channels are retained.
- Source changes are confined to the standalone diagnostic. The production `app-mobile` working tree remained clean. A pre-change source backup is at `backups/source-before-2.0-20261104.tar.gz`.

---

## Earlier versions

# Version 1.4 validation — GPU first

Validated November 4, 2026. Deliverable: `artifacts/lfm3-cpu-vulkan-benchmark-1.4-arm64.apk` (57,734,282 bytes), version code 5, same diagnostic package and signing key. SHA-256: `731f3fd21e42abfd2e04e6e14eb1836bb15b711381791ee9df79dd63f210b8d6`.

- Execution, screen cards, and report configuration order now put Vulkan GPU before CPU. Future diagnostic queues follow the same policy.
- The version 1.3 queue and completed answers are preserved on update. Each execution attempt records app version and backend order; original metadata and engine sessions remain intact.
- A GPU snapshot is automatically saved to Downloads as `*-after-vulkan.zip` before CPU starts, including GPU failure/cancellation results. Export failure is shown without preventing CPU from running. Final full export and manual save/share remain available. Saving on the phone is not a transfer to the user's computer.
- Release/debug builds and lint passed (0 errors, 14 warnings). All 13 existing download/checkpoint/replay/worker instrumentation tests passed. Signature and 16 KB APK alignment verified.
- Emulator upgrade/resume test started with a version 1.3 run containing two completed synthetic CPU answers. Version 1.4 ran all 30 synthetic GPU answers first. The automatic GPU snapshot contained GPU 30/30 and exactly the two unchanged old CPU answers. CPU then resumed the remaining 28, with a new engine session. The final exported ZIP contained all 60 synthetic answers; the prior two were unchanged, and the new CPU session began after GPU finished. Both exports and screenshots are saved under `artifacts/validation-v1.4/`.
- Model/runtime pins, retrieval fixture, and ARM64 JNI inference library are byte-identical to version 1.3. No inference behavior changed; version 1.3's real-model validation still applies. This version's UI run was synthetic and does not establish Samsung GPU support or speed.

The user observed roughly 40 seconds per LFM3 CPU question (about 20 minutes per 30-question batch). Plan 45–60 minutes for both configurations and download the GPU ZIP while CPU runs. The expired RTL session's report was not recovered.

---

# Version 1.3 validation — LFM3 CPU and Vulkan

Validated locally on November 4, 2026. Deliverable: `artifacts/lfm3-cpu-vulkan-benchmark-1.3-arm64.apk` (57,717,898 bytes), package `com.cemig.gpudiagnostic`, version code 4. SHA-256: `bc5651cfd58db700c86a960970e63790b4b2c9bed69deb78b6aa412920608732`.

- Release/debug builds passed; Android lint reports 0 errors and 14 warnings. Release APK is ARM64, Android 12+, with the same signing certificate as earlier diagnostics. ZIP/native-library 16 KB alignment and the APK signature were verified. ARM CPU kernels target ARMv8.2 dot-product/FP16; JNI checks these capabilities before invoking the backend.
- All 17 instrumentation tests passed with the real LFM3 model supplied. Coverage includes download ranges/checksums, worker crashes/timeouts, checkpoint/resume/session labels, exact question/reference availability, citation range checks, invalid native model loading, the concurrent AtomicFile regression, two real RAG answers, and A/B/A recurrent/attention-state reset. The A/B/A check produced identical greedy output for the repeated question.
- The real CPU replay used the pinned Q4_K_M model on the Android 15 x86_64 emulator, one engine and two saved Portuguese RAG prompts. Actual prefill counts were 1,591 and 1,501, with 234 and 118 output tokens. Both answers were nonempty, the second was marked as engine reuse, and native metrics plus formatted-prompt hashes were preserved. These are integration checks, not Samsung latency or semantic accuracy results.
- A separate real Vulkan request correctly produced `No Vulkan GPU device available; CPU fallback is disabled`. The emulator exposes no usable device to this Vulkan backend. Its failure checkpoint, native log and initialization phase were retained. **Physical S25 FE Vulkan support and performance are not yet validated.**
- The UI completed the synthetic download/verification sequence and both 30-question configurations, then automatically exported a ZIP to Downloads. The ZIP was pulled and verified to contain exactly 60 explicitly synthetic answers, two correctly labeled sessions, 30 reference/context records, the byte-identical frozen fixture, native-build provenance, the exact embedded chat template, and preparation timestamps. This is not a 60-answer model-performance run.
- Preparation, progress cards, expandable GPU metrics, and completion were visually inspected. Screenshots and test/export evidence are under `artifacts/validation-v1.3/`. The emulator had an old System UI startup ANR from boot, unrelated to the app; it was dismissed before UI inspection.
- The LFM3 prompt renderer matched the pinned GGUF's embedded Jinja template for all 30 prompts with thinking disabled. Source-tokenizer lengths were 1,340–1,741; all fit in the 2,048-token context with 256 output tokens reserved. Runtime counts are authoritative. The packaged retrieval fixture SHA-256 remains `3a5ed24a726e53d53b5056b8849f9581a6dcf8f76ff54513da9418022fd38e77`.
- The previously observed checkpoint race is fixed: readers open the committed inode without deleting `.new`. The regression reads the old JSON repeatedly while a new AtomicFile write is open, verifies staging survives, then verifies the new JSON after commit.

The APK is ready for RTL: download one 1.10 GB model, run CPU 30/30 then Vulkan 30/30, and export the report even if GPU fails. Reserve 30 minutes initially; actual Samsung duration remains unmeasured. Existing production apps, earlier APKs, and collected Gemma answers were not changed.

---

# Version 1.2 validation

Validated on November 4, 2026. Deliverable: `artifacts/gemma-gpu-continuation-1.2-arm64.apk`, package `com.cemig.gpudiagnostic`, version code 3. SHA-256: `d1120215b65088778d2a40e650203ddd62820efe199472f77d827f290de38c4b`.

- Release/debug builds and Android lint passed. APK signing certificate matches earlier diagnostic versions; signature and 16 KB alignment verified. Release native libraries are ARM64 only.
- All 12 standard instrumentation tests passed. The additional selection test verified that only original question indices 10–29 run, none of the first ten GPU questions repeats, a single engine session serves the subset, and the joined export contains exactly the selected 20 questions.
- A real mixed48 CPU integration check on the x86_64 Android emulator answered the first two remaining prompts (original indices 10 and 11). It verified separate conversations with one engine, a reused-engine second question, native token metrics, bounded output and nonempty answers. Both results contain live elapsed/partial-answer checkpoints. It is not a new physical-GPU validation.
- The debug UI completed a synthetic preparation → 20-question GPU queue → automatic Downloads export. The pulled ZIP contains exactly one configuration, only the mixed48 model identity, all 20 selected outputs with reference answers/context, and preparation-phase timestamps. Synthetic outputs are explicitly labeled and are not performance evidence.
- Both screens and expanded metrics were visually inspected. Progress circles show percentage/completion; the benchmark card expands/collapses. The completed screen now remains idle between polls when values have not changed, verified through UI Automator.
- The selected-ID manifest and full prompt fixture in the release APK were verified against local snapshots. Source-report/fixture/model hashes tie selection to `reports/rag-1793807018113/report.json`.
- Evidence and screenshots are in `artifacts/validation-v1.2/`.

The physical Samsung must still run the remaining GPU questions. Earlier answers are not imported into the new session or its timing aggregates. Only the mixed48 model is downloaded (2.03 GB if absent); verified copies are reused. Existing app sources and previous reports remain unchanged.

---

# Version 1.1 validation

Validated locally on November 4, 2026. Deliverable: `artifacts/gemma-rag-benchmark-1.1-arm64.apk` (30 MB), package `com.cemig.gpudiagnostic`, version code 2. SHA-256: `da371f9602b01649d285b2a929690dea8afbe7e9302471bee17df41423326223`.

- Release build and Android lint passed (0 errors, 11 warnings). APK signature and 16 KB alignment verified. ARM64 only, Android 12+. Its signing certificate matches version 1.0, allowing an update that retains model downloads and earlier reports.
- All 11 standard instrumentation tests passed, including four replay tests: exact fixture/reference availability, checkpoint recovery after native process death, resumed session labeling/export joins, per-question error retention, and structural citation checks. The existing seven download/worker tests also passed.
- Both actual model files were separately exercised on an Android 15 x86_64 emulator, CPU: two real Portuguese RAG questions each, one engine session per model, separate conversations, nonempty answers, runtime prefill counts above 1,000 and decode counts within 256. The second question was correctly marked as engine reuse. These are integration checks, not a scored quality evaluation or Samsung timing measurements.
- The actual UI completed the debug-only synthetic three-configuration queue, automatically saved a ZIP to Downloads, and that ZIP was pulled and inspected. All 30 reference/context records joined with three configuration slots; nine synthetic answers were explicitly labeled. The readable comparison, native logs and exact fixture/hash were present.
- The packaged fixture was verified byte-for-byte against the generated input. All 30 reference answers/evidence matched the source benchmark by exact question ID and text. Fixture prompts are preserved verbatim. Expected answers are not passed to inference.
- Evidence is under `artifacts/validation-v1.1/`. Synthetic exports must not be used as model performance or quality evidence.

The full 90-answer run, GPU engine reuse, phone-specific throughput, thermals and answer accuracy still require the Samsung run. One fixed-order pass cannot eliminate thermal/order effects. The diagnostic uses LiteRT-LM 0.18.0 for all three configurations; it does not reproduce the old React Native runtime or measure retrieval/embedding time. Model answers are not automatically semantically graded; the ZIP contains the benchmark reference and retrieved passages for assessment.

Neither `app-mobile` nor `cemig-mobile` application source was changed. The original 1.0 APK and evidence remain available.

---

# Diagnostic APK validation

Validated locally on November 4, 2026. The deliverable is `artifacts/gemma-gpu-diagnostic-1.0-arm64.apk`, application ID `com.cemig.gpudiagnostic`, version 1.0. The release contains ARM64 native code and requires Android 12 or newer.

## Completed checks

- Release build passed, APK signature verified, and 16 KB page-alignment check passed.
- Android lint passed with no errors. Remaining warnings concern pinned dependency versions, target SDK, backup rules, and conservative storage-space checking.
- Seven instrumentation tests passed: worker completion, process-death recovery, timeout recovery, error/ZIP export, HTTP Range resumption, ignored-Range restart, and checksum rejection.
- The automatic synthetic queue was exercised through the actual screen. It survived a killed worker, continued to later cases, and saved a ZIP to Downloads. The exported ZIP was retrieved and inspected.
- A separate real-model integration test used the exact downloaded mixed 4/8-bit model, verified against SHA-256 `63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1`.
- The real model answered the CPU short prompt correctly: `The capital of France is Paris.`
- The CPU long prompt returned `CEMIG-GPU-731`, the expected marker. The runtime reported 1,532 prefill tokens and 22 decode tokens.
- The real GPU request initialized on the emulator and then failed during generation with `Can not find OpenCL library on this device`. The worker preserved the exception, phase, driver information, and native logs. No application-level CPU retry occurred.

The optional real-model instrumentation test is skipped in the standard test suite when no model path is provided. It was run explicitly for CPU short, CPU long, and GPU short. Evidence is in `artifacts/validation/`.

## Limits

The real-model checks used an x86_64 Android 15 emulator with SwiftShader, not a Samsung GPU. Its GPU failure does not explain or predict the S25 FE's failure. The physical-device run remains necessary.

Only the mixed 4/8-bit model received local end-to-end inference checks. The APK automatically tests both mixed 4/8-bit and int8 on the phone. Both artifact URLs, sizes, and checksums were verified during the preceding model audit.

Runtime-reported benchmark fields and externally observed wall-clock timings are recorded separately and can differ. No token-rate estimate is derived from the number of streaming callbacks. These single-run diagnostics are not a controlled performance comparison against the existing app.

The diagnostic was built in its own sibling directory. Neither `app-mobile/main` nor the sibling `cemig-mobile` application source was modified.
