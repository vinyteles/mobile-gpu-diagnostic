package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReportImportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun fixtureRun(): File {
        val run = File(context.cacheDir, "import-test-${System.nanoTime()}").apply { mkdirs() }
        val fixture = File(run, "rag-replay.json")
        context.assets.open(APK_FIXTURE_ASSET).use { input -> fixture.outputStream().use { input.copyTo(it) } }
        writeJson(File(run, "metadata.json"), JSONObject().put("protocolId", PROTOCOL_ID).put("device", deviceInfo(context))
            .put("fixtureSha256", sha256(fixture)).put("smokeTest", true))
        val question = replayDataset(context).getJSONArray("questions").getJSONObject(0)
        val config = APK_CONFIGS.first()
        writeJson(File(run, "${config.id}.json"), JSONObject().put("id", config.id).put("status", "cancelled")
            .put("modelSha256", config.model.sha256).put("answers", JSONArray().put(JSONObject()
                .put("questionId", question.getString("id")).put("promptSha256", question.getString("promptSha256"))
                .put("status", "completed").put("answer", "Synthetic saved output [1]."))))
        return run
    }

    @Test fun partialReportRoundTripKeepsAnswersAndResumesOnNewSession() {
        val run = fixtureRun()
        val archive = ReportExporter(context).archive(run)
        val imported = archive.inputStream().use { ReportImporter(context).restore(it) }
        assertNotEquals(run.path, imported.path)
        val config = APK_CONFIGS.first()
        val file = File(imported, "${config.id}.json")
        val result = readJson(file)!!
        val original = result.getJSONArray("answers").getJSONObject(0).toString()
        runReplay(context, null, result, { writeJson(file, result) }, questionLimit = 3, smokeMode = "replay_success")
        assertEquals(original, result.getJSONArray("answers").getJSONObject(0).toString())
        assertEquals(3, result.getJSONArray("answers").length())
        assertFalse(result.getJSONArray("answers").getJSONObject(1).getBoolean("engineReused"))
        assertTrue(result.getJSONArray("answers").getJSONObject(2).getBoolean("engineReused"))
        assertNotNull(readJson(File(imported, "metadata.json"))!!.optJSONObject("importedOnDevice"))
    }

    @Test fun changedProtocolOrModelIsRejected() {
        val run = fixtureRun()
        val file = File(run, "${APK_CONFIGS.first().id}.json")
        val record = readJson(file)!!.put("modelSha256", "invalid")
        writeJson(file, record)
        assertTrue(runCatching { ReportExporter(context).archive(run).inputStream().use { ReportImporter(context).restore(it) } }.isFailure)
        val metadataFile = File(run, "metadata.json")
        writeJson(metadataFile, readJson(metadataFile)!!.put("protocolId", "old-30-question-run"))
        assertTrue(runCatching { ReportExporter(context).archive(run).inputStream().use { ReportImporter(context).restore(it) } }.isFailure)
    }

    @Test fun newDownloadSnapshotReplacesThePreviousUriAfterPublication() {
        val archive = ReportExporter(context).archive(fixtureRun())
        val name = "snapshot-test-${System.nanoTime()}.zip"
        val publisher = ReportExporter(context)
        publisher.saveToDownloads(archive, name)
        val preferences = context.getSharedPreferences("published-reports", Context.MODE_PRIVATE)
        val previous = android.net.Uri.parse(preferences.getString(name, null)!!)
        publisher.saveToDownloads(archive, name)
        val current = android.net.Uri.parse(preferences.getString(name, null)!!)
        assertNotEquals(previous, current)
        assertTrue(runCatching { context.contentResolver.openInputStream(previous)!!.close() }.isFailure)
        context.contentResolver.openInputStream(current)!!.use { assertEquals('P'.code, it.read()) }
        context.contentResolver.delete(current, null, null)
    }
}
