package com.cemig.gpudiagnostic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Immutable on-device checkpoints survive offline periods; accepted and HF-synced are distinct states. */
class RelayUploader(private val context: Context, private val client: RelayClient = RelayClient(),
                    private val automatic: Boolean = true, private val allowSynthetic: Boolean = false) {
    val settings = RelaySettings(context)
    private val directory = File(context.filesDir, "relay-outbox-v2").apply { mkdirs() }
    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val immediateDrainQueued = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "report-relay").apply { isDaemon = true } }
    private var retryAtMs = 0L
    private var failures = 0
    @Volatile var status = "Each completed answer is sent to the server. HF publication starts after all 120 answers are saved."
        private set
    val repositoryUrl: String get() = runCatching { settings.session(settings.currentScope).optString("repository") }
        .getOrDefault("").takeIf { it.isNotBlank() }?.let { "https://huggingface.co/datasets/$it" }.orEmpty()

    fun start() {
        if (automatic && started.compareAndSet(false, true)) executor.scheduleWithFixedDelay({ drain() }, 1, 15, TimeUnit.SECONDS)
    }
    fun retry() { executor.execute { retryAtMs = 0; client.invalidate(); drain() }; start() }
    fun newSession() { settings.newSession(); status = "New upload session selected. Completed answers remain saved." }

    fun enqueue(run: File, archive: File, configuration: String? = null) {
        synchronized(lock) {
            val generation = UUID.randomUUID().toString()
            val zip = File(directory, "$generation.zip")
            val part = File(directory, "$generation.part")
            ReportExporter(context).copyArchive(archive, part)
            val metadata = ZipFile(part).use { source ->
                JSONObject(source.getInputStream(source.getEntry("report.json")).bufferedReader().use { it.readText() }).getJSONObject("metadata")
            }
            val synthetic = metadata.optBoolean("smokeTest")
            if (synthetic && !allowSynthetic) { part.delete(); return }
            check(part.renameTo(zip)) { "Could not retain upload checkpoint." }
            val session = settings.sessionFor(run.name, BuildConfig.BENCHMARK_PROFILE, synthetic)
            val jobFile = File(directory, "${session.getString("scope")}--${configuration ?: "latest"}.json")
            val old = readJson(jobFile)
            val job = JSONObject().put("generation", generation).put("scope", session.getString("scope"))
                .put("archive", zip.name).put("sha256", sha256(zip)).put("configuration", configuration ?: "")
                .put("createdAtMs", System.currentTimeMillis())
            writeJson(jobFile, job)
            old?.optString("archive")?.let { File(directory, it).delete() }
        }
        start()
        // Upload newly completed answers promptly without building an unbounded executor queue.
        if (automatic && immediateDrainQueued.compareAndSet(false, true)) executor.execute {
            immediateDrainQueued.set(false)
            drain()
        }
    }

    internal fun drain() {
        if (System.currentTimeMillis() < retryAtMs) return
        var copy: File? = null
        try {
            val selected = synchronized(lock) {
                directory.listFiles().orEmpty().filter { it.extension == "json" }.minByOrNull { it.lastModified() }?.let { file ->
                    val job = checkNotNull(readJson(file))
                    val source = File(directory, job.getString("archive"))
                    check(source.parentFile == directory && source.isFile)
                    val working = File.createTempFile("relay-upload-", ".zip", context.cacheDir)
                    source.copyTo(working, overwrite = true); copy = working
                    file to job
                }
            }
            if (selected != null) {
                val (jobFile, job) = selected
                val session = registered(job.getString("scope"))
                status = "Uploading checkpoint to the report server…"
                val receipt = client.upload(session, settings.secret(session), copy!!, job)
                synchronized(lock) {
                    if (readJson(jobFile)?.optString("generation") == job.getString("generation")) {
                        jobFile.delete(); File(directory, job.getString("archive")).delete()
                    }
                }
                writeJson(File(context.filesDir, "relay-last-receipt.json"), receipt)
                status = "Checkpoint received by server. Checking saved-answer count…"
            }
            if (settings.currentScope.isNotBlank()) {
                val session = registered(settings.currentScope)
                val remote = client.status(session, settings.secret(session))
                val sync = remote.getJSONObject("sync")
                val localPending = synchronized(lock) { directory.listFiles().orEmpty().count { it.extension == "json" } }
                val count = remote.optJSONObject("latestPublished")?.optInt("completedAnswers") ?: 0
                val accepted = remote.optJSONObject("latestAccepted")?.optJSONArray("configurations")
                val assignedCount = (0 until (accepted?.length() ?: 0)).map { accepted!!.getJSONObject(it) }
                    .filter { it.optString("id") in BATCH_CONFIG_IDS }.sumOf { it.optInt("completed") }
                val saved = sync.optInt("receivedAnswers", assignedCount)
                val expected = sync.optInt("expectedAnswers", QUESTION_COUNT)
                status = when {
                    localPending > 0 -> "$localPending local checkpoints waiting to upload · server saved $saved / $expected answers."
                    sync.getString("state") == "synced" -> "Hugging Face synced · $count answers · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(sync.optLong("lastPublishedAtMs")))}"
                    sync.getString("state") == "idle" -> "Upload session ready. Waiting for the first checkpoint."
                    sync.getString("state") == "collecting" -> "Server saved $saved / $expected answers · HF publishes when all are complete."
                    else -> "Server saved $saved / $expected answers · waiting for HF publication."
                }
                writeJson(File(context.filesDir, "relay-last-status.json"), remote)
            }
            failures = 0; retryAtMs = 0
        } catch (error: Throwable) {
            client.invalidate()
            failures++
            retryAtMs = System.currentTimeMillis() + (15_000L * (1L shl failures.coerceAtMost(4))).coerceAtMost(240_000L)
            val detail = error.message?.takeIf { it.startsWith("Upload service HTTP") || it.startsWith("Upload server identity") }
                ?: error.javaClass.simpleName
            status = "Upload pending ($detail). Local results are safe; retrying automatically."
        } finally { copy?.delete() }
    }

    private fun registered(scope: String): JSONObject {
        val session = settings.session(scope)
        if (!session.has("sessionId")) {
            val registered = client.register(session, settings.secret(session))
            for (key in listOf("sessionId", "repository", "serviceInstanceId")) session.put(key, registered.getString(key))
            settings.save(session)
        }
        return session
    }
}
