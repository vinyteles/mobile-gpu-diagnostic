package com.cemig.gpudiagnostic

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

const val HELLO = 1
const val RUN = 2
const val READY = 10
const val DONE = 11
const val QUESTION_COMPLETED = 12
const val CHECKPOINT_QUEUED = 13

/** One single test or one reusable-engine batch per disposable process. Native aborts cannot erase the controller's report. */
class InferenceService : Service() {
    private var started = false
    private val pendingCheckpoint = AtomicReference<Pair<Int, CountDownLatch>?>(null)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { request ->
        when (request.what) {
            HELLO -> request.replyTo.send(android.os.Message.obtain(null, READY).apply { arg1 = Process.myPid() })
            RUN -> if (!started) {
                started = true
                val arguments = request.data
                val client = request.replyTo
                Thread({
                    execute(arguments, client)
                    runCatching { client.send(android.os.Message.obtain(null, DONE)) }
                }, "inference-worker").start()
            }
            CHECKPOINT_QUEUED -> pendingCheckpoint.get()?.takeIf { it.first == request.arg1 }?.second?.countDown()
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    @OptIn(ExperimentalApi::class)
    private fun execute(arguments: Bundle, client: Messenger) {
        val caseFile = File(arguments.getString("caseFile")!!)
        val result = readJson(caseFile) ?: JSONObject()
        fun checkpoint(phase: String) {
            result.put("phase", phase).put("updatedAtMs", System.currentTimeMillis())
                .put("workerPid", Process.myPid()).put("memory", memorySnapshot(this))
            writeJson(caseFile, result)
            if (phase == "question_completed" && arguments.getBoolean("checkpointPerQuestion")) {
                // Queue this saved answer before the next question; never wait for the network.
                val sequence = result.getInt("currentQuestion")
                val queued = CountDownLatch(1)
                pendingCheckpoint.set(sequence to queued)
                runCatching {
                    client.send(android.os.Message.obtain(null, QUESTION_COMPLETED).apply { arg1 = sequence })
                    queued.await(60, TimeUnit.SECONDS)
                }
                pendingCheckpoint.set(null)
            }
        }

        try {
            // 1. Save progress before touching drivers or the model runtime.
            result.put("status", "running").put("engineInitialized", false)
            result.remove("engineLoadMs")
            checkpoint("hardware_probe")
            if (BuildConfig.DEBUG && arguments.containsKey("smoke")) {
                if (arguments.getString("smoke")!!.startsWith("replay_")) {
                    result.put("smokeTest", true)
                    runReplay(this, null, result, ::checkpoint, arguments.getInt("questionLimit", 3), arguments.getString("smoke"))
                    result.put("status", "completed")
                    checkpoint("done")
                    return
                }
                when (arguments.getString("smoke")) {
                    "crash" -> { Process.killProcess(Process.myPid()); return }
                    "hang" -> { CountDownLatch(1).await(); return }
                    "error" -> error("Injected worker error for recovery test")
                    "success" -> {
                        result.put("status", "completed").put("answer", "Synthetic smoke result; no model was run.").put("smokeTest", true)
                        checkpoint("done")
                        return
                    }
                }
            }
            result.put("gpuInfo", gpuInfo())
            checkpoint("engine_initializing")

            if (arguments.getString("runtime") == "llama.cpp") {
                val loadStart = SystemClock.elapsedRealtimeNanos()
                LlamaEngine(arguments.getString("modelPath")!!, arguments.getString("backend")!!, arguments.getString("modelFamily") ?: "lfm").use { llama ->
                    result.put("engineLoadMs", (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0)
                        .put("engineInitialized", true).put("runtime", "llama.cpp")
                        .put("runtimeRevision", BuildConfig.LLAMA_REVISION).put("nativeEngineInfo", llama.info)
                        .put("backendEvidence", llama.info.getString("backendEvidence"))
                    checkpoint("conversation_initializing")
                    runReplay(this, null, result, ::checkpoint,
                        if (BuildConfig.DEBUG) arguments.getInt("questionLimit", QUESTION_COUNT) else QUESTION_COUNT, llama = llama)
                }
                result.put("status", "completed")
                checkpoint("done")
                return
            }

            // 2. Request exactly one backend. There is no application-level CPU retry.
            Engine.setNativeMinLogSeverity(LogSeverity.INFO)
            ExperimentalFlags.enableBenchmark = true
            ExperimentalFlags.enableSpeculativeDecoding = false
            val requested = arguments.getString("backend")!!
            val backend = if (requested == "gpu") Backend.GPU() else Backend.CPU(threadCount = 4)
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val engineCache = File(arguments.getString("engineCache")!!).apply { mkdirs() }
            result.put("cacheFilesBeforeLoad", engineCache.listFiles()?.size ?: 0)
            fun createEngine() = Engine(EngineConfig(
                modelPath = arguments.getString("modelPath")!!,
                backend = backend,
                maxNumTokens = CONTEXT_TOKENS,
                cacheDir = engineCache.absolutePath,
            ))
            if (arguments.getBoolean("freshEnginePerQuestion")) {
                runReplay(this, null, result, ::checkpoint,
                    if (BuildConfig.DEBUG) arguments.getInt("questionLimit", QUESTION_COUNT) else QUESTION_COUNT,
                    freshEngineFactory = ::createEngine)
                result.put("status", "completed")
                checkpoint("done")
                return
            }
            val engine = createEngine()
            engine.use {
                engine.initialize()
                result.put("engineLoadMs", (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0)
                    .put("engineInitialized", true)
                    .put("backendEvidence", "Direct LiteRT-LM ${backend.name} engine initialized. No app fallback. Per-operation placement is not measured.")
                checkpoint("conversation_initializing")

                if (arguments.getBoolean("replay")) {
                    runReplay(this, engine, result, ::checkpoint,
                        if (BuildConfig.DEBUG) arguments.getInt("questionLimit", QUESTION_COUNT) else QUESTION_COUNT)
                } else {
                    // 3. Stream a bounded answer and obtain real token counts from runtime metrics.
                    engine.createConversation(ConversationConfig(
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                        maxOutputToken = 96,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        enableSpeculativeDecoding = false,
                    )).use { conversation ->
                        val text = StringBuilder()
                        val finished = CountDownLatch(1)
                        val error = AtomicReference<Throwable?>(null)
                        var firstResponseNs = 0L
                        var firstTextNs = 0L
                        var callbackCount = 0
                        val generationStart = SystemClock.elapsedRealtimeNanos()
                        checkpoint("generating")
                        conversation.sendMessageAsync(arguments.getString("prompt")!!, object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val now = SystemClock.elapsedRealtimeNanos()
                                if (firstResponseNs == 0L) firstResponseNs = now
                                val chunk = message.toString()
                                if (chunk.isNotEmpty() && firstTextNs == 0L) firstTextNs = now
                                text.append(chunk)
                                callbackCount++
                            }
                            override fun onDone() { finished.countDown() }
                            override fun onError(throwable: Throwable) { error.set(throwable); finished.countDown() }
                        })
                        if (!finished.await(180, TimeUnit.SECONDS)) {
                            conversation.cancelProcess()
                            error("Generation exceeded 180 seconds")
                        }
                        error.get()?.let { throw it }
                        result.put("generationMs", (SystemClock.elapsedRealtimeNanos() - generationStart) / 1_000_000.0)
                            .put("answer", text.toString()).put("streamCallbackCount", callbackCount)
                            .put("firstResponseMs", if (firstResponseNs == 0L) JSONObject.NULL else (firstResponseNs - generationStart) / 1_000_000.0)
                            .put("firstVisibleTextMs", if (firstTextNs == 0L) JSONObject.NULL else (firstTextNs - generationStart) / 1_000_000.0)
                        runCatching {
                            val metrics = conversation.getBenchmarkInfo()
                            JSONObject().put("initSeconds", metrics.initTimeInSecond)
                                .put("timeToFirstTokenSeconds", metrics.timeToFirstTokenInSecond)
                                .put("prefillTokens", metrics.lastPrefillTokenCount)
                                .put("decodeTokens", metrics.lastDecodeTokenCount)
                                .put("prefillTokensPerSecond", metrics.lastPrefillTokensPerSecond)
                                .put("decodeTokensPerSecond", metrics.lastDecodeTokensPerSecond)
                        }.onSuccess { result.put("runtimeMetrics", it) }
                            .onFailure { result.put("runtimeMetricsError", it.toString()) }
                        val expected = if (arguments.getString("workload") == "short") "Paris" else "CEMIG-GPU-731"
                        result.put("sanityExpected", expected).put("sanityPassed", text.contains(expected, ignoreCase = true))
                        check(text.isNotBlank()) { "Engine completed without visible answer text" }
                        checkpoint("engine_closing")
                    }
                }
            }
            result.put("status", "completed")
            checkpoint("done")
        } catch (error: Throwable) {
            result.put("status", "error").put("error", error.toString()).put("stackTrace", error.stackTraceToString())
            result.put("failedPhase", result.optString("phase"))
            checkpoint("failed")
        }
    }
}
