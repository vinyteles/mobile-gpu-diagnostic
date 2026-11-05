package com.cemig.gpudiagnostic

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional end-to-end check with a supplied local model. Never downloads a model during unit checks. */
@RunWith(AndroidJUnit4::class)
class RealModelTest {
    @Test fun runProvidedModel() {
        val options = InstrumentationRegistry.getArguments()
        val modelPath = options.getString("modelPath")
        assumeTrue("Supply -e modelPath to run the real-model integration test", modelPath != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = options.getString("backend") ?: "cpu"
        val workload = options.getString("workload") ?: "short"
        val runtime = options.getString("runtime") ?: "litertlm"
        val run = File(context.filesDir, "integration/${System.currentTimeMillis()}").apply { mkdirs() }
        val caseFile = File(run, "gemma-real-$backend-$workload.json")
        writeJson(caseFile, JSONObject().put("id", "gemma-real-$backend-$workload").put("status", "pending")
            .put("requestedBackend", backend).put("workload", workload))
        if (options.getString("continuation") == "true") {
            val continuation = context.assets.open("gpu-continuation.json").bufferedReader().use { JSONObject(it.readText()) }
            val record = readJson(caseFile)!!
            record.put("selectedQuestionIds", continuation.getJSONArray("selectedQuestionIds"))
            writeJson(caseFile, record)
        }
        val arguments = Bundle().apply {
            putString("caseFile", caseFile.path)
            putString("modelPath", modelPath)
            putString("backend", backend)
            putString("runtime", runtime)
            putBoolean("freshEnginePerQuestion", options.getString("freshEngine") == "true")
            putString("modelFamily", options.getString("modelFamily") ?: "lfm")
            if (workload == "replay") { putBoolean("replay", true); putInt("questionLimit", 2) }
            putString("workload", workload)
            putString("prompt", if (workload == "short") SHORT_PROMPT else LONG_PROMPT)
            putString("engineCache", File(context.cacheDir, "integration/$backend").path)
        }
        val result = WorkerBridge(context).run(arguments, { false })
        if (runtime == "llama.cpp" && result.optString("status") == "completed") {
            assertEquals("llama.cpp", result.getString("runtime"))
            val device = result.getJSONObject("nativeEngineInfo").getString("deviceName")
            if (backend == "cpu") assertEquals("CPU", device) else assertTrue(device.startsWith("Vulkan"))
            assertTrue(result.getJSONArray("answers").getJSONObject(0).getString("formattedPromptSha256").isNotBlank())
        }
        if (backend == "cpu") {
            assertEquals(result.toString(2), "completed", result.optString("status"))
            if (workload == "replay") {
                assertEquals(if (options.getString("freshEngine") == "true") 2 else 1, result.getJSONArray("engineSessions").length())
                val answers = result.getJSONArray("answers")
                assertEquals(2, answers.length())
                if (options.getString("continuation") == "true") {
                    assertEquals(10, answers.getJSONObject(0).getInt("questionIndex"))
                    assertEquals(11, answers.getJSONObject(1).getInt("questionIndex"))
                }
                assertFalse(answers.getJSONObject(0).getBoolean("engineReused"))
                assertEquals(options.getString("freshEngine") != "true", answers.getJSONObject(1).getBoolean("engineReused"))
                for (index in 0 until answers.length()) {
                    val answer = answers.getJSONObject(index)
                    assertEquals("completed", answer.getString("status"))
                    assertTrue(answer.getString("answer").isNotBlank())
                    assertTrue(answer.getJSONObject("runtimeMetrics").getInt("prefillTokens") > 100)
                    assertTrue(answer.getJSONObject("runtimeMetrics").getInt("decodeTokens") in 1..OUTPUT_TOKENS)
                }
            } else {
                assertTrue(result.toString(2), result.optBoolean("sanityPassed"))
                assertTrue(result.toString(2), result.getJSONObject("runtimeMetrics").getInt("decodeTokens") > 0)
            }
        } else {
            // Emulator GPU incompatibility is expected; its failure must still be preserved.
            assertTrue(result.toString(2), result.optString("status") in listOf("completed", "error", "worker_exited", "timeout"))
            assertTrue(File(run, caseFile.nameWithoutExtension + ".log").exists())
        }
    }
}
