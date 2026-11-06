package com.cemig.gpudiagnostic

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReportExporter(private val context: Context) {
    companion object { private val exportLock = Any() }
    fun results(run: File): List<JSONObject> = run.listFiles().orEmpty()
        .filter { (it.name.startsWith("gemma-") || it.name.startsWith("lfm3-")) && it.extension == "json" }
        .sortedWith(compareBy<File> { file -> BENCHMARK_CONFIGS.indexOfFirst { it.id == file.nameWithoutExtension }.takeIf { it >= 0 } ?: Int.MAX_VALUE }.thenBy { it.name })
        .mapNotNull { readJson(it) }

    fun summary(run: File, configuration: String? = null): String = buildString {
        append("CEMIG Model Diagnostic · ${readJson(File(run, "metadata.json"))?.optString("runtime") ?: "LiteRT-LM"}\n")
        append("${android.os.Build.MODEL} · ${android.os.Build.SOC_MODEL}\n\n")
        val results = if (configuration == null) results(run) else listOfNotNull(readJson(File(run, "$configuration.json")))
        if (results.isEmpty()) append("No inference results yet.\n")
        for (result in results) {
            append("${result.optString("id")}\n${result.optString("status").uppercase()} · ${result.optString("phase")}\n")
            result.optJSONObject("nativeEngineInfo")?.let { append("Device: ${it.optString("device")} · context ${it.optInt("contextTokens")} tokens\n") }
            if (result.optBoolean("smokeTest")) append("SMOKE TEST: no model inference\n")
            result.optJSONArray("answers")?.let { answers ->
                val entries = (0 until answers.length()).map { answers.getJSONObject(it) }
                val completed = entries.filter { it.optString("status") == "completed" }
                val warm = completed.filter { it.optBoolean("engineReused") }
                val first = completed.filter { !it.optBoolean("engineReused") }
                append("Answers: ${completed.size}/${result.optInt("totalQuestions", QUESTION_COUNT)} · current question ${result.optInt("currentQuestion")}\n")
                result.optJSONArray("engineSessions")?.let { sessions ->
                    append("Engine sessions: ${sessions.length()} (LFM LiteRT reloads for each question)\n")
                    val loads = (0 until sessions.length()).map { sessions.getJSONObject(it).optDouble("engineLoadMs") }.filter { it.isFinite() }.sorted()
                    if (loads.isNotEmpty()) {
                        val medianLoad = (loads[(loads.size - 1) / 2] + loads[loads.size / 2]) / 2
                        append("Engine load: median %.2f s · range %.2f–%.2f s\n".format(medianLoad / 1000, loads.first() / 1000, loads.last() / 1000))
                    }
                }
                fun median(values: List<Double>): Double? {
                    val sorted = values.filter { it.isFinite() }.sorted()
                    if (sorted.isEmpty()) return null
                    return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
                }
                for ((label, group) in listOf("First question per engine" to first, "Reused engine" to warm)) {
                    median(group.map { it.optDouble("questionToFirstTextMs") })?.let {
                        append("$label: median first text %.2f s (${group.size} questions)\n".format(it / 1000))
                    }
                }
                median(completed.map { it.optDouble("questionMs") })?.let { append("Median question pipeline: %.2f s\n".format(it / 1000)) }
                if (result.optString("workload") == "rag-tools") {
                    median(completed.map { it.optDouble("toolDecisionMs") })?.let { append("Median tool decisions: %.2f s\n".format(it / 1000)) }
                    median(completed.map { it.optDouble("retrievalMs") })?.let { append("Median retrieval incl. query embeddings: %.2f s\n".format(it / 1000)) }
                    median(completed.map { it.optDouble("finalGenerationMs") })?.let { append("Median final answer generation: %.2f s\n".format(it / 1000)) }
                    append("No final answer: ${completed.count { !it.optBoolean("finalAnswerAvailable", true) }} questions\n")
                }
                median(completed.map { it.optDouble("endToEndMs") })?.let { append("Median incl. engine load/close: %.2f s\n".format(it / 1000)) }
                median(completed.map { it.optDouble("engineLoadMs") })?.let { append("Median per-question engine load: %.2f s\n".format(it / 1000)) }
                median(completed.map { it.optJSONObject("runtimeMetrics")?.optDouble("prefillTokensPerSecond") ?: Double.NaN })?.let {
                    append("Median prefill %.1f tokens/s\n".format(it))
                }
                median(completed.map { it.optJSONObject("runtimeMetrics")?.optDouble("decodeTokensPerSecond") ?: Double.NaN })?.let {
                    append("Median decode %.1f tokens/s\n".format(it))
                }
                append("Output limit reached: ${completed.count { it.optBoolean("outputTokenLimitReached") }} answers\n")
                completed.lastOrNull()?.optJSONObject("memoryAfter")?.let {
                    append("Last memory: ${it.optJSONArray("processMemory")} · native heap ${it.optLong("nativeHeapBytes") / 1_000_000} MB\n")
                    append("Available system RAM: ${it.optLong("systemAvailableBytes") / 1_000_000} MB · thermal status ${it.optInt("thermalStatus")}\n")
                }
                append("Citation/answer correctness: not automatically scored; reference answers are in the ZIP.\n")
                completed.lastOrNull()?.let { append("Last answer: ${it.optString("answer").take(400)}\n") }
            }
            if (result.has("firstVisibleTextMs") && !result.isNull("firstVisibleTextMs")) {
                append("First visible text: %.2f s\n".format(result.optDouble("firstVisibleTextMs") / 1000))
            }
            result.optJSONObject("runtimeMetrics")?.let {
                append("Prefill: ${it.optInt("prefillTokens")} tokens · %.1f tokens/s\n".format(it.optDouble("prefillTokensPerSecond")))
                append("Decode: ${it.optInt("decodeTokens")} tokens · %.1f tokens/s\n".format(it.optDouble("decodeTokensPerSecond")))
            }
            if (result.has("sanityPassed")) append("Answer check: ${if (result.optBoolean("sanityPassed")) "passed" else "FAILED"}\n")
            result.optJSONArray("nativeFallbackMessages")?.let {
                if (it.length() > 0) append("Native fallback notices: ${it.length()}; inspect the report for affected components.\n")
            }
            if (result.optString("error").isNotBlank()) append("${result.optString("error")}\n")
            if (result.optString("controllerError").isNotBlank()) append("${result.optString("controllerError")}\n")
            if (result.optString("answer").isNotBlank()) append("Answer: ${result.optString("answer").take(700)}\n")
            append("\n")
        }
        append("GPU completion confirms the requested backend ran. CPU participation is possible; inspect native device and placement logs.\n")
    }

    fun archive(run: File): File = synchronized(exportLock) {
        // 1. Snapshot results and raw native logs into a portable archive.
        val directory = File(context.filesDir, "reports").apply { mkdirs() }
        val archive = File(directory, "gpu-diagnostic-${run.name}.zip")
        val staging = File(directory, archive.name + ".part")
        val report = JSONObject().put("metadata", readJson(File(run, "metadata.json")))
            .put("queue", readJson(File(run, "queue-state.json"))).put("results", JSONArray(results(run)))
            .put("preparation", readJson(File(run, "preparation.json")) ?: JSONObject.NULL)
        ZipOutputStream(staging.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("report.json")); zip.write(report.toString(2).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.txt")); zip.write(summary(run).toByteArray()); zip.closeEntry()
            if (report.optJSONObject("metadata")?.optString("runtime")?.contains("llama.cpp") == true) {
                for (asset in listOf("native-provenance.json", "lfm3-chat-template.jinja", "gemma4-chat-template.jinja")) {
                    zip.putNextEntry(ZipEntry(asset)); context.assets.open(asset).use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            val fixture = File(run, "rag-replay.json")
            if (fixture.exists()) {
                zip.putNextEntry(ZipEntry("rag-replay.json")); fixture.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                zip.putNextEntry(ZipEntry("comparisons.jsonl"))
                val paired = comparisons(run)
                zip.write(paired.toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("answers-and-context.txt"))
                zip.write(comparisonText(paired).toByteArray()); zip.closeEntry()
            }
            run.listFiles().orEmpty().filter { it.extension in listOf("log", "trace") }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        check(staging.renameTo(archive)) { "Could not publish report ZIP" }
        archive
    }

    /** Join identical inputs, benchmark references and outputs without leaking references into inference. */
    fun comparisons(run: File): String {
        val fixture = readJson(File(run, "rag-replay.json")) ?: return ""
        val questions = fixture.getJSONArray("questions")
        val batches = results(run)
        val selected = readJson(File(run, "metadata.json"))?.optJSONArray("selectedQuestionIds")?.let { ids ->
            (0 until ids.length()).map { ids.getString(it) }.toSet()
        }
        return (0 until questions.length()).filter { selected == null || questions.getJSONObject(it).getString("id") in selected }
            .joinToString("\n", postfix = "\n") { index ->
            val question = questions.getJSONObject(index)
            val outputs = JSONArray()
            for (batch in batches) {
                val answers = batch.optJSONArray("answers") ?: JSONArray()
                val answer = (0 until answers.length()).map { answers.getJSONObject(it) }
                    .firstOrNull { it.optString("questionId") == question.getString("id") }
                outputs.put(JSONObject().put("configuration", batch.optString("id"))
                    .put("configurationStatus", batch.optString("status"))
                    .put("result", answer ?: JSONObject.NULL))
            }
            JSONObject(question.toString()).put("systemPrompt", fixture.getString("systemPrompt"))
                .put("outputs", outputs).toString()
        }
    }

    private fun comparisonText(paired: String): String = buildString {
        append("RAG generation comparison. Reference answers are evaluation-only. Semantic correctness is not automatically scored.\n\n")
        for (line in paired.lineSequence().filter { it.isNotBlank() }) {
            val question = JSONObject(line)
            append("QUESTION ${question.getString("id")}\n${question.getString("question")}\n\n")
            append("BENCHMARK REFERENCE\n${question.getJSONObject("reference").getString("answer")}\n\n")
            val outputs = question.getJSONArray("outputs")
            for (index in 0 until outputs.length()) {
                val output = outputs.getJSONObject(index)
                val answer = output.optJSONObject("result")
                append("${output.getString("configuration")} · ${answer?.optString("status") ?: "not run"}\n")
                append("${answer?.optString("answer") ?: ""}\n\n")
                answer?.optJSONArray("toolTrace")?.let { trace -> append("TOOL CONVERSATION (exact prompts, queries and evidence)\n${trace.toString(2)}\n\n") }
            }
            append("INITIAL USER PROMPT (context is here for replay; live evidence is in each tool trace above)\n${question.getString("prompt")}\n\n")
            append("============================================================\n\n")
        }
    }

    fun copyArchive(archive: File, destination: File): Unit = synchronized(exportLock) {
        archive.copyTo(destination, overwrite = true)
    }

    fun saveToDownloads(archive: File, displayName: String = archive.name): Unit = synchronized(exportLock) {
        // 2. Publish through MediaStore; no storage permission or ADB is needed.
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val snapshots = context.getSharedPreferences("published-reports", Context.MODE_PRIVATE)
        val previousUri = snapshots.getString(displayName, null)
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output -> archive.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // Keep the previous snapshot until the replacement is fully published.
            snapshots.edit().putString(displayName, uri.toString()).commit()
            if (previousUri != null) runCatching { resolver.delete(android.net.Uri.parse(previousUri), null, null) }
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
