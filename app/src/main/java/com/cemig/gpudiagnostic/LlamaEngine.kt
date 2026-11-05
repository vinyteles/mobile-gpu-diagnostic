package com.cemig.gpudiagnostic

import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** JNI calls are owned by the inference thread; the controller can terminate its worker process. */
internal object LlamaNative {
    init { System.loadLibrary("lfm_diagnostic") }
    external fun open(path: ByteArray, gpu: Boolean): Long
    external fun info(handle: Long): ByteArray
    external fun begin(handle: Long, prompt: ByteArray): Int
    external fun next(handle: Long): ByteArray?
    external fun metrics(handle: Long): ByteArray
    external fun close(handle: Long)
}

/** Exact text-only, system+user rendering of the pinned LFM3 template with enable_thinking=false. */
fun lfmPrompt(system: String, user: String): String =
    "<|startoftext|><|im_start|>system\n$system<|im_end|>\n" +
        "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think></think>"

/** Exact system+user rendering of Google's pinned Gemma 4 template, thinking disabled. */
fun gemmaPrompt(system: String, user: String): String =
    "<bos><|turn>system\n${system.trim()}<turn|>\n<|turn>user\n${user.trim()}<turn|>\n<|turn>model\n"

class LlamaEngine(path: String, backend: String, private val family: String = "lfm") : AutoCloseable {
    private val handle: Long
    init {
        require(backend in LFM_BACKENDS) { "Unknown LFM backend: $backend" }
        handle = LlamaNative.open(path.toByteArray(Charsets.UTF_8), backend == "vulkan")
        check(handle != 0L)
    }
    val info get() = JSONObject(LlamaNative.info(handle).toString(Charsets.UTF_8))

    /** Generate from the same frozen content, measuring template setup, prefill and visible output separately. */
    fun answer(system: String, prompt: String, progress: (JSONObject) -> Unit): JSONObject {
        // 1. Format only the two inference messages; reference answers never enter the model.
        val setupStart = SystemClock.elapsedRealtimeNanos()
        val formatted = (if (family == "gemma") gemmaPrompt(system, prompt) else lfmPrompt(system, prompt)).toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(formatted).joinToString("") { "%02x".format(it.toInt() and 255) }
        val setupMs = (SystemClock.elapsedRealtimeNanos() - setupStart) / 1_000_000.0
        val start = SystemClock.elapsedRealtimeNanos()
        LlamaNative.begin(handle, formatted)

        // 2. Decode up to 512 tokens, retaining raw bytes across UTF-8 token boundaries.
        val bytes = ByteArrayOutputStream()
        var count = 0
        var firstTokenNs = 0L
        var firstTextNs = 0L
        var lastUpdate = start
        while (count < OUTPUT_TOKENS) {
            val piece = LlamaNative.next(handle) ?: break
            val now = SystemClock.elapsedRealtimeNanos()
            if (firstTokenNs == 0L) firstTokenNs = now
            bytes.write(piece); count++
            if (firstTextNs == 0L && bytes.size() > 0) firstTextNs = now
            if (now - lastUpdate >= 1_000_000_000) {
                progress(JSONObject().put("partialAnswer", bytes.toString("UTF-8"))
                    .put("streamCallbackCount", count).put("lastTextAtMs", System.currentTimeMillis())
                    .put("elapsedGenerationMs", (now - start) / 1_000_000)
                    .put("firstVisibleTextMs", if (firstTextNs == 0L) JSONObject.NULL else (firstTextNs - start) / 1_000_000.0))
                lastUpdate = now
            }
        }
        val generationMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val answer = bytes.toString("UTF-8")
        check(answer.isNotBlank()) { "Model completed without visible answer text" }

        // 3. Keep native compute throughput separate from wall time and checkpoint overhead.
        val metrics = JSONObject(LlamaNative.metrics(handle).toString(Charsets.UTF_8))
            .put("timeToFirstTokenSeconds", (firstTokenNs - start) / 1_000_000_000.0)
        return JSONObject().put("answer", answer).put("conversationInitMs", setupMs)
            .put("generationMs", generationMs).put("questionMs", setupMs + generationMs)
            .put("firstResponseMs", (firstTokenNs - start) / 1_000_000.0)
            .put("firstVisibleTextMs", (firstTextNs - start) / 1_000_000.0)
            .put("questionToFirstTextMs", setupMs + (firstTextNs - start) / 1_000_000.0)
            .put("streamCallbackCount", count).put("runtimeMetrics", metrics)
            .put("formattedPromptSha256", hash).put("outputTokenLimitReached", count == OUTPUT_TOKENS)
            .put("stopReason", if (count == OUTPUT_TOKENS) "output_limit" else "end_of_generation")
    }

    override fun close() { LlamaNative.close(handle) }
}
