package com.cemig.gpudiagnostic

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.google.ai.edge.litertlm.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Freeze inputs once; a new conversation per question shares the same initialized engine. */
fun replayDataset(context: Context): JSONObject = context.assets.open(APK_FIXTURE_ASSET).bufferedReader().use { JSONObject(it.readText()) }

/** Structural citation checks do not assess whether a passage supports a claim. */
fun citationCheck(answer: String, sources: JSONArray): JSONObject {
    val ranks = (0 until sources.length()).map { sources.getJSONObject(it).getInt("rank") }.toSet()
    val cited = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]").findAll(answer)
        .flatMap { it.groupValues[1].split(',').asSequence().map { rank -> rank.trim().toInt() } }.distinct().toList()
    return JSONObject().put("citedRanks", JSONArray(cited)).put("unknownRanks", JSONArray(cited.filter { it !in ranks }))
        .put("hasCitations", cited.isNotEmpty()).put("correctness", "unscored")
}

/** Returns generated text and measured timings; caller checkpoints outside the timed interval. */
@OptIn(ExperimentalApi::class)
private fun replayAnswer(engine: Engine, systemPrompt: String, prompt: String, progress: (JSONObject) -> Unit): JSONObject {
    val setupStart = SystemClock.elapsedRealtimeNanos()
    engine.createConversation(ConversationConfig(
        systemInstruction = Contents.of(systemPrompt),
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
        maxOutputToken = OUTPUT_TOKENS,
        thinkingConfig = ThinkingConfig(enableThinking = false),
        enableSpeculativeDecoding = false,
    )).use { conversation ->
        val setupMs = (SystemClock.elapsedRealtimeNanos() - setupStart) / 1_000_000.0
        val text = StringBuilder()
        val channels = linkedMapOf<String, StringBuilder>()
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        var firstResponseNs = 0L
        var firstTextNs = 0L
        var callbacks = 0
        val live = AtomicReference(JSONObject())
        val started = SystemClock.elapsedRealtimeNanos()
        conversation.sendMessageAsync(prompt, object : MessageCallback {
            override fun onMessage(message: Message) {
                val now = SystemClock.elapsedRealtimeNanos()
                if (firstResponseNs == 0L) firstResponseNs = now
                val chunk = message.toString()
                if (chunk.isNotEmpty() && firstTextNs == 0L) firstTextNs = now
                text.append(chunk)
                message.channels.forEach { (name, value) -> channels.getOrPut(name) { StringBuilder() }.append(value) }
                callbacks++
                live.set(JSONObject().put("partialAnswer", text.toString()).put("rawChannels", JSONObject(channels.mapValues { it.value.toString() })).put("streamCallbackCount", callbacks)
                    .put("lastTextAtMs", System.currentTimeMillis())
                    .put("firstVisibleTextMs", if (firstTextNs == 0L) JSONObject.NULL else (firstTextNs - started) / 1_000_000.0))
            }
            override fun onDone() { finished.countDown() }
            override fun onError(throwable: Throwable) { failure.set(throwable); finished.countDown() }
        })
        var lastCheckpoint = 0L
        while (!finished.await(250, TimeUnit.MILLISECONDS)) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
            if (elapsedMs >= 180_000) {
                conversation.cancelProcess()
                error("Generation exceeded 180 seconds")
            }
            if (elapsedMs - lastCheckpoint >= 1000) {
                progress(JSONObject(live.get().toString()).put("elapsedGenerationMs", elapsedMs))
                lastCheckpoint = elapsedMs
            }
        }
        failure.get()?.let { throw it }
        val generationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        progress(JSONObject(live.get().toString()))
        check(text.isNotBlank()) { "Engine completed without visible answer text; raw channels retained in the checkpoint." }
        val result = JSONObject().put("answer", text.toString()).put("conversationInitMs", setupMs)
            .put("rawChannels", JSONObject(channels.mapValues { it.value.toString() }))
            .put("unexpectedThinking", channels.any { it.value.isNotEmpty() } || text.contains("<think>"))
            .put("generationMs", generationMs).put("questionMs", setupMs + generationMs)
            .put("firstResponseMs", if (firstResponseNs == 0L) JSONObject.NULL else (firstResponseNs - started) / 1_000_000.0)
            .put("firstVisibleTextMs", if (firstTextNs == 0L) JSONObject.NULL else (firstTextNs - started) / 1_000_000.0)
            .put("questionToFirstTextMs", if (firstTextNs == 0L) JSONObject.NULL else setupMs + (firstTextNs - started) / 1_000_000.0)
            .put("streamCallbackCount", callbacks)
        runCatching {
            val metrics = conversation.getBenchmarkInfo()
            JSONObject().put("prefillTokens", metrics.lastPrefillTokenCount).put("decodeTokens", metrics.lastDecodeTokenCount)
                .put("prefillTokensPerSecond", metrics.lastPrefillTokensPerSecond).put("decodeTokensPerSecond", metrics.lastDecodeTokensPerSecond)
                .put("timeToFirstTokenSeconds", metrics.timeToFirstTokenInSecond)
        }.onSuccess {
            result.put("runtimeMetrics", it).put("outputTokenLimitReached", it.getInt("decodeTokens") >= OUTPUT_TOKENS)
        }.onFailure { result.put("runtimeMetricsError", it.toString()) }
        return result
    }
}

