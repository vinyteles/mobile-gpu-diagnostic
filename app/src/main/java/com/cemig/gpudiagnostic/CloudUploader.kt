package com.cemig.gpudiagnostic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Durable latest checkpoint + separate configuration snapshots; networking never blocks inference. */
class CloudUploader(private val context: Context, private val client: HuggingFaceClient = HuggingFaceClient(),
                    private val automatic: Boolean = true) {
    val settings = CloudSettings(context)
    private val directory = File(context.filesDir, "cloud-outbox").apply { mkdirs() }
    private val lock = Any()
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "hf-report-uploader").apply { isDaemon = true } }
    @Volatile private var retryAtMs = 0L
    @Volatile private var failures = 0
    @Volatile var status = if (settings.enabled) "Hugging Face upload enabled; checking pending reports." else "Hugging Face upload is not configured."
        private set

    fun start() {
        if (automatic && started.compareAndSet(false, true)) executor.scheduleWithFixedDelay({ drain() }, 1, 15, TimeUnit.SECONDS)
    }

    fun configure(repository: String, token: String) {
        status = "Checking repository and write access…"
        client.connect(repository, token, settings.installationId)
        settings.save(repository, token)
        retryAtMs = 0; failures = 0
        status = "Connected to dataset $repository. Automatic uploads enabled."
        start()
    }

    fun disable() { settings.disable(); status = "Hugging Face uploads disabled. Local reports remain available." }

    fun enqueue(run: File, archive: File, configuration: String? = null) {
        if (!settings.enabled) return
        val cloudRun = "${run.name}-${settings.installationId.take(8)}"
        val name = "$cloudRun--${configuration ?: "latest"}"
        synchronized(lock) {
            val zip = File(directory, "$name.zip")
            val temporary = File(directory, "$name.part")
            ReportExporter(context).copyArchive(archive, temporary)
            check(temporary.renameTo(zip)) { "Could not persist upload checkpoint." }
            val progress = progress(zip, cloudRun)
            val job = JSONObject().put("generation", UUID.randomUUID().toString()).put("repository", settings.repository)
                .put("configuration", configuration ?: JSONObject.NULL).put("archive", zip.name).put("progress", progress)
            writeJson(File(directory, "$name.json"), job)
        }
        start()
    }

    /** One attempt is public within the module so retry/persistence can be tested without real delays. */
    internal fun drain() {
        if (!settings.enabled || System.currentTimeMillis() < retryAtMs) return
        var working: File? = null
        try {
            val token = settings.token() ?: error("Credential unavailable; reconnect Hugging Face.")
            val selected = synchronized(lock) {
                val jobFile = directory.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.lastModified() }
                    .firstOrNull { readJson(it)?.optString("repository") == settings.repository } ?: return
                val job = readJson(jobFile) ?: return
                val zip = File(directory, job.getString("archive"))
                check(zip.parentFile == directory && zip.isFile)
                val copy = File(context.cacheDir, "hf-upload-working.zip")
                zip.copyTo(copy, overwrite = true)
                working = copy
                Triple(jobFile, job, copy)
            }
            val (jobFile, job, zip) = selected
            status = "Uploading report to ${job.getString("repository")}…"
            val configuration = if (job.isNull("configuration")) null else job.getString("configuration")
            val progress = job.getJSONObject("progress")
            val commit = client.publish(job.getString("repository"), token, zip, progress, configuration)
            synchronized(lock) {
                if (readJson(jobFile)?.optString("generation") == job.getString("generation")) {
                    File(directory, job.getString("archive")).delete(); jobFile.delete()
                }
            }
            val uploadedAt = System.currentTimeMillis()
            writeJson(File(context.filesDir, "cloud-last-upload.json"), JSONObject().put("uploadedAtMs", uploadedAt)
                .put("repository", job.getString("repository")).put("cloudRunId", progress.getString("cloudRunId")).put("commit", commit))
            failures = 0; retryAtMs = 0
            status = "Hugging Face synced · ${progress.optInt("completedAnswers")} answers · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(uploadedAt))}"
        } catch (error: Throwable) {
            failures++
            retryAtMs = System.currentTimeMillis() + (30_000L * (1L shl failures.coerceAtMost(4))).coerceAtMost(300_000L)
            val detail = error.message?.takeIf { it.startsWith("Hugging Face HTTP ") }
                ?: error.javaClass.simpleName
            status = "Hugging Face upload pending ($detail). Retrying automatically; local results are safe."
        } finally { working?.delete() }
    }

    private fun progress(archive: File, cloudRun: String): JSONObject {
        val report = ZipFile(archive).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("report.json")).bufferedReader().use { it.readText() })
        }
        val records = report.getJSONArray("results")
        val metadata = report.getJSONObject("metadata")
        var completed = 0
        val rows = JSONArray()
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val answers = record.optJSONArray("answers") ?: JSONArray()
            val done = (0 until answers.length()).count { answers.getJSONObject(it).optString("status") == "completed" }
            completed += done
            rows.put(JSONObject().put("id", record.getString("id")).put("status", record.optString("status"))
                .put("phase", record.optString("phase")).put("completed", done).put("total", record.optInt("totalQuestions", QUESTION_COUNT))
                .put("currentQuestion", record.optInt("currentQuestion"))
                .put("updatedAtMs", record.optLong("updatedAtMs")))
        }
        return JSONObject().put("schemaVersion", 1).put("cloudRunId", cloudRun).put("localRunId", metadata.optString("runId"))
            .put("updatedAtMs", System.currentTimeMillis()).put("appVersion", BuildConfig.VERSION_NAME)
            .put("batch", BENCHMARK_BATCH_NAME).put("smokeTest", metadata.optBoolean("smokeTest"))
            .put("device", metadata.optJSONObject("device")).put("queue", report.optJSONObject("queue"))
            .put("completedAnswers", completed).put("configurations", rows)
            .put("preparation", report.optJSONObject("preparation"))
            .put("archivePath", "runs/$cloudRun/latest.zip").put("archiveSha256", sha256(archive))
    }
}
