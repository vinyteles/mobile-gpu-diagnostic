package com.cemig.gpudiagnostic

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

private data class GraphHit(val distance: Float, val node: Int)
private val hitOrder = compareBy<GraphHit> { it.distance }.thenBy { it.node }

/** Native counterpart of MobileRAG+'s exported HNSW graph search. */
internal class RetrievalGraph(database: SQLiteDatabase, cluster: Int) {
    private val vectors: FloatArray
    val labels: LongArray
    private val links: IntArray
    private val dimension: Int
    private val layers: Int
    private val width: Int
    private val entry: Int
    private val level: Int
    var computations = 0
    init {
        database.rawQuery("SELECT node_count,layer_count,link_width,entry_point,max_level,vectors,labels,links FROM graphs WHERE cluster_id=?", arrayOf(cluster.toString())).use {
            check(it.moveToFirst()) { "Missing HNSW graph $cluster" }
            layers = it.getInt(1); width = it.getInt(2); entry = it.getInt(3); level = it.getInt(4)
            vectors = floats(it.getBlob(5)); dimension = vectors.size / it.getInt(0).coerceAtLeast(1)
            val labelBuffer = ByteBuffer.wrap(it.getBlob(6)).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer()
            labels = LongArray(labelBuffer.remaining()).also { values -> labelBuffer.get(values) }
            val linkBuffer = ByteBuffer.wrap(it.getBlob(7)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            links = IntArray(linkBuffer.remaining()).also { values -> linkBuffer.get(values) }
        }
    }
    fun vector(node: Int) = vectors.copyOfRange(node * dimension, (node + 1) * dimension)
    private fun distance(query: FloatArray, node: Int): Float {
        check(query.size == dimension)
        computations++
        var dot = 0.0
        for (i in query.indices) dot += vectors[node * dimension + i].toDouble() * query[i]
        return 1f - dot.toFloat()
    }
    private fun neighbors(node: Int, layer: Int): List<Int> {
        val offset = (node * layers + layer) * width
        return (0 until width).map { links[offset + it] }.filter { it >= 0 }
    }
    fun search(query: FloatArray, k: Int, ef: Int): List<Pair<Long, Float>> {
        if (entry < 0) return emptyList()
        var current = entry
        for (layer in level downTo 1) {
            var score = distance(query, current)
            while (true) {
                val scored = neighbors(current, layer).map { GraphHit(distance(query, it), it) }
                val best = scored.minByOrNull { it.distance } ?: break
                if (best.distance >= score) break
                current = best.node; score = best.distance
            }
        }
        val candidates = PriorityQueue(hitOrder)
        val results = PriorityQueue(hitOrder)
        val seen = mutableSetOf(current)
        val first = distance(query, current)
        candidates.add(GraphHit(first, current)); results.add(GraphHit(-first, current))
        val capacity = maxOf(k, ef)
        while (candidates.isNotEmpty()) {
            val next = candidates.remove()
            if (results.size >= capacity && next.distance > -results.peek()!!.distance) break
            val unseen = neighbors(next.node, 0).filter { it !in seen }
            seen.addAll(unseen)
            for (node in unseen) {
                val d = distance(query, node)
                if (results.size < capacity || d < -results.peek()!!.distance) {
                    candidates.add(GraphHit(d, node)); results.add(GraphHit(-d, node))
                    if (results.size > capacity) results.remove()
                }
            }
        }
        return results.map { GraphHit(-it.distance, it.node) }.sortedWith(hitOrder).take(k)
            .map { labels[it.node] to (1f - it.distance) }
    }
}

/** EcoVector, neighboring chunks and int8 SCR from the same published MobileRAG+ assets. */
class MobileRagRetriever(private val context: Context) : LiveRetriever {
    private val index = openReadOnly(retrievalFile(context, "mr-eco-vector", "mobileragplus"))
    private val corpus = openReadOnly(retrievalFile(context, "mr-corpus", "mobileragplus"))
    private val windows = openReadOnly(retrievalFile(context, "mr-windows-int8", "mobileragplus"))
    private val meta = JSONObject()
    private val centroids = RetrievalGraph(index, -1)
    private val positions = linkedMapOf<Pair<String, Int>, Long>()
    private val vectors = mutableMapOf<Long, FloatArray>()
    private val spans = context.assets.open("mobilerag-sentence-spans.json").bufferedReader().use { JSONObject(it.readText()) }
    init {
        index.rawQuery("SELECT key,value FROM meta", null).use { cursor -> while (cursor.moveToNext()) meta.put(cursor.getString(0), org.json.JSONTokener(cursor.getString(1)).nextValue()) }
        check(meta.getString("format") == "1") { "Unsupported EcoVector index" }
        corpus.rawQuery("SELECT id,metadata_json FROM documents", null).use { cursor ->
            while (cursor.moveToNext()) {
                val data = JSONObject(cursor.getString(1))
                if (!data.isNull("chunk_index")) positions[data.optString("filename") to data.getInt("chunk_index")] = cursor.getLong(0)
            }
        }
    }

