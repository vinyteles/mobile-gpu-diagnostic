package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolLoopTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val config = BENCHMARK_CONFIGS.single { it.matrixCase == "A" }
    private fun call(query: String) = "<tool_call>\n<function=buscar_norma>\n<parameter=consulta>\n$query\n</parameter>\n<parameter=nr>\nnull\n</parameter>\n</function>\n</tool_call>"
    private class Generator(private val turns: List<String>) : ToolGenerator {
        val prompts = mutableListOf<String>()
        override fun countTokens(text: String) = text.length / 3 + 1
        override fun generate(prompt: String, progress: (JSONObject) -> Unit): JSONObject {
            val raw = turns[prompts.size]; prompts.add(prompt)
            return JSONObject().put("answer", raw).put("streamCallbackCount", 20).put("generationMs", 10.0)
                .put("firstVisibleTextMs", 1.0).put("stopReason", "end_of_generation")
        }
    }
    private class Retriever : LiveRetriever {
        val queries = mutableListOf<String>()
        override fun search(query: String): ToolEvidence {
            queries.add(query)
            return ToolEvidence(listOf(JSONObject().put("filename", "Source").put("chunk_index", queries.size).put("text", "Evidence for $query")))
        }
        override fun close() {}
    }
    @Test fun rewrittenSecondQueryExecutesAndKeepsDistinctCitationRanks() {
        val model = Generator(listOf(call("original question"), call("focused second search"), "Answer [1] [2]."))
        val retrieval = Retriever()
        ToolConversation(context, config, model, retrieval).use {
            val result = it.answer("Shared tool instruction", "original question") {}
            assertEquals(listOf("original question", "focused second search"), retrieval.queries)
            assertEquals(2, result.getInt("toolCallCount"))
            assertEquals(2, result.getJSONArray("sources").getJSONObject(1).getInt("rank"))
            assertFalse(model.prompts[0].contains("Evidence"))
            assertTrue(model.prompts[2].contains("Evidence for focused second search"))
        }
    }
    @Test fun thirdSearchIsRecordedButNeverExecuted() {
        val model = Generator(listOf(call("first"), call("second"), call("third")))
        val retrieval = Retriever()
        val result = ToolConversation(context, config, model, retrieval).use { it.answer("Tool instruction", "question") {} }
        assertEquals(listOf("first", "second"), retrieval.queries)
        assertFalse(result.getBoolean("finalAnswerAvailable"))
        assertTrue(result.isNull("questionToFirstTextMs"))
        assertEquals("tool_limit", result.getString("stopReason"))
        assertEquals(2, result.getInt("toolCallCount"))
        assertEquals(3, result.getInt("toolRequestCount"))
        assertEquals(call("third"), result.getString("answer"))
    }
    @Test fun directAnswerDoesNotCauseAnUnrequestedRetrieval() {
        val model = Generator(listOf("Insufficient evidence.")); val retrieval = Retriever()
        val result = ToolConversation(context, config, model, retrieval).use { it.answer("Tool instruction", "question") {} }
        assertTrue(retrieval.queries.isEmpty())
        assertTrue(result.getBoolean("finalAnswerAvailable"))
        assertEquals(0, result.getInt("toolCallCount"))
    }
}