/** Resume retains successful answers and records a fresh engine session for remaining questions. */
fun runReplay(context: Context, engine: Engine?, result: JSONObject, checkpoint: (String) -> Unit,
              questionLimit: Int = QUESTION_COUNT, smokeMode: String? = null, llama: LlamaEngine? = null,
              freshEngineFactory: (() -> Engine)? = null) {
    // 1. Start one session per initialized engine; warm labels never cross a worker restart.
    val dataset = replayDataset(context)
    val questions = dataset.getJSONArray("questions")
    val answers = result.optJSONArray("answers") ?: JSONArray().also { result.put("answers", it) }
    val sessions = result.optJSONArray("engineSessions") ?: JSONArray().also { result.put("engineSessions", it) }
    var sessionId = "${Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}"
    var session = JSONObject().put("id", sessionId).put("startedAtMs", System.currentTimeMillis())
        .put("engineLoadMs", result.opt("engineLoadMs") ?: JSONObject.NULL)
        .put("cacheFilesBeforeLoad", result.optInt("cacheFilesBeforeLoad"))
        .put("workerPid", Process.myPid()).put("synthetic", smokeMode != null)
    if (freshEngineFactory == null) sessions.put(session)
    val selected = result.optJSONArray("selectedQuestionIds")?.let { ids ->
        (0 until ids.length()).map { ids.getString(it) }.toSet()
    }
    val indices = (0 until questions.length()).filter { selected == null || questions.getJSONObject(it).getString("id") in selected }.take(questionLimit)
    check(selected == null || selected.all { id -> (0 until questions.length()).any { questions.getJSONObject(it).getString("id") == id } }) { "Unknown selected question ID" }
    result.put("totalQuestions", indices.size)
    var sessionIndex = 0
    val liveConfig = BENCHMARK_CONFIGS.firstOrNull { it.id == result.optString("id") && it.liveTools }
    val toolConversation = if (llama != null && liveConfig != null) ToolConversation(context, liveConfig, llama) else null

    // 2. Replay verbatim prompts, persisting each completed answer before advancing.
    for ((ordinal, index) in indices.withIndex()) {
        val question = questions.getJSONObject(index)
        val priorIndex = (0 until answers.length()).firstOrNull { answers.getJSONObject(it).optString("questionId") == question.getString("id") }
        if (priorIndex != null && answers.getJSONObject(priorIndex).optString("status") == "completed") continue
        if (freshEngineFactory != null) {
            sessionId = "${Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}"
            sessionIndex = 0
            session = JSONObject().put("id", sessionId).put("startedAtMs", System.currentTimeMillis())
                .put("workerPid", Process.myPid()).put("freshEnginePerQuestion", true)
            sessions.put(session)
        }
        val entry = JSONObject().put("questionId", question.getString("id")).put("questionIndex", index)
            .put("question", question.getString("question")).put("promptSha256", question.getString("promptSha256"))
            .put("engineSessionId", sessionId).put("sessionQuestionIndex", sessionIndex)
            .put("engineReused", sessionIndex > 0).put("status", "running")
            .put("device", deviceInfo(context)).put("vocabularyLevel", question.optString("vocabularyLevel"))
            .put("questionType", question.optString("questionType")).put("baseQaId", question.optString("baseQaId"))
            .put("engineLoadMs", if (sessionIndex == 0) result.optDouble("engineLoadMs", 0.0) else 0.0)
            .put("startedAtMs", System.currentTimeMillis()).put("memoryBefore", memorySnapshot(context))
        if (priorIndex == null) answers.put(entry) else answers.put(priorIndex, entry)
        result.put("currentQuestion", ordinal + 1).put("sourceQuestionNumber", index + 1)
        checkpoint("generating")
        try {
            val generated = if (BuildConfig.DEBUG && smokeMode != null) {
                if (index == 1 && smokeMode == "replay_crash") { Process.killProcess(Process.myPid()); return }
                if (index == 1 && smokeMode == "replay_error") error("Injected replay failure after first answer")
                if (smokeMode == "replay_ui") Thread.sleep(20)
                JSONObject().put("answer", "Synthetic replay answer [1]. No inference.").put("smokeTest", true)
            } else {
                val progress: (JSONObject) -> Unit = { live ->
                    live.keys().forEach { key -> entry.put(key, live.get(key)) }
                    checkpoint("generating")
                }
                if (toolConversation != null) toolConversation.answer(dataset.getString("systemPrompt"), question.getString("question"), progress)
                else if (llama != null) llama.answer(dataset.getString("systemPrompt"), question.getString("prompt"), progress)
                else if (freshEngineFactory != null) {
                    checkpoint("engine_initializing")
                    val loadStart = SystemClock.elapsedRealtimeNanos()
                    val fresh = freshEngineFactory()
                    var initialized = false
                    try {
                        fresh.initialize()
                        initialized = true
                        val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0
                        entry.put("engineLoadMs", loadMs)
                        session.put("engineLoadMs", loadMs)
                        result.put("engineInitialized", true).put("engineLoadMs", loadMs)
                            .put("backendEvidence", "Direct LiteRT-LM engine initialized for this question. No app fallback; native operations may use CPU.")
                        checkpoint("generating")
                        val answer = replayAnswer(fresh, dataset.getString("systemPrompt"), question.getString("prompt"), progress)
                        entry.put("memoryBeforeEngineClose", memorySnapshot(context))
                        answer.keys().forEach { key -> entry.put(key, answer.get(key)) }
                        checkpoint("question_generated")
                        answer
                    } finally {
                        if (initialized) {
                            checkpoint("engine_closing_question")
                            val closeStart = SystemClock.elapsedRealtimeNanos()
                            fresh.close()
                            entry.put("engineCloseMs", (SystemClock.elapsedRealtimeNanos() - closeStart) / 1_000_000.0)
                        }
                    }
                } else replayAnswer(checkNotNull(engine), dataset.getString("systemPrompt"), question.getString("prompt"), progress)
            }
            generated.keys().forEach { key -> entry.put(key, generated.get(key)) }
            entry.put("status", "completed").put("memoryAfter", memorySnapshot(context))
                .put("endToFirstTextMs", entry.optDouble("questionToFirstTextMs").takeIf { it.isFinite() }?.let { it + entry.optDouble("engineLoadMs", 0.0) } ?: JSONObject.NULL)
                .put("endToEndMs", entry.optDouble("engineLoadMs", 0.0) + entry.optDouble("questionMs", 0.0) + entry.optDouble("engineCloseMs", 0.0))
                .put("observedQuestionWallMs", System.currentTimeMillis() - entry.getLong("startedAtMs"))
                .put("citations", citationCheck(entry.getString("answer"), entry.optJSONArray("sources") ?: question.getJSONArray("sources")))
                .put("finishedAtMs", System.currentTimeMillis())
            sessionIndex++
            session.put("completedQuestions", sessionIndex).put("device", deviceInfo(context)).put("finishedAtMs", System.currentTimeMillis())
            checkpoint("question_completed")
        } catch (error: Throwable) {
            entry.put("status", "error").put("error", error.toString())
            checkpoint("question_failed")
            toolConversation?.close()
            throw error // Reusing a failed native conversation is unsafe; other configurations still run.
        }
    }
    session.put("finishedAtMs", System.currentTimeMillis())
    toolConversation?.close()
    checkpoint("engine_closing")
}
