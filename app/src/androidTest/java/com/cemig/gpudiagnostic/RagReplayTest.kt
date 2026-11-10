package com.cemig.gpudiagnostic

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class RagReplayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun eachCompletedAnswerQueuesACheckpointBeforeTheNextQuestion() {
        val run = File(context.cacheDir, "question-checkpoints-${System.nanoTime()}").apply { mkdirs() }
        val file = File(run, "case.json")
        writeJson(file, JSONObject().put("status", "pending"))
        val counts = mutableListOf<Int>()
        val result = WorkerBridge(context).run(Bundle().apply {
            putString("caseFile", file.path); putString("smoke", "replay_success")
            putBoolean("replay", true); putInt("questionLimit", 3)
        }, { false }, 20_000, questionCompleted = {
            val saved = checkNotNull(readJson(file)).getJSONArray("answers")
            assertTrue((0 until saved.length()).all { saved.getJSONObject(it).getString("status") == "completed" })
            counts.add(saved.length())
        })
        assertEquals("completed", result.getString("status"))
        assertEquals(listOf(1, 2, 3), counts)
    }

    private fun runWorker(file: File, mode: String): JSONObject = WorkerBridge(context).run(Bundle().apply {
        putString("caseFile", file.path); putString("smoke", mode)
        putBoolean("replay", true); putInt("questionLimit", 3)
    }, { false }, 20_000)

    @Test fun frozenQuestionsHaveReferencesAndEvidence() {
        val data = replayDataset(context)
        val questions = data.getJSONArray("questions")
        assertEquals(120, questions.length())
        val ids = mutableSetOf<String>()
        for (index in 0 until questions.length()) {
            val question = questions.getJSONObject(index)
            assertTrue(ids.add(question.getString("id")))
            assertTrue(question.getString("prompt").contains(question.getString("question")))
            assertTrue(question.getJSONObject("reference").getString("answer").isNotBlank())
            assertTrue(question.getJSONObject("reference").getJSONArray("evidence").length() > 0)
            if (APK_CONFIGS.single().liveTools) assertEquals(question.getString("question"), question.getString("prompt"))
            else assertTrue(question.getJSONArray("sources").length() > 0)
        }
    }

    @Test fun crashRetainsAnswerAndResumeUsesNewSessionThenExportsJoinedReferences() {
        val run = File(context.cacheDir, "replay-test-${System.nanoTime()}").apply { mkdirs() }
        val file = File(run, "gemma-replay-test.json")
        writeJson(file, JSONObject().put("id", "gemma-replay-test").put("status", "pending"))
        val crashed = runWorker(file, "replay_crash")
        assertEquals("worker_exited", crashed.getString("status"))
        val first = crashed.getJSONArray("answers").getJSONObject(0).toString()
        assertEquals("completed", crashed.getJSONArray("answers").getJSONObject(0).getString("status"))
        val resumed = runWorker(file, "replay_success")
        assertEquals("completed", resumed.getString("status"))
        val answers = resumed.getJSONArray("answers")
        assertEquals(3, answers.length())
        assertEquals(first, answers.getJSONObject(0).toString())
        assertFalse(answers.getJSONObject(1).getBoolean("engineReused"))
        assertTrue(answers.getJSONObject(2).getBoolean("engineReused"))
        assertNotEquals(answers.getJSONObject(0).getString("engineSessionId"), answers.getJSONObject(1).getString("engineSessionId"))
        assertEquals(answers.getJSONObject(1).getString("engineSessionId"), answers.getJSONObject(2).getString("engineSessionId"))
        assertEquals(2, resumed.getJSONArray("engineSessions").length())
        writeJson(File(run, "rag-replay.json"), replayDataset(context))
        ZipFile(ReportExporter(context).archive(run)).use { zip ->
            val lines = zip.getInputStream(zip.getEntry("comparisons.jsonl")).bufferedReader().readLines()
            assertEquals(120, lines.size)
            val joined = JSONObject(lines.first())
            assertTrue(joined.getJSONObject("reference").getString("answer").isNotBlank())
            assertEquals("completed", joined.getJSONArray("outputs").getJSONObject(0).getJSONObject("result").getString("status"))
            assertNotNull(zip.getEntry("rag-replay.json"))
        }
    }

    @Test fun failedQuestionRetainsEarlierOutput() {
        val run = File(context.cacheDir, "replay-error-${System.nanoTime()}").apply { mkdirs() }
        val file = File(run, "gemma-replay-error.json")
        writeJson(file, JSONObject().put("status", "pending"))
        val result = runWorker(file, "replay_error")
        assertEquals("error", result.getString("status"))
        assertEquals("completed", result.getJSONArray("answers").getJSONObject(0).getString("status"))
        assertEquals("error", result.getJSONArray("answers").getJSONObject(1).getString("status"))
    }


    @Test fun balancedCategoriesAndGpuOrdering() {
        val questions = replayDataset(context).getJSONArray("questions")
        val rows = (0 until questions.length()).map { questions.getJSONObject(it) }
        assertEquals(mapOf("clean" to 30, "technical" to 30, "operational" to 30, "lay" to 30), rows.groupingBy { it.getString("vocabularyLevel") }.eachCount())
        assertEquals(30, rows.map { it.getString("baseQaId") }.distinct().size)
        assertTrue(rows.groupBy { it.getString("baseQaId") }.values.all { it.size == 4 })
        assertEquals(listOf("gemma-mixed48", "lfm3-q4km"), BENCHMARK_CONFIGS.take(2).map { it.model.id })
        assertTrue(BENCHMARK_CONFIGS.take(5).all { it.isGpu })
        assertTrue(BENCHMARK_CONFIGS.take(9).drop(5).all { !it.isGpu })
        assertTrue(BENCHMARK_CONFIGS.filter { it.liveTools }.all { it.isGpu })
        assertTrue(BENCHMARK_CONFIGS[2].freshEngine)
        assertTrue(BENCHMARK_CONFIGS[4].memoryConditional)
        for (row in rows) {
            val counts = row.optJSONObject("formattedInputTokens") ?: continue
            counts.keys().forEach { assertTrue(counts.getInt(it) + OUTPUT_TOKENS + 128 <= CONTEXT_TOKENS) }
        }
    }

    @Test fun citationRangeCheckNeverClaimsSemanticCorrectness() {
        val sources = JSONArray().put(JSONObject().put("rank", 1)).put(JSONObject().put("rank", 2))
        val check = citationCheck("First claim [1, 2]. Another [99]. Again [1].", sources)
        assertEquals("[1,2,99]", check.getJSONArray("citedRanks").toString())
        assertEquals("[99]", check.getJSONArray("unknownRanks").toString())
        assertEquals("unscored", check.getString("correctness"))
        assertFalse(citationCheck("No citation", sources).getBoolean("hasCitations"))
    }
}
