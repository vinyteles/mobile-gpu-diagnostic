package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CoordinatorTest {
    @Test fun changingBatchSelectsItsPresetAndPreservesTheExistingRunAndCredentials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("benchmark-120-v2", Context.MODE_PRIVATE)
        val before = Coordinator(context)
        val run = before.currentRun
        val state = File(context.filesDir, "queue-benchmark-120-v2.json").takeIf { it.isFile }?.readText()
        val settings = CloudSettings(context)
        val savedToken = settings.token()
        val repository = settings.repository
        preferences.edit().putInt("batch", 1).putStringSet("configurations", BENCHMARK_CONFIGS.map { it.id }.toSet()).commit()

        val switched = Coordinator(context)

        assertEquals(BATCH_CONFIG_IDS, switched.selectedIds)
        assertEquals(1, switched.selectedIds.size)
        assertEquals("downloads", switched.stage)
        assertEquals(run, switched.currentRun)
        assertEquals(state, File(context.filesDir, "queue-benchmark-120-v2.json").takeIf { it.isFile }?.readText())
        assertEquals(savedToken, settings.token())
        assertEquals(repository, settings.repository)
    }

    @Test fun fullQueueRunsEstablishedGpusFirstExportsAndResumesWithoutRepeatingAnswers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The separate single-configuration test exercises per-question export at release settings.
        val configs = BENCHMARK_CONFIGS.filter { it.fixtureAsset == APK_FIXTURE_ASSET }
        val coordinator = Coordinator(context, configs, reportEachQuestion = false)
        context.getSharedPreferences("benchmark-120-v2", Context.MODE_PRIVATE).edit().putStringSet("configurations", configs.map { it.id }.toSet()).commit()
        fun finish() {
            val deadline = System.currentTimeMillis() + 600_000
            while (coordinator.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertFalse("Controller did not finish: ${coordinator.status}", coordinator.isRunning)
        }
        coordinator.start(newRun = true, smoke = true)
        finish()
        val run = checkNotNull(coordinator.currentRun)
        val originals = configs.associate { config ->
            val result = checkNotNull(readJson(File(run, "${config.id}.json")))
            assertEquals(result.toString(), "completed", result.getString("status"))
            assertEquals(120, result.getJSONArray("answers").length())
            assertEquals(1, result.getJSONArray("engineSessions").length())
            config.id to result.toString()
        }
        val records = configs.map { checkNotNull(readJson(File(run, "${it.id}.json"))) }
        for (index in 1 until records.size) assertTrue(records[index].getLong("startedAtMs") >= records[index - 1].getLong("finishedAtMs"))
        assertTrue(coordinator.reportStatus, coordinator.reportStatus.startsWith("Latest report saved"))
        val archive = ReportExporter(context).archive(run)
        val imported = archive.inputStream().use { ReportImporter(context).restore(it) }
        assertEquals(configs.size, ReportExporter(context).results(imported).size)
        coordinator.start(smoke = true)
        finish()
        for (config in configs) assertEquals(originals[config.id], readJson(File(run, "${config.id}.json"))!!.toString())
        assertEquals("completed", readJson(File(run, "queue-state.json"))!!.getString("status"))
    }

    @Test fun apkRunsOnlyItsConfigurationDespiteOldSelectionsAndResumesIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("benchmark-120-v2", Context.MODE_PRIVATE)
        preferences.edit().putInt("batch", BuildConfig.BENCHMARK_PROFILE)
            .putStringSet("configurations", BENCHMARK_CONFIGS.map { it.id }.toSet()).commit()
        val coordinator = Coordinator(context)
        fun finish() {
            val deadline = System.currentTimeMillis() + 180_000
            while (coordinator.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertFalse(coordinator.status, coordinator.isRunning)
        }
        coordinator.start(newRun = true, smoke = true)
        finish()
        val run = checkNotNull(coordinator.currentRun)
        val records = ReportExporter(context).results(run)
        assertEquals(1, records.size)
        assertEquals(APK_CONFIGS.single().id, records.single().getString("id"))
        assertEquals(120, records.single().getJSONArray("answers").length())
        assertEquals(requiredArtifacts(context, APK_CONFIGS.single()).map { it.id }.toSet(), coordinator.downloads.keys)
        val original = records.single().toString()
        coordinator.start(smoke = true)
        finish()
        assertEquals(original, ReportExporter(context).results(run).single().toString())
    }
}
