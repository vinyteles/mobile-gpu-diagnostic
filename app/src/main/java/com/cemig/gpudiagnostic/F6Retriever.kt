package com.cemig.gpudiagnostic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.zip.ZipFile
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

private fun deaccent(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
private val unicodeWord = Regex("[\\p{L}\\p{N}_]+")

/** NRC2 classifier: TF-IDF character/word n-grams followed by logistic regression. */
internal class NrClassifier(file: File) {
    private val classes: List<String>
    private val charMap = mutableMapOf<String, Int>()
    private val wordMap = mutableMapOf<String, Int>()
    private val idf: FloatArray
    private val coefficients: FloatArray
    private val intercepts: FloatArray
    private val nChar: Int
    init {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        check(String(ByteArray(4).also { buffer.get(it) }) == "NRC2")
        val nClasses = buffer.short.toInt() and 65535
        val features = buffer.int; nChar = buffer.int
        fun string(): String = ByteArray(buffer.short.toInt() and 65535).also { buffer.get(it) }.toString(Charsets.UTF_8)
        classes = List(nClasses) { string() }
        idf = FloatArray(features)
        for (column in 0 until features) {
            val name = string(); idf[column] = buffer.float
            (if (column < nChar) charMap else wordMap)[name] = column
        }
        coefficients = FloatArray(features * nClasses) { buffer.float }
        intercepts = FloatArray(nClasses) { buffer.float }
        check(!buffer.hasRemaining())
    }
    fun predict(query: String): Pair<List<String>, Double> {
        val text = deaccent(query).lowercase(java.util.Locale.ROOT).trim()
        val counts = DoubleArray(idf.size)
        for (token in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val word = " $token "
            for (n in 2..5) {
                var offset = 0
                charMap[word.take(n)]?.let { counts[it]++ }
                while (offset + n < word.length) { offset++; charMap[word.substring(offset, offset + n)]?.let { counts[it]++ } }
                if (offset == 0) break
            }
        }
        val words = Regex("[\\p{L}\\p{N}_]{2,}").findAll(text).map { it.value }.toList()
        for (n in 1..2) for (i in 0..words.size - n) wordMap[words.subList(i, i + n).joinToString(" ")]?.let { counts[it]++ }
        for (i in counts.indices) if (counts[i] > 0) counts[i] = (1 + ln(counts[i])) * idf[i]
        for (range in listOf(0 until nChar, nChar until idf.size)) {
            val norm = sqrt(range.sumOf { counts[it] * counts[it] })
            if (norm > 0) for (i in range) counts[i] /= norm
        }
        val logits = classes.indices.map { c -> intercepts[c] + counts.indices.sumOf { coefficients[c * idf.size + it] * counts[it] } }
        val maximum = logits.max()
        val probabilities = logits.map { exp(it - maximum) }
        val total = probabilities.sum()
        val order = probabilities.indices.sortedByDescending { probabilities[it] }
        return order.take(2).map { classes[it] } to (probabilities[order.first()] / total)
    }
}

private data class DenseMatrix(val ids: IntArray, val values: FloatArray, val dimension: Int) {
    fun rank(query: FloatArray): List<Pair<Int, Float>> {
        check(query.size == dimension)
        return ids.indices.map { row ->
            var dot = 0.0
            for (i in query.indices) dot += values[row * dimension + i].toDouble() * query[i]
            ids[row] to dot.toFloat()
        }.sortedByDescending { it.second }.take(60)
    }
}

private fun dvec(file: File): DenseMatrix {
    val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    check(String(ByteArray(5).also { buffer.get(it) }) == "DVEC1")
    val dimension = buffer.int; val count = buffer.int
    val ids = IntArray(count); val values = FloatArray(count * dimension)
    for (row in 0 until count) { ids[row] = buffer.int; for (i in 0 until dimension) values[row * dimension + i] = buffer.float }
    return DenseMatrix(ids, values, dimension)
}

private fun npy(bytes: ByteArray): Pair<String, ByteBuffer> = npyBuffer(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))

