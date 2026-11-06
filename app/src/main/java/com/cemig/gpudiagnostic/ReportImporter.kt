package com.cemig.gpudiagnostic

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Import only a matching protocol and fixture; never merge unrelated benchmark answers. */
class ReportImporter(private val context: Context) {
    fun restore(input: InputStream): File {
        // 1. Read a bounded set of known archive entries without extracting arbitrary paths.
        val entries = mutableMapOf<String, ByteArray>()
        var bytesRead = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in listOf("report.json", "rag-replay.json") || entry.name.matches(Regex("[a-zA-Z0-9_.-]+\\.(log|trace)"))) {
                    val buffer = ByteArray(64 * 1024)
                    val collected = java.io.ByteArrayOutputStream()
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        check(collected.size() + count <= 32 * 1024 * 1024) { "Report entry is too large." }
                        collected.write(buffer, 0, count)
                    }
                    val bytes = collected.toByteArray()
                    bytesRead += bytes.size
                    check(bytes.size <= 32 * 1024 * 1024 && bytesRead <= 128L * 1024 * 1024) { "Report is too large." }
                    check(entries.put(entry.name, bytes) == null) { "Duplicate ZIP entry." }
                }
                zip.closeEntry()
            }
        }
        val report = JSONObject(checkNotNull(entries["report.json"]) { "Missing report.json" }.toString(Charsets.UTF_8))
        val metadata = report.getJSONObject("metadata")
        check(metadata.optString("protocolId") == PROTOCOL_ID) { "Report belongs to a different benchmark protocol; use its matching APK." }
        check(BuildConfig.DEBUG || !metadata.optBoolean("smokeTest")) { "Synthetic test reports are not valid benchmarks." }
        val fixture = checkNotNull(entries["rag-replay.json"]) { "Missing frozen questions." }
        val bundled = context.assets.open(APK_FIXTURE_ASSET).use { it.readBytes() }
        check(fixture.contentEquals(bundled)) { "Report uses different questions, context, or settings." }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bundled).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(metadata.getString("fixtureSha256") == hash) { "Fixture checksum mismatch." }
        val questions = replayDataset(context).getJSONArray("questions")
        val prompts = (0 until questions.length()).associate { questions.getJSONObject(it).let { q -> q.getString("id") to q.getString("promptSha256") } }

        // 2. Validate every configuration and answer before publishing any imported state.
        val results = report.getJSONArray("results")
        val ids = mutableSetOf<String>()
        for (index in 0 until results.length()) {
            val record = results.getJSONObject(index)
            val config = BENCHMARK_CONFIGS.singleOrNull { it.id == record.optString("id") }
            check(config != null && ids.add(config.id)) { "Unknown or duplicate configuration." }
            check(config.fixtureAsset == APK_FIXTURE_ASSET) { "Configuration belongs to a different retrieval fixture." }
            check(record.getString("modelSha256") == config.model.sha256) { "Model checksum mismatch." }
            val answers = record.optJSONArray("answers")
            val answered = mutableSetOf<String>()
            if (answers != null) for (answerIndex in 0 until answers.length()) {
                val answer = answers.getJSONObject(answerIndex)
                val id = answer.getString("questionId")
                check(answered.add(id) && prompts[id] == answer.getString("promptSha256")) { "Unknown, duplicated, or changed question." }
                if (answer.optString("status") == "completed") check(answer.optString("answer").isNotBlank()) { "Completed answer is empty." }
                if (!answer.has("device")) answer.put("device", metadata.getJSONObject("device"))
            }
            if (record.optString("status") == "completed") {
                check(answers != null && answers.length() == QUESTION_COUNT &&
                    (0 until answers.length()).all { answers.getJSONObject(it).optString("status") == "completed" }) { "Incomplete configuration marked completed." }
            }
        }

        // 3. Create a new local run, preserving original device provenance and raw logs.
        val run = File(context.filesDir, "runs/import120-${System.currentTimeMillis()}")
        check(run.mkdirs())
        try {
            metadata.put("importedAtMs", System.currentTimeMillis()).put("importedOnDevice", deviceInfo(context))
            writeJson(File(run, "metadata.json"), metadata)
            File(run, "rag-replay.json").writeBytes(fixture)
            for (index in 0 until results.length()) {
                val record = results.getJSONObject(index)
                writeJson(File(run, "${record.getString("id")}.json"), record)
            }
            entries.filterKeys { it.endsWith(".log") || it.endsWith(".trace") }.forEach { (name, bytes) -> File(run, name).writeBytes(bytes) }
            report.optJSONObject("preparation")?.let { writeJson(File(run, "preparation.json"), it) }
            return run
        } catch (error: Throwable) { run.deleteRecursively(); throw error }
    }
}
