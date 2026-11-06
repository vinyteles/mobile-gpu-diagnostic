package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires the pinned data files in files/models; no network, HF publication or GPU inference. */
@RunWith(AndroidJUnit4::class)
class LiveRetrievalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use { it.readText() }
    private fun vector(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }

    @Test fun toolParserAcceptsOnlyOneWhitelistedSearch() {
        assertNull(parseSearchCall("The answer is unavailable."))
        val call = "<tool_call>\n<function=buscar_norma>\n<parameter=consulta>\nNR-10 proteção\n</parameter>\n<parameter=nr>\nNR-10\n</parameter>\n</function>\n</tool_call>"
        assertEquals(SearchCall("NR-10 proteção", "NR-10"), parseSearchCall(call))
        assertTrue(runCatching { parseSearchCall(call.replace("buscar_norma", "execute_shell")) }.isFailure)
        assertTrue(runCatching { parseSearchCall(call + call) }.isFailure)
        assertTrue(runCatching { parseSearchCall(call.replace("NR-10\n</parameter>", "invalid\n</parameter>")) }.isFailure)
    }

    @Test fun mobileRetrievalMatchesExistingThirtyQuestionVectors() {
        val questions = JSONObject(fixture("mobile-retrieval-parity.json")).getJSONArray("questions")
        MobileRagRetriever(context).use { retriever ->
            for (index in 0 until questions.length()) {
                val q = questions.getJSONObject(index)
                val result = retriever.fromVectors(vector(q.getJSONArray("qwen_vector")), vector(q.getJSONArray("granite_vector")))
                val expected = q.getJSONArray("documents")
                val hits = q.getJSONObject("search").getJSONArray("hits")
                assertEquals((0 until hits.length()).map { hits.getJSONObject(it).getLong("label") }, (0 until result.trace.getJSONArray("hitLabels").length()).map { result.trace.getJSONArray("hitLabels").getLong(it) })
                assertEquals("question $index source count", expected.length(), result.sources.size)
                for (rank in 0 until expected.length()) {
                    val actual = result.sources[rank]; val original = expected.getJSONObject(rank)
                    // The original TypeScript parity suite permits float32 ties within 1e-5.
                    assertEquals(original.getDouble("scr_score"), actual.getDouble("scr_score"), 0.00001)
                    val matching = (0 until expected.length()).map { expected.getJSONObject(it) }.single { it.getLong("label") == actual.getLong("label") }
                    assertEquals("question $index label ${actual.getLong("label")}", matching.getString("text"), actual.getString("text"))
                    assertEquals(original.getDouble("scr_score"), actual.getDouble("scr_score"), 0.00001)
                }
            }
        }
    }

    @Test fun f6RetrievalMatchesPublishedPythonIncludingRewrittenQueries() {
        val rows = JSONArray(fixture("f6-retrieval-parity.json"))
        LlamaEngine(File(context.filesDir, "models/lfm3-q4km.gguf").path, "cpu").use { model ->
            F6Retriever(context, model::countTokens).use { retriever ->
                for (index in 0 until rows.length()) {
                    val q = rows.getJSONObject(index)
                    val result = retriever.fromVector(q.getString("question"), vector(q.getJSONArray("vector")))
                    assertEquals("question $index retrieved IDs", q.getJSONArray("retrieved_ids").toString(), result.trace.getJSONArray("ids").toString())
                    assertEquals("question $index gate", q.getString("gate_mode"), result.trace.getString("mode"))
                    assertEquals("question $index budget", q.getInt("budget"), result.trace.getInt("budget"))
                    assertEquals("question $index context", q.getString("context"), result.trace.getString("context"))
                }
            }
        }
    }

    @Test fun nativeEmbeddingMatchesPinnedEmbeddingGemmaQuery() {
        val row = JSONArray(fixture("f6-retrieval-parity.json")).getJSONObject(0)
        val expected = vector(row.getJSONArray("vector"))
        val actual = queryEmbedding(retrievalFile(context, "f6-embedder", "f6"), "task: search result | query: " + row.getString("question"), 1, 2048)
        assertEquals(expected.size, actual.size)
        assertTrue("Embedding cosine ${vectorDot(expected, actual)}", vectorDot(expected, actual) > 0.9999)
    }
}