    override fun search(query: String): ToolEvidence {
        val started = System.nanoTime()
        val qwen = queryEmbedding(retrievalFile(context, "qwen-query", "mobileragplus"),
            "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery:" + query, 3, 512)
        val qwenMs = (System.nanoTime() - started) / 1e6
        val mark = System.nanoTime()
        val granite = queryEmbedding(retrievalFile(context, "granite-scr", "mobileragplus"), query, 2, 512)
        val graniteMs = (System.nanoTime() - mark) / 1e6
        return fromVectors(qwen, granite).also {
            it.trace.put("retrievalEmbeddingMs", qwenMs).put("scrEmbeddingMs", graniteMs)
        }
    }

    /** Public for vector-level parity tests against the existing MobileRAG+ implementation. */
    fun fromVectors(retrieval: FloatArray, scr: FloatArray): ToolEvidence {
        val start = System.nanoTime()
        val query = unitVector(retrieval)
        val clusters = centroids.search(query, minOf(meta.getInt("n_probe"), centroids.labels.size), meta.getInt("centroid_ef_search"))
        val hits = clusters.flatMap { (cluster, _) ->
            val graph = RetrievalGraph(index, cluster.toInt())
            graph.labels.forEachIndexed { node, label -> vectors[label] = graph.vector(node) }
            graph.search(query, 5, meta.getInt("cluster_ef_search"))
        }.sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first }).take(5)
        val chunks = hits.map { readChunk(it.first).put("retrieval_score", it.second) }
        val candidatesBeforeCompression = JSONArray(chunks.map { JSONObject(it.toString()) })
        val known = hits.map { it.first }.toMutableSet()
        val neighbors = mutableListOf<Long>()
        for (offset in 1..2) for (chunk in chunks) {
            if (chunk.isNull("chunk_index")) continue
            for (position in listOf(chunk.getInt("chunk_index") - offset, chunk.getInt("chunk_index") + offset)) {
                val label = positions[chunk.getString("filename") to position] ?: continue
                if (known.add(label)) neighbors.add(label)
            }
        }
        if (neighbors.any { it !in vectors }) {
            index.rawQuery("SELECT cluster_id FROM graphs WHERE cluster_id>=0 ORDER BY cluster_id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val graph = RetrievalGraph(index, cursor.getInt(0))
                    graph.labels.forEachIndexed { node, label -> if (label !in vectors) vectors[label] = graph.vector(node) }
                }
            }
        }
        val neighborsRanked = neighbors.map { label ->
            val vector = vectors.getValue(label)
            label to (vectorDot(vector, retrieval) / (vectorNorm(vector) * vectorNorm(retrieval)))
        }.sortedByDescending { it.second }
        val all = chunks + neighborsRanked.map { readChunk(it.first).put("retrieval_score", it.second) }
        val expandedCandidates = JSONArray(all.map { JSONObject(it.toString()) })
        val searchMs = (System.nanoTime() - start) / 1e6
        val reduced = all.map { chunk ->
            val label = chunk.getLong("label")
            val sentences = spans.getJSONArray(label.toString())
            var best = Float.NEGATIVE_INFINITY; var from = 0; var to = 0; var count = 0
            windows.rawQuery("SELECT sentence_start,sentence_end,scale,vector FROM windows WHERE label=? ORDER BY window", arrayOf(label.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    val scale = cursor.getFloat(2) / 127f
                    val vector = cursor.getBlob(3).map { it * scale }.toFloatArray()
                    val score = vectorDot(vector, scr)
                    if (score > best) { best = score; from = cursor.getInt(0); to = cursor.getInt(1) }
                    count++
                }
            }
            check(count > 0 && to <= sentences.length()) { "Missing or inconsistent SCR windows for $label" }
            from = maxOf(0, from - 1); to = minOf(sentences.length(), to + 1)
            val raw = chunk.getString("text")
            chunk.put("text", raw.substring(sentences.getJSONArray(from).getInt(0), sentences.getJSONArray(to - 1).getInt(1)))
                .put("scr_score", best).put("sentence_start", from).put("sentence_end", to).put("sentence_count", sentences.length())
        }.sortedByDescending { it.getDouble("scr_score") }
        reduced.forEachIndexed { index, source -> source.put("rank", index + 1) }
        return ToolEvidence(reduced, JSONObject().put("retrieval", "mobileragplus").put("searchMs", searchMs)
            .put("retrievalAndScrMs", (System.nanoTime() - start) / 1e6)
            .put("candidateSources", candidatesBeforeCompression).put("expandedCandidateSources", expandedCandidates).put("configuredTopK", 5)
            .put("probedClusters", JSONArray(clusters.map { it.first })).put("hitLabels", JSONArray(hits.map { it.first })))
    }

    private fun readChunk(label: Long): JSONObject {
        corpus.rawQuery("SELECT document_id,document,categoria,metadata_json FROM documents WHERE id=?", arrayOf(label.toString())).use {
            check(it.moveToFirst()) { "Missing corpus chunk $label" }
            val meta = JSONObject(it.getString(3))
            return JSONObject().put("label", label).put("document_id", it.getString(0)).put("text", it.getString(1))
                .put("category", it.getString(2)).put("filename", meta.optString("filename"))
                .put("chunk_index", meta.opt("chunk_index") ?: JSONObject.NULL)
        }
    }

    override fun close() { index.close(); corpus.close(); windows.close() }
}
