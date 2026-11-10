package com.cemig.gpudiagnostic

/** A pinned public artifact. SHA-256 is checked before any inference process opens it. */
data class ModelSpec(val id: String, val label: String, val url: String, val bytes: Long, val sha256: String, val extension: String = "litertlm")

val LFM_MODEL = ModelSpec("lfm3-q4km", "LFM3 1.7B · Q4_K_M",
    "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/cd0c74c575b5c416f3011b5b4c298077f26ca8c2/LFM3-1.7B-Q4_K_M.gguf",
    1099627808L, "3793e1e6e2266919d6e015287221b41100175a98eddaba92e111aed3b05c93c4", "gguf")

val LFM_TUNED_Q4KM = ModelSpec("lfm3-tuned-q4km", "LFM3 1.7B fine-tuned · Q4_K_M",
    "https://huggingface.co/juniorrios/automobil-ultra-semnexo/resolve/aff14d91b1b8922e67d348afbbc783759477a641/cemig-lfm3-1.7b-nr-Q4_K_M.gguf",
    1099627040L, "487c5a5530a599acc1827fffbf1b1fa17a5cdbe2f31082632fa3c572bdfcc8f3", "gguf")
val LFM_TUNED_Q4 = ModelSpec("lfm3-tuned-q4", "LFM3 1.7B fine-tuned · Q4_0",
    "https://huggingface.co/juniorrios/automobil-ultra-semnexo/resolve/aff14d91b1b8922e67d348afbbc783759477a641/cemig-lfm3-1.7b-nr-Q4_0.gguf",
    1049614880L, "94847d5bbcaae51a3cf63c6aed0063922e008521267d4ccdd364bf8533e85050", "gguf")

val LFM_RLVR_Q4 = ModelSpec("lfm3-rlvr-v3s400-q4", "LFM3 1.7B RLVR v3s400 · Q4_0",
    "https://huggingface.co/juniorrios/automobil-ultra-semnexo/resolve/682e55452123b5451fea11be0fc46ab2a4aa1c17/v1.1-rlvr/cemig-lfm3-1.7b-nr-v3s400-Q4_0.gguf",
    1049614880L, "689735f30a195be407fc56c5c4b7b27e3043c37b5ff2e58480477cda5992d6d8", "gguf")

// Run GPU configurations before CPU so limited RTL sessions collect accelerator results first.
val LFM_BACKENDS = listOf("vulkan", "cpu")
fun lfmCaseId(backend: String) = "lfm3-q4km-$backend-replay"

data class ProbeSpec(val model: ModelSpec, val backend: String, val workload: String) {
    val id get() = "${model.id}-$backend-$workload"
    val prompt get() = if (workload == "short") SHORT_PROMPT else LONG_PROMPT
}

val MODELS = listOf(
    ModelSpec("gemma-mixed48", "Gemma Energy 4/8-bit",
        "https://storage.googleapis.com/modelo-mobile/models/chat/gemma-energy-mixed48/model_mixed48.litertlm",
        2031107344L, "63a7374c71b404eb4f8cf8322beca2bc862061cba29adb368131c73e198f59e1"),
    ModelSpec("gemma-int8", "Gemma Energy int8",
        "https://storage.googleapis.com/modelo-mobile/releases/2026-08-19-lexical.1/models/chat/model_int8.litertlm",
        2972388640L, "1a4a87c0f00b191cc4136f783e17640cc5ab58fc4c2a0d3ec21348c0b98d2e54"),
)

const val CONTEXT_TOKENS = 4096
const val OUTPUT_TOKENS = 512
const val QUESTION_COUNT = 120
const val REPLAY_PROTOCOL_ID = "mobile-rag-replay-120-v2"
const val TOOL_PROTOCOL_ID = "mobile-rag-tools-120-v1"
const val V3_PROTOCOL_ID = "mobile-rag-pipelines-120-v3"
val PROTOCOL_ID = when {
    BuildConfig.BENCHMARK_PROFILE >= 116 -> V3_PROTOCOL_ID
    BuildConfig.BENCHMARK_PROFILE >= 110 -> TOOL_PROTOCOL_ID
    else -> REPLAY_PROTOCOL_ID
}

