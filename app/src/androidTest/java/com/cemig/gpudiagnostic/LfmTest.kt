package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LfmTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun templatePreservesPortugueseAndDisablesThinking() {
        val text = lfmPrompt("Instrução.", "Óculos [1].")
        assertEquals("<|startoftext|><|im_start|>system\nInstrução.<|im_end|>\n<|im_start|>user\nÓculos [1].<|im_end|>\n<|im_start|>assistant\n<think></think>", text)
    }

    @Test fun nativeLoadFailureBecomesAnExceptionWithoutFallback() {
        val error = runCatching { LlamaEngine("/does/not/exist.gguf", "cpu").close() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.toString(), error is IllegalStateException)
    }

    /** Optional real-model regression: A/B/A must not leak recurrent or attention state. */
    @Test fun repeatedQuestionAfterDifferentQuestionHasIdenticalGreedyOutput() {
        val path = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("lfmPath")
        assumeTrue("Supply -e lfmPath for real model state-reset coverage", path != null && File(path).exists())
        LlamaEngine(path!!, "cpu").use { engine ->
            val system = "Reply in one short sentence."
            val first = engine.answer(system, "What is the capital of France?") {}
            engine.answer(system, "What is the capital of Brazil?") {}
            val repeated = engine.answer(system, "What is the capital of France?") {}
            assertTrue(first.getString("answer"), first.getString("answer").contains("Paris"))
            assertEquals(first.getString("answer"), repeated.getString("answer"))
            assertEquals(first.getJSONObject("runtimeMetrics").getInt("prefillTokens"), repeated.getJSONObject("runtimeMetrics").getInt("prefillTokens"))
        }
    }
}
