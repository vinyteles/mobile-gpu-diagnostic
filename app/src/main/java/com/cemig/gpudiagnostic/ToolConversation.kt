package com.cemig.gpudiagnostic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class SearchCall(val query: String, val nr: String?)

/** Parse only the trained LFM3 tool syntax; arbitrary function names never execute. */
internal fun parseSearchCall(text: String): SearchCall? {
    if (!text.contains("<tool_call>") && !text.contains("<function=")) return null
    val call = Regex("<tool_call>\\s*<function=buscar_norma>\\s*(.*?)\\s*</function>\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        .findAll(text).toList()
    require(call.size == 1 && Regex("<tool_call>").findAll(text).count() == 1) { "Expected one buscar_norma call" }
    val parameters = Regex("<parameter=(consulta|nr)>\\s*(.*?)\\s*</parameter>", RegexOption.DOT_MATCHES_ALL)
        .findAll(call.single().groupValues[1]).toList()
    require(parameters.map { it.groupValues[1] }.toSet() == setOf("consulta", "nr") && parameters.size == 2) { "Expected consulta and nr parameters" }
    require(Regex("<parameter=").findAll(call.single().groupValues[1]).count() == 2) { "Unknown tool argument" }
    val values = parameters.associate { it.groupValues[1] to it.groupValues[2].trim() }
    val query = values.getValue("consulta")
    require(query.isNotBlank() && query.length <= 2000 && !query.contains("<|")) { "Invalid search query" }
    val nr = values.getValue("nr").takeUnless { it == "null" || it.isBlank() }
    require(nr == null || Regex("(?i)NR[- _]?\\d{1,2}").matches(nr)) { "Invalid NR identifier" }
    return SearchCall(query, nr)
}

private fun hashPrompt(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    .joinToString("") { "%02x".format(it.toInt() and 255) }
private fun safeReference(text: String) = text.replace("<|", "‹|").replace("<tool_response>", "‹tool_response›").replace("</tool_response>", "‹/tool_response›")

/** Each question has fresh history; the answer model stays loaded across questions. */
class ToolConversation(private val context: Context, private val config: BenchmarkConfig, private val model: ToolGenerator,
                       initialRetriever: LiveRetriever? = null) : AutoCloseable {
    private var retriever: LiveRetriever? = initialRetriever
    fun answer(system: String, question: String, progress: (JSONObject) -> Unit): JSONObject {
        val start = System.nanoTime()
        val trace = JSONArray()
        val sources = mutableListOf<JSONObject>()
        var history = "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n${safeReference(question)}<|im_end|>\n"
        val suffix = "<|im_start|>assistant\n<think></think>"
        var retrievalMs = 0.0
        var decisionMs = 0.0
        var totalGenerated = 0
        var last = JSONObject()
        var stopReason = "end_of_generation"
        var finalAvailable = false
        // 1. The LLM chooses whether and how to search; no evidence is injected initially.
        for (turn in 0..2) {
            val prompt = history + suffix
            check(model.countTokens(prompt) + OUTPUT_TOKENS <= CONTEXT_TOKENS) { "Tool conversation exceeds context budget" }
            val event = JSONObject().put("turn", turn).put("prompt", prompt).put("formattedPromptSha256", hashPrompt(prompt))
                .put("inputTokens", model.countTokens(prompt)).put("phase", "generating")
            trace.put(event)
            progress(JSONObject().put("toolTrace", trace).put("toolTurn", turn).put("phase", "tool_decision_or_answer"))
            last = model.generate(prompt, progress)
            totalGenerated += last.getInt("streamCallbackCount")
            val raw = last.getString("answer")
            event.put("generation", JSONObject(last.toString())).put("phase", "generated")
            val parsed = runCatching { parseSearchCall(raw) }
            val call = parsed.getOrNull()
            if (parsed.isSuccess && call == null) { finalAvailable = true; break }
            decisionMs += last.getDouble("generationMs")
            if (turn == 2) { stopReason = "tool_limit"; event.put("phase", "tool_limit"); break }

            // 2. Run only the whitelisted search tool; each actual query reaches the selected retriever.
            history += "<|im_start|>assistant\n<think></think>$raw<|im_end|>\n"
            var response: String
            if (parsed.isFailure) {
                response = "Tool call rejected: ${parsed.exceptionOrNull()?.message}. Use the documented buscar_norma format or answer with the evidence already available."
                event.put("toolError", parsed.exceptionOrNull()?.message)
            } else {
                val requested = checkNotNull(call)
                val effective = requested.query + if (requested.nr != null && !Regex("(?i)\\bnr[\\s._-]*\\d{1,2}").containsMatchIn(requested.query)) " (${requested.nr})" else ""
                event.put("query", requested.query).put("nr", requested.nr ?: JSONObject.NULL).put("effectiveQuery", effective)
                event.put("phase", "retrieving")
                progress(JSONObject().put("toolTrace", trace).put("phase", "retrieving"))
                val retrievalStart = System.nanoTime()
                if (retriever == null) retriever = if (config.retrieval == "f6") F6Retriever(context, model::countTokens) else MobileRagRetriever(context)
                val retrieved = checkNotNull(retriever).search(effective)
                val elapsed = (System.nanoTime() - retrievalStart) / 1e6
                retrievalMs += elapsed
                event.put("retrievalMs", elapsed).put("retrievalTrace", retrieved.trace)
                    .put("retrievedSources", JSONArray(retrieved.sources))

                // 3. Fit whole passages to the actual model tokenizer; reserve room for the next decision.
                val accepted = mutableListOf<JSONObject>()
                val reserve = if (turn == 0) 512 else 0
                val prefix = history + "<|im_start|>tool\n<tool_response>\n"
                val ending = "\n</tool_response><|im_end|>\n" + suffix
                fun render(rows: List<JSONObject>) = rows.joinToString("\n\n") {
                    "[${it.getInt("rank")}] ${safeReference(it.getString("filename"))} | chunk: ${it.getInt("chunk_index")}\n${safeReference(it.getString("text"))}"
                }
                for (source in retrieved.sources) {
                    val row = JSONObject(source.toString()).put("rank", sources.size + accepted.size + 1)
                    val candidate = render(accepted + row)
                    if (model.countTokens(candidate) > 1800 || model.countTokens(prefix + candidate + ending) + OUTPUT_TOKENS + reserve > CONTEXT_TOKENS) break
                    accepted.add(row)
                }
                response = if (accepted.isEmpty()) "No retrieved passage fits the remaining context budget. State that the available evidence is insufficient." else render(accepted)
                sources.addAll(accepted)
                event.put("sources", JSONArray(accepted)).put("omittedSources", retrieved.sources.size - accepted.size)
            }
            event.put("toolResponse", response).put("phase", "tool_completed")
            history += "<|im_start|>tool\n<tool_response>\n$response\n</tool_response><|im_end|>\n"
            progress(JSONObject().put("toolTrace", trace).put("sources", JSONArray(sources)))
        }
        // 4. Keep decision, retrieval and final-generation timing separate and retain every raw turn.
        val total = (System.nanoTime() - start) / 1e6
        return last.put("questionMs", total).put("questionToFirstTextMs", if (finalAvailable) total - last.optDouble("generationMs") + last.optDouble("firstVisibleTextMs") else JSONObject.NULL)
            .put("toolDecisionMs", decisionMs).put("retrievalMs", retrievalMs).put("finalGenerationMs", if (finalAvailable) last.optDouble("generationMs") else 0)
            .put("totalGeneratedTokens", totalGenerated).put("sources", JSONArray(sources)).put("toolTrace", trace)
            .put("finalAnswerAvailable", finalAvailable).put("stopReason", if (finalAvailable) last.optString("stopReason") else stopReason)
            .put("retrieval", config.retrieval)
            .put("toolCallCount", (0 until trace.length()).count { trace.getJSONObject(it).has("retrievalTrace") })
            .put("toolRequestCount", trace.length() - if (finalAvailable) 1 else 0)
    }
    override fun close() { retriever?.close(); retriever = null }
}