private fun npyBuffer(buffer: ByteBuffer): Pair<String, ByteBuffer> {
    check(ByteArray(6).also { buffer.get(it) }.contentEquals(byteArrayOf(-109, 78, 85, 77, 80, 89)))
    val version = buffer.get().toInt()
    buffer.position(8)
    val size = if (version == 1) buffer.short.toInt() and 65535 else buffer.int
    val header = ByteArray(size).also { buffer.get(it) }.toString(Charsets.US_ASCII)
    check(header.contains("'fortran_order': False"))
    return header to buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
}

/** Port of pinned Python F6 retrieval: lexical/dense fusion, NR gating and hybrid compression. */
class F6Retriever(private val context: Context, private val countTokens: (String) -> Int) : LiveRetriever {
    private fun file(id: String) = retrievalFile(context, id, "f6")
    private val normFile = file("f6-index-db")
    private val manualFile = file("f6-manuals-db")
    private val normDb = openReadOnly(normFile)
    private val manualDb = openReadOnly(manualFile)
    private val classifier = NrClassifier(file("f6-nr-classifier-bin"))
    private val textDense = dvec(file("f6-dense-text-bin"))
    private val expansionDense = dvec(file("f6-dense-exponly-bin"))
    private val manualDense: DenseMatrix
    private val units = JSONObject(file("f6-units-json").readText())
    private val unitVectors: ByteBuffer
    private val bases = mutableMapOf<Int, Int>()
    private val chunks = mutableMapOf<Int, JSONObject>()
    private val stopwords = context.assets.open("f6-stopwords.json").bufferedReader().use { JSONArray(it.readText()) }.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    init {
        ZipFile(file("f6-manuals-dense-npz")).use { zip ->
            val (ih, ib) = npy(zip.getInputStream(zip.getEntry("ids.npy")).readBytes())
            val ids = if (ih.contains("i8")) IntArray(ib.remaining() / 8) { ib.long.toInt() } else IntArray(ib.remaining() / 4) { ib.int }
            val (vh, vb) = npy(zip.getInputStream(zip.getEntry("vecs.npy")).readBytes())
            check(vh.contains("f4"))
            val values = FloatArray(vb.remaining() / 4) { vb.float }
            manualDense = DenseMatrix(ids, values, values.size / ids.size)
        }
        // Keep the published fp16 windows mapped; expanding them to float32 exceeds small Android heaps.
        val (header, buffer) = FileInputStream(file("f6-units-win3-f16-npy")).channel.use {
            npyBuffer(it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.LITTLE_ENDIAN))
        }
        check(header.contains("f2"))
        unitVectors = buffer
        var offset = 0
        for (id in units.keys().asSequence().map { it.toInt() }.sorted()) {
            bases[id] = offset; offset += units.getJSONObject(id.toString()).getJSONArray("units").length()
        }
        check(offset * 768 * 2 == unitVectors.remaining())
        for ((db, table) in listOf(normDb to "chunks", manualDb to "mchunks")) {
            db.rawQuery("SELECT id,doc,section,title,text FROM $table", null).use { c ->
                while (c.moveToNext()) chunks[c.getInt(0)] = JSONObject().put("doc", c.getString(1)).put("section", c.getString(2))
                    .put("title", c.getString(3)).put("text", c.getString(4))
            }
        }
    }
    private fun lexical(text: String) = unicodeWord.findAll(deaccent(text).lowercase(java.util.Locale.ROOT)).map { it.value }
        .filter { it.length > 2 && it !in stopwords }.toList()
    private fun ftsQuery(query: String): String {
        val raw = Regex("[\\p{L}\\p{N}_.-]+").findAll(deaccent(query).lowercase(java.util.Locale.ROOT)).map { it.value }.toList()
        val selected = raw.filter { it.length > 2 && it !in stopwords }.ifEmpty { raw.filter { it.length > 2 } }
        return selected.joinToString(" OR ") { value -> val stem = value.take(6); if ('-' in stem || '.' in stem) "\"$stem\"*" else "$stem*" }
    }
    private fun bm(query: String, manuals: Boolean = false, nr: String = ""): List<JSONObject> {
        if (query.isEmpty()) return emptyList()
        val result = JSONArray(LlamaNative.searchFts((if (manuals) manualFile else normFile).path.toByteArray(), query.toByteArray(), manuals, nr.toByteArray()).toString(Charsets.UTF_8))
        return (0 until result.length()).map { result.getJSONObject(it) }
    }
    private fun searchIds(query: String, vector: FloatArray, threshold: Double, boost: Double, manuals: Boolean): JSONObject {
        val fq = ftsQuery(query)
        val explicit = Regex("(?i)\\bnr[\\s._-]*(\\d{1,2})\\b").find(query)?.groupValues?.get(1)?.toInt()?.let { "nr-%02d".format(java.util.Locale.ROOT, it) }
        val (predictions, probability) = if (explicit == null) classifier.predict(query) else listOf(explicit) to 1.0
        val mode = if (explicit != null) "explicit" else if (predictions.first() == "nenhuma") "none" else if (probability >= threshold) "hard" else "soft"
        val broad = bm(fq)
        val lexical = when (mode) {
            "explicit", "hard" -> { val filtered = bm(fq, nr = predictions.first()); val seen = filtered.map { it.getInt("id") }.toSet(); (filtered + broad.filter { it.getInt("id") !in seen }).take(60) }
            "soft" -> broad.sortedBy { it.getDouble("score") * if (it.getString("doc") in predictions && it.getString("doc") != "nenhuma") boost else 1.0 }
            else -> broad
        }
        val text = textDense.rank(vector); val expansion = expansionDense.rank(vector); val man = if (manuals) manualDense.rank(vector) else emptyList()
        val enabled = manuals && man.first().second >= text.first().second
        val signals = mutableListOf(lexical.map { it.getInt("id") } to 2.0, text.map { it.first } to 1.0, expansion.map { it.first } to 2.0)
        if (enabled) { signals.add(bm(fq, true).map { it.getInt("id") } to 1.75); signals.add(man.map { it.first } to 1.75) }
        val scores = linkedMapOf<Int, Double>()
        for ((ids, weight) in signals) ids.forEachIndexed { index, id -> scores[id] = (scores[id] ?: 0.0) + weight / (10 + index + 1) }
        return JSONObject().put("ids", JSONArray(scores.entries.sortedByDescending { it.value }.take(5).map { it.key }))
            .put("mode", mode).put("manuals_on", enabled).put("classifierTop2", JSONArray(predictions)).put("classifierConfidence", probability)
    }
    private fun label(id: Int): String = chunks.getValue(id).let { "${it.getString("doc")} - ${it.getString("section")}" + if (it.optString("title").isNotEmpty()) " - ${it.getString("title")}" else "" }
    private fun prefix(id: Int) = units.getJSONObject(id.toString()).getString("prefix").trimEnd(' ', '—').trim()
    private fun plain(id: Int, unit: Int): String {
        val record = units.getJSONObject(id.toString()).getJSONArray("units").getJSONArray(unit)
        val raw = chunks.getValue(id).getString("text")
        val split = raw.indexOf(" — ")
        val body = if (split in 0..300) raw.substring(split + 3) else raw
        return body.substring(body.offsetByCodePoints(0, record.getInt(0)), body.offsetByCodePoints(0, record.getInt(1)))
    }
    private fun unitText(id: Int, unit: Int): String {
        val record = units.getJSONObject(id.toString()).getJSONArray("units").getJSONArray(unit)
        val item = record.optString(2, "")
        return (if (record.getInt(3) == 0 && item.isNotEmpty()) "(item $item) " else "") + plain(id, unit)
    }
    private fun render(selected: Map<Int, List<Int>>, ids: List<Int>): String = ids.filter { !selected[it].isNullOrEmpty() }.mapIndexed { rank, id ->
        var previous = -2
        val pieces = selected.getValue(id).sorted().map { unit ->
            val value = (if (previous >= 0 && unit != previous + 1) "[…] " else "") + if (unit == previous + 1) plain(id, unit) else unitText(id, unit)
            previous = unit; value
        }
        "[${rank + 1}] (${label(id)}):\n${prefix(id)} — ${pieces.joinToString(" ")}"
    }.joinToString("\n\n")

    override fun search(query: String): ToolEvidence {
        val start = System.nanoTime()
        val vector = queryEmbedding(file("f6-embedder"), "task: search result | query: $query", 1, 2048)
        return fromVector(query, vector).also { it.trace.put("retrievalTotalMs", (System.nanoTime() - start) / 1e6) }
    }
    // Lint mistakes Half.toFloat(short) for Short.toFloat(); these are IEEE-754 bit patterns,
    // explicitly decoded with android.util.Half. Exact F6 context parity covers the conversion.
    @android.annotation.SuppressLint("HalfFloat")
    fun fromVector(query: String, vector: FloatArray): ToolEvidence {
        val control = searchIds(query, vector, .5, 5.0, false).getJSONArray("ids")
        val raw = (0 until minOf(2, control.length())).joinToString("\n\n") { index -> val id = control.getInt(index); "[${index + 1}] (${label(id)}):\n${chunks.getValue(id).getString("text")}" }
        val budget = (countTokens(raw) * .5).toInt()
        val search = searchIds(query, vector, .7, 2.0, true)
        val ids = search.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val candidateSources = JSONArray(ids.mapIndexed { rank, id ->
            JSONObject().put("rank", rank + 1).put("document_id", chunks.getValue(id).getString("doc"))
                .put("filename", label(id)).put("chunk_index", id).put("text", chunks.getValue(id).getString("text"))
        })
        data class Candidate(val score: Double, val id: Int, val unit: Int)
        val candidates = mutableListOf<Candidate>()
        val terms = lexical(query).map { it.take(6) }.toSet()
        for ((rank, id) in ids.withIndex()) {
            val count = units.getJSONObject(id.toString()).getJSONArray("units").length()
            val lexical = (0 until count).map { lexical(plain(id, it)).map { token -> token.take(6) }.toSet() }
            for (unit in 0 until count) {
                var score = 0.0; val offset = (bases.getValue(id) + unit) * 768
                for (i in vector.indices) {
                    val bits: Short = unitVectors.getShort((offset + i) * 2)
                    val value = android.util.Half.toFloat(bits)
                    score += value.toDouble() * vector[i]
                }
                val nearby = (maxOf(0, unit - 1)..minOf(count - 1, unit + 1)).flatMap { lexical[it] }.toSet()
                val lexicalScore = if (terms.isEmpty()) 0.0 else terms.intersect(nearby).size.toDouble() / terms.size
                candidates.add(Candidate(score.toFloat().toDouble() + .3 * lexicalScore + .05 / (rank + 1), id, unit))
            }
        }
        candidates.sortByDescending { it.score }
        val selected = linkedMapOf<Int, MutableList<Int>>()
        val picked = mutableListOf<Candidate>(); var used = 0
        for (candidate in candidates) {
            val id = candidate.id
            val header = if (id !in selected) countTokens("[9] (${label(id)}):\n${prefix(id)} — ") + 2 else 0
            val cost = countTokens(unitText(id, candidate.unit)) + 3 + header
            if (used + cost > budget) continue
            selected.getOrPut(id) { mutableListOf() }.add(candidate.unit); picked.add(candidate); used += cost
        }
        if (picked.isEmpty() && candidates.isNotEmpty()) { val c = candidates.first(); selected[c.id] = mutableListOf(c.unit); picked.add(c) }
        var text = render(selected, ids)
        while (countTokens(text) > budget && picked.size > 1) {
            val c = picked.removeAt(picked.lastIndex); selected.getValue(c.id).remove(c.unit)
            if (selected.getValue(c.id).isEmpty()) selected.remove(c.id)
            text = render(selected, ids)
        }
        val sources = ids.filter { it in selected }.mapIndexed { rank, id ->
            val passage = render(mapOf(id to selected.getValue(id)), listOf(id)).substringAfter('\n')
            JSONObject().put("rank", rank + 1).put("document_id", chunks.getValue(id).getString("doc"))
                .put("filename", label(id)).put("chunk_index", id).put("text", passage).put("selectedUnits", JSONArray(selected.getValue(id)))
        }
        return ToolEvidence(sources, search.put("candidateSources", candidateSources).put("configuredCandidateCount", ids.size).put("retrieval", "f6").put("context", text).put("budget", budget).put("contextTokens", countTokens(text)))
    }
    override fun close() { normDb.close(); manualDb.close() }
}
