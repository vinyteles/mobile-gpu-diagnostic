package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class V3PipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun result(text: String) = JSONObject().put("answer", text).put("generationMs", 2.0).put("firstVisibleTextMs", 1.0)
    private class Search : LiveRetriever {
        val queries = mutableListOf<String>()
        override fun search(query: String): ToolEvidence {
            queries.add(query)
            val source = JSONObject().put("rank", 1).put("filename", "Synthetic manual").put("chunk_index", 0)
                .put("document_id", "synthetic").put("text", "Synthetic test marker: CEDAR-731.")
            return ToolEvidence(listOf(source), JSONObject().put("candidateSources", JSONArray().put(JSONObject(source.toString()))))
        }
        override fun close() {}
    }

    @Test fun gemmaFlagUsesOriginalQuestionAndFreshGroundedPrompt() {
        val search = Search()
        val prompts = mutableListOf<String>()
        LiteToolConversation(context, BENCHMARK_CONFIGS[17], null, search) { _, prompt, tools, _ ->
            assertFalse(tools); prompts.add(prompt)
            result(if (prompts.size == 1) "<call_tool>" else "CEDAR-731 [1].")
        }.use {
            val answer = it.answer("What is the verification marker?", {})
            assertEquals(listOf("What is the verification marker?"), search.queries)
            assertTrue(prompts.last().contains("CEDAR-731"))
            assertFalse(prompts.last().contains("<call_tool>"))
            assertTrue(answer.getBoolean("finalAnswerAvailable"))
            assertEquals(1, answer.getInt("modelToolCallCount"))
        }
    }

    @Test fun malformedFlagCannotBecomeSuccessfulAnswerOrAutomaticSearch() {
        val search = Search()
        LiteToolConversation(context, BENCHMARK_CONFIGS[17], null, search) { _, _, _, _ -> result("Here: <call_tool>") }.use {
            val answer = it.answer("A technical question", {})
            assertFalse(answer.getBoolean("finalAnswerAvailable"))
            assertEquals(1, answer.getInt("malformedToolCallCount"))
            assertTrue(search.queries.isEmpty())
        }
    }

    @Test fun greetingDoesNotSearch() {
        val search = Search()
        LiteToolConversation(context, BENCHMARK_CONFIGS[17], null, search) { _, _, _, _ -> result("Hello!") }.use {
            assertTrue(it.answer("Hi", {}).getBoolean("finalAnswerAvailable"))
            assertTrue(search.queries.isEmpty())
        }
    }

    @Test fun energyNativeCallAndFallbackRemainDistinct() {
        assertEquals("assentos", energyEffectiveQuery("assentos NR-10", "Quais requisitos dos assentos?"))
        assertEquals("NR-17 assentos", energySearch(result("<|tool_call>call:searchDocuments{query:<|\"|>NR-17 assentos<|\"|>}<tool_call|>")))
        for (native in listOf(true, false)) {
            val search = Search(); var turns = 0
            LiteToolConversation(context, BENCHMARK_CONFIGS[15], null, search) { _, _, tools, _ ->
                turns++
                if (turns == 1) {
                    assertTrue(tools)
                    if (native) result("").put("nativeToolCalls", JSONArray().put(JSONObject()
                        .put("name", "searchDocuments").put("arguments", JSONObject().put("query", "marker"))))
                    else result("I do not know.")
                } else { assertFalse(tools); result("CEDAR-731 (Fonte: Synthetic manual).") }
            }.use {
                val answer = it.answer("What is the exact verification marker here?", {})
                assertEquals(1, search.queries.size)
                assertEquals(if (native) 0 else 1, answer.getInt("fallbackSearchCount"))
                assertEquals(if (native) 1 else 0, answer.getInt("modelToolCallCount"))
                assertTrue(answer.getBoolean("finalAnswerAvailable"))
            }
        }
    }

    /** Explicit invocation only: pinned real model on CPU; synthetic evidence avoids corpus dependence. */
    @OptIn(ExperimentalApi::class)
    @Test fun realLiteRtCpuSmoke() {
        val args = InstrumentationRegistry.getArguments()
        val index = args.getString("v3ModelIndex")?.toInt() ?: return
        val config = BENCHMARK_CONFIGS[index]
        val model = File(context.filesDir, "models/${config.model.id}.litertlm")
        assertTrue("Missing model ${model.path}", model.isFile)
        assertEquals(config.model.sha256, sha256(model))
        Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(threadCount = 2), maxNumTokens = CONTEXT_TOKENS)).use { engine ->
            ExperimentalFlags.enableBenchmark = true
            engine.initialize()
            val search = Search()
            LiteToolConversation(context, config, engine, search).use {
                val answer = it.answer("Qual é o código de verificação registrado no manual?", {})
                File(context.filesDir, "v3-smoke-${config.toolPolicy}.json").writeText(answer.toString(2))
                assertTrue("Expected retrieval: $answer", search.queries.isNotEmpty())
                assertTrue("Expected final answer: $answer", answer.getBoolean("finalAnswerAvailable"))
                assertTrue("Expected evidence use: $answer", answer.getString("answer").contains("731"))
            }
        }
    }

    /** Explicit one-question integration using the actual pinned embedder, index and postprocessing. */
    @OptIn(ExperimentalApi::class)
    @Test fun realPipelineCpuSmoke() {
        val index = InstrumentationRegistry.getArguments().getString("v3PipelineIndex")?.toInt() ?: return
        val config = BENCHMARK_CONFIGS[index]
        val model = File(context.filesDir, "models/${config.model.id}.${config.model.extension}")
        assertEquals(config.model.sha256, sha256(model))
        val question = "Consulte os documentos: quais são os requisitos dos assentos na NR-17, incluindo altura, borda frontal e encosto?"
        val result = if (config.model.extension == "gguf") {
            val system = JSONObject(context.assets.open("v3-questions.json").bufferedReader().use { it.readText() }).getString("systemPrompt")
            LlamaEngine(model.path, "cpu").use { engine ->
                ToolConversation(context, config, engine).use { it.answer(system, question, {}) }
            }
        } else {
            Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(threadCount = 2), maxNumTokens = CONTEXT_TOKENS)).use { engine ->
                ExperimentalFlags.enableBenchmark = true
                engine.initialize()
                LiteToolConversation(context, config, engine).use { it.answer(question, {}) }
            }
        }
        result.put("smokeTest", true).put("testBackend", "cpu").put("configuration", config.id)
        File(context.filesDir, "v3-pipeline-${config.displayNumber}.json").writeText(result.toString(2))
        assertTrue("No final answer: $result", result.getBoolean("finalAnswerAvailable"))
        assertTrue("No retrieval: $result", result.getInt("toolCallCount") > 0)
        assertTrue("No supplied evidence: $result", result.getJSONArray("sources").length() > 0)
        val retrieval = result.getJSONArray("toolTrace").getJSONObject(0).getJSONObject("retrievalTrace")
        assertTrue(retrieval.getJSONArray("candidateSources").length() > 0)
    }
}
