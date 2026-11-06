package com.cemig.gpudiagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One persisted queue: established GPUs, experimental GPUs, then CPUs. */
class Coordinator(private val context: Context, private val configurations: List<BenchmarkConfig> = APK_CONFIGS,
                  private val reportEachQuestion: Boolean = true) {
    private val stopped = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val stateFile = File(context.filesDir, if (APK_FIXTURE_ASSET == "rag-replay.json")
        "queue-benchmark-120-v2.json" else "queue-benchmark-120-tools-v1.json")
    private val preferences = context.getSharedPreferences("benchmark-120-v2", Context.MODE_PRIVATE)
    val selectedIds: Set<String> get() = configurations.map { it.id }.toSet()
    val downloads = ConcurrentHashMap<String, DownloadProgress>()
    val downloadErrors = ConcurrentHashMap<String, String>()
    @Volatile var status = "Start to run this APK's configuration on 120 questions."
        private set
    @Volatile var reportStatus = ""
        private set
    @Volatile var stage = readJson(stateFile)?.optString("stage", "downloads") ?: "downloads"
        private set
    @Volatile var stageStartedAtMs = System.currentTimeMillis()
        private set
    @Volatile var activeBackend = ""
        private set
    @Volatile var currentRun: File? = readJson(stateFile)?.optString("runId")?.takeIf { it.isNotBlank() }
        ?.let { File(context.filesDir, "runs/$it") }
        private set
    val isRunning get() = active.get()
    private val cloud get() = (context.applicationContext as DiagnosticApp).cloudUploader
    init {
        if (preferences.getInt("batch", 0) != BuildConfig.BENCHMARK_PROFILE) {
            preferences.edit().putInt("batch", BuildConfig.BENCHMARK_PROFILE).putStringSet("configurations", BATCH_CONFIG_IDS).commit()
            stage = "downloads"
        }
    }
    fun syncReport() { currentRun?.let { saveReport(it) } }


    fun autoStart(smoke: Boolean = false) {
        if (smoke && currentRun == null) start(smoke = true)
        else if (currentRun != null) status = "Saved run found. Resume keeps completed answers; import a ZIP to continue from another RTL session."
    }

    fun showPreparation() { if (!isRunning) stage = "downloads" }
    fun stop() { stopped.set(true); status = "Stopping. Completed downloads and answers will be kept." }

    /** Adopt a fully validated report without changing or deleting the existing run. */
    fun importReport(input: java.io.InputStream) {
        check(active.compareAndSet(false, true)) { "Stop the benchmark before importing a report." }
        try {
            val imported = ReportImporter(context).restore(input)
            currentRun = imported
            stage = "downloads"
            saveState("stopped")
            status = "Report imported. Resume this configuration; completed answers are retained."
        } finally { active.set(false) }
    }

    fun start(newRun: Boolean = false, smoke: Boolean = false) {
        val configs = BENCHMARK_CONFIGS.filter { it.id in selectedIds }
        if (configs.isEmpty()) { status = "Select at least one configuration."; return }
        if (!active.compareAndSet(false, true)) return
        stopped.set(false)
        reportStatus = ""
        Thread({
            try {
                // 1. Freeze protocol and inputs; old 30-question reports remain in their original runs.
                if (newRun || currentRun == null) currentRun = File(context.filesDir, "runs/bench120-${System.currentTimeMillis()}")
                val run = currentRun!!.apply { mkdirs() }
                val fixtureFile = File(run, "rag-replay.json")
                check(configs.all { it.fixtureAsset == APK_FIXTURE_ASSET }) { "A run must use a single retrieval fixture." }
                val bundled = context.assets.open(APK_FIXTURE_ASSET).use { it.readBytes() }
                if (!fixtureFile.exists()) fixtureFile.writeBytes(bundled)
                val fixtureHash = java.security.MessageDigest.getInstance("SHA-256").digest(bundled).joinToString("") { "%02x".format(it.toInt() and 255) }
                if (configurations.first().liveTools) {
                    val data = replayDataset(context)
                    for ((asset, field) in listOf("retrieval-artifacts.json" to "retrievalArtifactsSha256", "mobilerag-sentence-spans.json" to "sentenceSpansSha256", "f6-stopwords.json" to "f6StopwordsSha256")) {
                        val bytes = context.assets.open(asset).use { it.readBytes() }
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                        check(hash == data.getString(field)) { "Retrieval asset changed: $asset" }
                    }
                }
                check(sha256(fixtureFile) == fixtureHash) { "Fixture changed; start a new run." }
                val previous = readJson(File(run, "metadata.json"))
                val synthetic = previous?.optBoolean("smokeTest") ?: (BuildConfig.DEBUG && smoke)
                check(BuildConfig.DEBUG || !synthetic) { "Synthetic reports cannot run in the release APK." }
                val metadata = previous ?: JSONObject().put("schemaVersion", 6).put("protocolId", PROTOCOL_ID)
                    .put("runId", run.name).put("startedAtMs", System.currentTimeMillis()).put("device", deviceInfo(context))
                    .put("smokeTest", synthetic).put("fixtureSha256", fixtureHash).put("runtime", "LiteRT-LM + llama.cpp")
                    .put("litertVersion", BuildConfig.LITERT_VERSION).put("llamaRevision", BuildConfig.LLAMA_REVISION)
                    .put("scope", replayDataset(context).getString("scope"))
                    .put("retrievalMode", if (configurations.first().liveTools) "on-device model-controlled tools" else "frozen replay")
                    .put("retrievalArtifacts", JSONArray(if (configurations.first().liveTools) (retrievalArtifacts(context, "mobileragplus") + retrievalArtifacts(context, "f6")).map {
                        JSONObject().put("id", it.id).put("url", it.url).put("sha256", it.sha256).put("bytes", it.bytes)
                    } else emptyList<JSONObject>()))
                    .put("settings", replayDataset(context).getJSONObject("settings"))
                    .put("timingPolicy", "One engine per configuration/session except LFM LiteRT: new engine per question to reset recurrent state. Per-question load/close overhead is recorded separately and included in endToEndMs. No cross-question conversations; model-specific templates. After each completed question a cumulative ZIP is queued before the next question starts; network uploads run asynchronously. Additional snapshots are queued every 60 seconds while waiting, on stop and at configuration end. Server publishes to HF only after all assigned questions are complete. Wall timing includes checkpoint/export overhead. Fixed order, not thermally counterbalanced.")
                    .put("backendPolicy", "Exact requested backend; no app CPU fallback. Native operators can still use CPU. Q8 GPU requires model bytes plus 768 MiB available RAM before loading; otherwise skipped with evidence.")
                    .put("models", JSONArray(BENCHMARK_MODELS.map { JSONObject().put("id", it.id).put("url", it.url).put("bytes", it.bytes).put("sha256", it.sha256) }))
                check(metadata.getString("protocolId") == PROTOCOL_ID)
                val attempts = metadata.optJSONArray("executionAttempts") ?: JSONArray().also { metadata.put("executionAttempts", it) }
                attempts.put(JSONObject().put("startedAtMs", System.currentTimeMillis()).put("appVersion", BuildConfig.VERSION_NAME).put("batch", BENCHMARK_BATCH_NAME)
                    .put("uploadPolicy", "Cumulative checkpoint after every completed question; HF publication after all assigned questions complete.")
                    .put("device", deviceInfo(context)).put("configurationOrder", JSONArray(configs.map { it.id })))
                writeJson(File(run, "metadata.json"), metadata)

                // 2. Show and verify every required artifact before inference; one failed download does not block other models.
                stage = "downloads"; stageStartedAtMs = System.currentTimeMillis(); saveState("running")
                downloads.clear(); downloadErrors.clear()
                saveReport(run)
                var lastDownloadReport = System.currentTimeMillis()
                val pending = configs.filter { readJson(File(run, "${it.id}.json"))?.optString("status") != "completed" }
                val files = mutableMapOf<String, File>()
                val preparation = readJson(File(run, "preparation.json")) ?: JSONObject()
                for (model in pending.flatMap { requiredArtifacts(context, it) }.distinctBy { it.id }) {
                    if (stopped.get()) throw CancellationException()
                    try {
                        if (model == GEMMA_Q8 && !synthetic) memorySkipReason()?.let { error(it) }
                        val file = if (synthetic) {
                            downloads[model.id] = DownloadProgress("ready", model.bytes, model.bytes)
                            File(context.filesDir, "unused-smoke-model")
                        } else ModelDownloader(File(context.filesDir, "models"), { stopped.get() }, { status = it }) { update ->
                            downloads[model.id] = update
                            preparation.put(model.id, JSONObject().put("phase", update.phase).put("bytes", update.bytes)
                                .put("totalBytes", update.totalBytes).put("updatedAtMs", System.currentTimeMillis()))
                            writeJson(File(run, "preparation.json"), preparation)
                            if (System.currentTimeMillis() - lastDownloadReport >= 60_000) {
                                saveReport(run); lastDownloadReport = System.currentTimeMillis()
                            }
                        }.download(model)
                        files[model.id] = file
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { downloadErrors[model.id] = error.message ?: error.toString() }
                }

                // 3. Run ordered configurations in disposable processes; checkpoint/export every configuration.
                stage = "benchmark"; stageStartedAtMs = System.currentTimeMillis(); saveState("running")
                for (config in configs) {
                    if (stopped.get()) throw CancellationException()
                    val caseFile = File(run, "${config.id}.json")
                    val existing = readJson(caseFile)
                    if (existing?.optString("status") == "completed") continue
                    activeBackend = config.id
                    val record = existing ?: JSONObject().put("id", config.id).put("model", config.model.id)
                        .put("retrieval", config.retrieval).put("matrixCase", config.matrixCase)
                        .put("modelSha256", config.model.sha256).put("requestedBackend", config.backend)
                        .put("workload", if (config.liveTools) "rag-tools" else "rag-replay").put("runtime", config.runtime).put("startedAtMs", System.currentTimeMillis())
                    record.put("status", "pending").put("phase", "connecting").put("totalQuestions", QUESTION_COUNT)
                        .put("freshEnginePerQuestion", config.freshEngine)
                    listOf("controllerError", "controllerOutcome", "failedPhase", "error", "stackTrace").forEach { record.remove(it) }
                    val memoryReason = if (config.memoryConditional && !synthetic) memorySkipReason() else null
                    val engineCache = File(context.filesDir, "engine-cache/${config.model.id}-${config.backend}-${BuildConfig.LITERT_VERSION}")
                    val cachedBytes = if (engineCache.exists()) engineCache.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
                    val cacheAllowance = if (config.backend == "cpu") config.model.bytes else 1024L * 1024 * 1024
                    val requiredStorage = (cacheAllowance - cachedBytes).coerceAtLeast(512L * 1024 * 1024)
                    val availableStorage = context.filesDir.usableSpace
                    record.put("storagePreflight", JSONObject().put("availableBytes", availableStorage)
                        .put("cacheBytes", cachedBytes).put("requiredFreeBytes", requiredStorage))
                    val storageReason = if (!synthetic && config.runtime == "LiteRT-LM" && availableStorage < requiredStorage)
                        "Not enough storage for runtime caches: ${availableStorage / 1_000_000} MB free; reserve ${requiredStorage / 1_000_000} MB. Free space and Resume." else null
                    val problem = memoryReason ?: requiredArtifacts(context, config).firstNotNullOfOrNull { downloadErrors[it.id] } ?: storageReason
                    if (problem != null || files[config.model.id] == null) {
                        record.put("status", if (memoryReason != null) "skipped" else "error").put("phase", "preparation_failed")
                            .put("error", problem ?: "Model file unavailable").put("memory", memorySnapshot(context))
                        writeJson(caseFile, record); saveReport(run, config.id); continue
                    }
                    status = "Running ${config.label}."
                    writeJson(caseFile, record)
                    val arguments = Bundle().apply {
                        putString("caseFile", caseFile.path); putString("modelPath", files.getValue(config.model.id).path)
                        putString("runtime", config.runtime); putString("modelFamily", if (config.model == GEMMA_Q8) "gemma" else "lfm")
                        putString("backend", config.backend); putBoolean("replay", true)
                        putBoolean("freshEnginePerQuestion", config.freshEngine)
                        putString("engineCache", engineCache.path)
                        if (synthetic) { putString("smoke", "replay_ui"); putInt("questionLimit", QUESTION_COUNT) }
                    }
                    WorkerBridge(context).run(arguments, { stopped.get() }, timeoutMs = 6 * 60 * 60 * 1000L,
                        periodicReport = { saveReport(run) }, questionCompleted = if (reportEachQuestion) ({ saveReport(run) }) else null)
                    saveReport(run, config.id)
                }
                if (stopped.get()) throw CancellationException()
                val complete = configs.all { readJson(File(run, "${it.id}.json"))?.optString("status") == "completed" }
                saveState(if (complete) "completed" else "error")
                status = if (complete) "Selected configurations completed. Report saved." else "Run finished with skipped or failed configurations. Export the report; Resume retries unfinished answers."
            } catch (error: CancellationException) {
                saveState("stopped"); status = "Stopped. Resume keeps every completed answer."
            } catch (error: Throwable) {
                saveState("error", error.stackTraceToString()); status = "Stopped: ${error.message}. Resume to retry."
            } finally {
                currentRun?.let { saveReport(it) }
                activeBackend = ""; active.set(false)
            }
        }, "diagnostic-controller").start()
    }

    private fun memorySkipReason(): String? {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val required = GEMMA_Q8.bytes + 768L * 1024 * 1024
        return if (memory.availMem < required || memory.lowMemory)
            "Gemma Q8 skipped: ${memory.availMem / 1_000_000} MB available RAM; conservative minimum ${required / 1_000_000} MB. No smaller model substituted." else null
    }

    private fun saveReport(run: File, configuration: String? = null) {
        runCatching {
            val exporter = ReportExporter(context)
            val archive = exporter.archive(run)
            // Cloud queuing still happens if publishing to Downloads fails.
            try { exporter.saveToDownloads(archive) } finally { cloud.enqueue(run, archive, configuration) }
        }.onSuccess { reportStatus = "Latest report saved to Downloads. Save it before RTL ends; import it to continue on another device." }
            .onFailure { reportStatus = "Automatic report export failed: ${it.message}. Use Share report." }
    }

    private fun saveState(value: String, error: String? = null) {
        writeJson(stateFile, JSONObject().put("runId", currentRun?.name).put("status", value).put("stage", stage)
            .put("updatedAtMs", System.currentTimeMillis()).put("error", error ?: JSONObject.NULL))
        currentRun?.let { writeJson(File(it, "queue-state.json"), readJson(stateFile)!!) }
    }
}