val LFM_LITERT = ModelSpec("lfm3-mixed4-litert", "LFM3 1.7B · LiteRT mixed 4/8-bit",
    "https://huggingface.co/litert-community/LFM3-1.7B-LiteRT/resolve/29f09e1eac2ccf753af14806dca96eb048e8baad/LFM3-1.7B_mixed_int4_ekv4096.litertlm",
    1441629120L, "327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b")
val GEMMA_BASE = ModelSpec("gemma-base-mixed248", "Gemma 4 E2B-IT · mobile 2/4/8-bit",
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/c658da437244f072bf2f1cabc916b60d66d97663/gemma-4-E2B-it.litertlm",
    2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
val GEMMA_Q8 = ModelSpec("gemma-base-q8", "Gemma 4 E2B-IT · Q8_0 (experimental)",
    "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/0314792d7f1f7e229411f620751375812bb9faf2/gemma-4-E2B-it-Q8_0.gguf",
    5048352864L, "605d3c2647d7c58c1e4b5375ccb5702acf94c2611b4c8d4877812f8fdd32d053", "gguf")

data class BenchmarkConfig(val model: ModelSpec, val backend: String, val tested: Boolean = false,
                           val freshEngine: Boolean = false, val memoryConditional: Boolean = false,
                           val retrieval: String = "mobileragplus", val matrixCase: String = "", val toolPolicy: String = "", val displayNumber: Int = 0) {
    val isV3 get() = toolPolicy.isNotEmpty()
    val liveTools get() = isV3 || matrixCase.isNotEmpty()
    val id get() = if (isV3) "${model.id}-$backend-$retrieval-$toolPolicy-v3" else if (liveTools) "${model.id}-$backend-$retrieval-tools-v1" else "${model.id}-$backend-replay"
    val fixtureAsset get() = if (isV3) "v3-questions.json" else if (liveTools) "tool-questions.json" else "rag-replay.json"
    val runtime get() = if (model.extension == "gguf") "llama.cpp" else "LiteRT-LM"
    val isGpu get() = backend != "cpu"
    val label get() = "${model.label} · ${if (backend == "vulkan") "Vulkan GPU" else backend.uppercase()}" +
        if (isV3) " · Config $displayNumber · $retrieval · $toolPolicy" else if (matrixCase.isNotEmpty()) " · Case $matrixCase · ${if (retrieval == "f6") "F6" else "MobileRAG+"}" else ""
}

// Established GPU paths first, experimental GPUs next, every CPU configuration last.
val BENCHMARK_CONFIGS = listOf(
    BenchmarkConfig(MODELS[0], "gpu", tested = true),
    BenchmarkConfig(LFM_MODEL, "vulkan", tested = true),
    BenchmarkConfig(LFM_LITERT, "gpu", freshEngine = true),
    BenchmarkConfig(GEMMA_BASE, "gpu"),
    BenchmarkConfig(GEMMA_Q8, "vulkan", memoryConditional = true),
    BenchmarkConfig(LFM_MODEL, "cpu", tested = true),
    BenchmarkConfig(MODELS[1], "cpu", tested = true),
    BenchmarkConfig(MODELS[0], "cpu", tested = true),
    BenchmarkConfig(GEMMA_BASE, "cpu"),
    BenchmarkConfig(LFM_MODEL, "vulkan", matrixCase = "A"),
    BenchmarkConfig(LFM_TUNED_Q4KM, "vulkan", matrixCase = "B"),
    BenchmarkConfig(LFM_MODEL, "vulkan", retrieval = "f6", matrixCase = "C"),
    BenchmarkConfig(LFM_TUNED_Q4KM, "vulkan", retrieval = "f6", matrixCase = "D"),
    BenchmarkConfig(LFM_TUNED_Q4, "vulkan", matrixCase = "E"),
    BenchmarkConfig(LFM_TUNED_Q4, "vulkan", retrieval = "f6", matrixCase = "F"),
    BenchmarkConfig(MODELS[0], "gpu", retrieval = "mobileragplus", toolPolicy = "energy", displayNumber = 17),
    BenchmarkConfig(MODELS[0], "gpu", retrieval = "f6", toolPolicy = "energy", displayNumber = 18),
    BenchmarkConfig(GEMMA_BASE, "gpu", retrieval = "mobileragplus", toolPolicy = "flag", displayNumber = 19),
    BenchmarkConfig(GEMMA_BASE, "gpu", retrieval = "f6", toolPolicy = "flag", displayNumber = 20),
    BenchmarkConfig(LFM_TUNED_Q4KM, "vulkan", retrieval = "mobileragplus", toolPolicy = "lfm", displayNumber = 21),
    BenchmarkConfig(LFM_TUNED_Q4KM, "vulkan", retrieval = "f6", toolPolicy = "lfm", displayNumber = 22),
    BenchmarkConfig(LFM_RLVR_Q4, "vulkan", retrieval = "mobileragplus", toolPolicy = "lfm", displayNumber = 23),
    BenchmarkConfig(LFM_RLVR_Q4, "vulkan", retrieval = "f6", toolPolicy = "lfm", displayNumber = 24),
)
val BENCHMARK_MODELS = BENCHMARK_CONFIGS.map { it.model }.distinctBy { it.id }

const val SHORT_PROMPT = "Reply in English in one short sentence. What is the capital of France?"

// Synthetic evidence avoids dependence on a retrieval database. Actual input tokens come from the runtime.
val LONG_PROMPT = buildString {
    append("Read these fictional maintenance records. They are test data, not safety instructions.\n\n")
    for (number in 1..30) {
        append("Record $number: The inspection team visited station Cedar during the morning shift. ")
        append("They recorded the equipment identifier, checked the document list, and filed the observation in the local register. ")
        append("The next review is scheduled for the following month.\n")
        if (number == 15) append("The unique verification marker is CEMIG-GPU-731.\n")
    }
    append("\nQuestion: What is the unique verification marker? Reply in English with the marker and one brief sentence. Do not summarize the records.")
}

fun probes(model: ModelSpec) = listOf(
    ProbeSpec(model, "cpu", "short"), ProbeSpec(model, "gpu", "short"),
    ProbeSpec(model, "cpu", "rag"), ProbeSpec(model, "gpu", "rag"),
)

val BENCHMARK_BATCH_NAME = when (BuildConfig.BENCHMARK_PROFILE) {
    101 -> "Config 1 · Energy mixed 4/8-bit GPU"
    102 -> "Config 2 · LFM3 Q4_K_M Vulkan GPU"
    103 -> "Config 3 · LFM3 LiteRT GPU"
    104 -> "Config 4 · Gemma 4 E2B-IT mobile GPU"
    105 -> "Config 5 · Gemma 4 E2B-IT Q8 Vulkan GPU"
    106 -> "Config 6 · LFM3 Q4_K_M CPU"
    107 -> "Config 7 · Energy int8 CPU"
    108 -> "Config 8 · Energy mixed 4/8-bit CPU"
    109 -> "Config 9 · Gemma 4 E2B-IT mobile CPU"
    110 -> "Case A · LFM3 base Q4_K_M · MobileRAG+ GPU"
    111 -> "Case B · LFM3 tuned Q4_K_M · MobileRAG+ GPU"
    112 -> "Case C · LFM3 base Q4_K_M · F6 GPU"
    113 -> "Case D · LFM3 tuned Q4_K_M · F6 GPU"
    114 -> "Case E · LFM3 tuned Q4_0 · MobileRAG+ GPU"
    115 -> "Case F · LFM3 tuned Q4_0 · F6 GPU"
    116 -> "Config 17 · ENERGY · mobileragplus · v3"
    117 -> "Config 18 · ENERGY · f6 · v3"
    118 -> "Config 19 · FLAG · mobileragplus · v3"
    119 -> "Config 20 · FLAG · f6 · v3"
    120 -> "Config 21 · LFM · mobileragplus · v3"
    121 -> "Config 22 · LFM · f6 · v3"
    122 -> "Config 23 · LFM · mobileragplus · v3"
    123 -> "Config 24 · LFM · f6 · v3"
    else -> error("Unknown APK configuration")
}
// Each APK exposes and runs exactly one configuration. Keep the full catalog for report imports.
val APK_CONFIGS = listOf(BENCHMARK_CONFIGS[BuildConfig.BENCHMARK_PROFILE - 101])
val APK_FIXTURE_ASSET = APK_CONFIGS.single().fixtureAsset
val APK_MODELS = APK_CONFIGS.map { it.model }
val BATCH_CONFIG_IDS = APK_CONFIGS.map { it.id }.toSet()
