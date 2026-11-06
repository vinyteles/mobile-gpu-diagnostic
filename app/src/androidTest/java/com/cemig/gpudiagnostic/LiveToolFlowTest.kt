package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** CPU-only integration smoke tests; these are not GPU benchmark measurements. */
@RunWith(AndroidJUnit4::class)
class LiveToolFlowTest {
    private fun runCase(case: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = BENCHMARK_CONFIGS.single { it.matrixCase == case }
        val fixture = context.assets.open("tool-questions.json").bufferedReader().use { org.json.JSONObject(it.readText()) }
        val question = "Consulte a ferramenta buscar_norma: quais são os requisitos mínimos para assentos na NR-17, incluindo altura, borda frontal e encosto?"
        LlamaEngine(File(context.filesDir, "models/${config.model.id}.gguf").path, "cpu").use { model ->
            ToolConversation(context, config, model).use { conversation ->
                val result = conversation.answer(fixture.getString("systemPrompt"), question) { live ->
                    writeJson(File(context.filesDir, "live-smoke-$case-progress.json"), live)
                }.put("testBackend", "cpu").put("smokeTest", true)
                writeJson(File(context.filesDir, "live-smoke-$case.json"), result)
                assertTrue(result.toString(), result.getBoolean("finalAnswerAvailable"))
                assertTrue(result.getJSONArray("sources").length() > 0)
                assertTrue(result.getJSONArray("toolTrace").length() in 2..3)
                val initial = result.getJSONArray("toolTrace").getJSONObject(0).getString("prompt")
                assertFalse(initial.contains("<tool_response>"))
                assertTrue(result.getDouble("retrievalMs") > 0)
            }
        }
    }
    @Test fun baseModelUsesMobileRagSearch() = runCase("A")
    @Test fun tunedQ4kmUsesF6Search() = runCase("D")
    @Test fun tunedQ4UsesF6Search() = runCase("F")
}
