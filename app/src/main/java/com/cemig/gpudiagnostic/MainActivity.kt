package com.cemig.gpudiagnostic

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** Preparation and benchmark screens with persistent progress cards and expandable metrics. */
class MainActivity : Activity() {
    private val cloud get() = (application as DiagnosticApp).cloudUploader
    private val coordinator get() = (application as DiagnosticApp).coordinator
    private val handler = Handler(Looper.getMainLooper())
    private var shownStage = ""
    private val expanded = mutableSetOf<String>()
    private var smoke = false
    private var preview = false
    private lateinit var statusText: TextView
    private val modelViews = mutableMapOf<String, Triple<ProgressRing, TextView, TextView>>()
    private lateinit var action: Button
    private var repeatButton: Button? = null
    private var quickAction: Button? = null
    private data class PipelineViews(val ring: ProgressRing, val count: TextView, val detail: TextView, val metrics: TextView)
    private val pipelines = mutableMapOf<String, PipelineViews>()
    private val green = Color.rgb(0, 132, 61)
    private val ink = Color.rgb(22, 57, 41)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val refresh = object : Runnable {
        override fun run() {
            if (shownStage != coordinator.stage) buildScreen(coordinator.stage)
            renderProgress()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cloud.start()
        expanded.addAll(savedInstanceState?.getStringArrayList("expanded").orEmpty())
        smoke = BuildConfig.DEBUG && intent.getBooleanExtra("smoke", false)
        preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview", false)
        buildScreen(coordinator.stage)
        if (!preview) coordinator.autoStart(smoke)
        if (!preview && !coordinator.isRunning) Thread({ coordinator.syncReport() }, "restore-upload-checkpoint").start()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putStringArrayList("expanded", ArrayList(expanded)); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun label(parent: LinearLayout, text: String, size: Float = 15f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(ink); setPadding(0, dp(4), 0, dp(6))
        if (bold) setTypeface(null, Typeface.BOLD)
    }.also { parent.addView(it) }

    private fun button(parent: LinearLayout, text: String, primary: Boolean = false, clicked: () -> Unit): Button = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f; minHeight = dp(48)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) green else Color.rgb(228, 238, 232))
        setTextColor(if (primary) Color.WHITE else ink)
        setOnClickListener { clicked() }
    }.also { parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }) }

    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.rgb(222, 231, 225)) }
    }.also { parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }) }

    private fun progressHeading(card: LinearLayout, title: String): Triple<ProgressRing, TextView, TextView> {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        card.addView(row)
        val progress = ProgressRing(this)
        row.addView(progress, LinearLayout.LayoutParams(dp(68), dp(68)).apply { marginEnd = dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        label(texts, title, 17f, true)
        val count = label(texts, "Waiting", 14f)
        val detail = label(card, "", 14f)
        return Triple(progress, count, detail)
    }

    private fun buildScreen(stage: String) {
        shownStage = stage; pipelines.clear(); modelViews.clear(); repeatButton = null; quickAction = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24))
            setBackgroundColor(Color.rgb(245, 248, 246))
        }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom); insets
        }
        label(content, "CEMIG  /  MODEL BENCHMARK", 12f, true).setTextColor(green)
        label(content, BENCHMARK_BATCH_NAME, 18f, true)
        button(content, "Report upload status") { cloudSettings() }
        if (stage == "downloads") {
            label(content, "Prepare files", 28f, true)
            label(content, "STEP 1 OF 2", 12f, true)
            label(content, "120 questions for this APK's configuration. The required model downloads before the benchmark starts.")
            for (config in APK_CONFIGS) {
                label(content, config.label + if (config.tested) " · previously tested" else " · new", 16f, true)
            }
            quickAction = button(content, "Start / resume", primary = true) {
                if (coordinator.isRunning) coordinator.stop() else coordinator.start(smoke = smoke)
            }
            button(content, "Import previous report ZIP") { chooseReport() }
            label(content, "Required model downloads", 19f, true)
            for (model in requiredArtifacts(this, APK_CONFIGS.single())) modelViews[model.id] = progressHeading(card(content), model.label)
            val prompts = progressHeading(card(content), "Questions and references")
            prompts.first.update(1f, completed = true)
            prompts.second.text = "Included in this APK"
            prompts.third.text = "120 questions: 30 clean, 30 technical, 30 operational, 30 lay. Contexts and evaluation references included."
            label(content, "The benchmark opens automatically after verification. Keep this screen open.", 14f)
        } else {
            label(content, "Model benchmark", 28f, true)
            label(content, "STEP 2 OF 2", 12f, true)
            label(content, "This APK runs one configuration. Each answer is saved; reports upload automatically. Keep the latest ZIP before RTL ends.")
            for (config in APK_CONFIGS) {
                val backend = config.id
                val pipeline = card(content)
                val title = config.label
                val heading = progressHeading(pipeline, title)
                button(pipeline, if (backend in expanded) "Hide metrics ▴" else "Show metrics ▾") {
                    if (!expanded.add(backend)) expanded.remove(backend)
                    buildScreen("benchmark")
                }.contentDescription = "Expand or collapse $backend pipeline metrics"
                val metrics = label(pipeline, "Waiting for metrics…", 14f).apply {
                    visibility = if (backend in expanded) View.VISIBLE else View.GONE
                    setTextIsSelectable(true)
                }
                pipelines[backend] = PipelineViews(heading.first, heading.second, heading.third, metrics)
            }
        }
        statusText = label(content, coordinator.status, 14f)
        action = button(content, "Resume", primary = true) {
            if (coordinator.isRunning) coordinator.stop() else coordinator.start(smoke = smoke)
        }
        if (stage == "benchmark") {
            button(content, "Save report to Downloads") { export(false) }
            button(content, "Share report") { export(true) }
            button(content, "Copy metrics") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Model benchmark", coordinator.currentRun?.let { ReportExporter(this).summary(it) } ?: "No results"))
                Toast.makeText(this, "Metrics copied", Toast.LENGTH_SHORT).show()
            }
            button(content, "Model / downloads") { coordinator.showPreparation(); buildScreen(coordinator.stage) }
            repeatButton = button(content, "Start a new run (keep old reports)") { coordinator.start(newRun = true, smoke = smoke) }
        }
        if (stage != "downloads") button(content, "Import previous report ZIP") { chooseReport() }
        label(content, "Each completed answer is saved locally and sent to the server. The complete run is published to HF after all 120 answers finish.", 12f)
        setContentView(ScrollView(this).apply { addView(content) })
        renderProgress()
    }

    private fun TextView.show(value: CharSequence) { if (text.toString() != value.toString()) text = value }

    private fun seconds(ms: Long) = "${ms.coerceAtLeast(0) / 1000}s"

    private fun renderProgress() {
        statusText.show(listOf(coordinator.status, coordinator.reportStatus, cloud.status).filter { it.isNotBlank() }.joinToString("\n\n"))
        if (shownStage == "downloads") action.show(if (coordinator.isRunning) "Stop" else "Download / resume")
        repeatButton?.isEnabled = !coordinator.isRunning
        quickAction?.show(if (coordinator.isRunning) "Stop" else "Start / resume")
        if (shownStage == "downloads") {
            for (model in requiredArtifacts(this, APK_CONFIGS.single())) {
                val views = modelViews.getValue(model.id)
                val needed = true
                val progress = coordinator.downloads[model.id] ?: DownloadProgress("waiting", 0, model.bytes)
                val problem = coordinator.downloadErrors[model.id]
                val fraction = progress.bytes.toFloat() / progress.totalBytes.coerceAtLeast(1)
                views.first.update(fraction, coordinator.isRunning && needed && problem == null && progress.phase in listOf("downloading", "verifying"), progress.phase == "ready", problem != null)
                views.second.show(when {
                    !needed -> "Not selected"
                    problem != null -> "Unavailable · retry to continue"
                    progress.phase == "ready" -> "Verified and ready"
                    progress.phase == "verifying" -> "Verifying checksum"
                    progress.phase == "downloading" -> "Downloading · ${(fraction * 100).toInt()}%"
                    else -> "Waiting"
                })
                views.third.show(problem ?: "${progress.bytes / 1_000_000} / ${model.bytes / 1_000_000} MB · downloaded once, shared across configurations")
            }
            return
        }
        val run = coordinator.currentRun
        val results = BENCHMARK_CONFIGS.associate { config -> config.id to run?.let { readJson(File(it, "${config.id}.json")) } }
        val done = coordinator.selectedIds.isNotEmpty() && coordinator.selectedIds.all { results[it]?.optString("status") == "completed" }
        action.show(if (done) "Completed" else if (coordinator.isRunning) "Stop" else "Resume unfinished questions")
        action.isEnabled = !done
        for ((backend, views) in pipelines) {
            val result = results[backend]
            val answers = result?.optJSONArray("answers")
            val rows = if (answers == null) emptyList() else (0 until answers.length()).map { answers.getJSONObject(it) }
            val count = rows.count { it.optString("status") == "completed" }
            val total = result?.optInt("totalQuestions", QUESTION_COUNT) ?: QUESTION_COUNT
            val complete = result?.optString("status") == "completed"
            val failed = result?.optString("status") in listOf("error", "timeout", "worker_exited", "skipped")
            val running = coordinator.isRunning && coordinator.activeBackend == backend
            views.ring.update(count.toFloat() / total.coerceAtLeast(1), running && !complete, complete, failed)
            views.count.show("$count / $total questions completed")
            val current = rows.lastOrNull { it.optString("status") == "running" }
            views.detail.show(when {
                complete -> "Complete · answers and metrics are ready."
                failed -> "Failed: ${result?.optString("error").orEmpty().ifBlank { result?.optString("status").orEmpty() }.take(180)}\nExport the report for diagnosis."
                !running -> if (backend !in coordinator.selectedIds) "Not selected for this session" else if (result == null) "Waiting for its turn" else "Paused · completed answers are saved"
                current != null -> "Question ${result?.optInt("currentQuestion")} of $total · ${seconds(System.currentTimeMillis() - current.optLong("startedAtMs"))}\n" +
                    if (current.optInt("streamCallbackCount") > 0) "Generating · ${current.optInt("streamCallbackCount")} token updates" else "Reading context / waiting for first text…"
                else -> "Loading model and preparing the requested backend…"
            })
            if (run != null && backend in expanded) views.metrics.show(buildString {
                append(ReportExporter(this@MainActivity).summary(run, backend))
                current?.let {
                    append("\nCurrent question: ${it.optString("question")}\n")
                    if (it.optString("partialAnswer").isNotBlank()) append("\nAnswer so far:\n${it.optString("partialAnswer")}\n")
                }
            })
        }
    }

    private fun cloudSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), 0) }
        label(layout, "Each completed answer uploads to the server automatically. The server saves partial progress and publishes to a public Hugging Face repository after all 120 answers finish. No account or token setup is needed.")
        label(layout, cloud.status)
        val address = cloud.repositoryUrl
        if (address.isNotBlank()) {
            label(layout, address, 13f).setTextIsSelectable(true)
            button(layout, "Copy results link") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Benchmark results", address))
                Toast.makeText(this, "Results link copied", Toast.LENGTH_SHORT).show()
            }
        }
        label(layout, "Uploads include questions, retrieved context, reference and generated answers, device information, and metrics. During a partial run, check the server's saved-answer count. After completion, keep the app open until Hugging Face synced. Keep a ZIP backup before RTL ends.", 13f)
        val dialog = AlertDialog.Builder(this).setTitle("Automatic report uploads").setView(layout)
            .setPositiveButton("Retry uploads") { _, _ -> cloud.retry() }
            .setNegativeButton("Close", null)
            .setNeutralButton("New upload session") { _, _ ->
                if (!coordinator.isRunning) {
                    cloud.newSession()
                    Thread({ coordinator.syncReport(); cloud.retry() }, "new-upload-session").start()
                }
            }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = !coordinator.isRunning }
        dialog.show()
    }

    private fun chooseReport() {
        if (coordinator.isRunning) Toast.makeText(this, "Stop the benchmark before importing.", Toast.LENGTH_SHORT).show()
        else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
        }, 41)
    }

    @Deprecated("Uses the platform document picker for this standalone diagnostic")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread({
            runCatching { contentResolver.openInputStream(uri)!!.use { coordinator.importReport(it) } }
                .onSuccess { runOnUiThread { buildScreen(coordinator.stage) } }
                .onFailure { error -> runOnUiThread { Toast.makeText(this, "Import failed: ${error.message}", Toast.LENGTH_LONG).show() } }
        }, "report-import").start()
    }

    private fun export(share: Boolean) {
        val run = coordinator.currentRun ?: return
        Thread({
            runCatching {
                val exporter = ReportExporter(this)
                val archive = exporter.archive(run)
                if (!share) exporter.saveToDownloads(archive)
                runOnUiThread {
                    if (share) {
                        val uri = FileProvider.getUriForFile(this, "$packageName.reports", archive)
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("Benchmark report", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share benchmark report"))
                    } else Toast.makeText(this, "Report saved to Downloads", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error -> runOnUiThread { Toast.makeText(this, "Export failed: ${error.message}", Toast.LENGTH_LONG).show() } }
        }, "report-export").start()
    }
}
