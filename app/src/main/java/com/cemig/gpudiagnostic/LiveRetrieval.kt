package com.cemig.gpudiagnostic

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Immutable inputs used by the on-device tool, downloaded and checked by the normal setup UI. */
fun retrievalArtifacts(context: Context, retrieval: String): List<ModelSpec> {
    val rows = context.assets.open("retrieval-artifacts.json").bufferedReader().use { JSONArray(it.readText()) }
    return (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
        if (retrieval == "f6") it.getString("id").startsWith("f6-") else !it.getString("id").startsWith("f6-")
    }.map { ModelSpec(it.getString("id"), it.getString("label"), it.getString("url"), it.getLong("bytes"),
        it.getString("sha256"), it.getString("extension")) }
}

fun requiredArtifacts(context: Context, config: BenchmarkConfig): List<ModelSpec> =
    listOf(config.model) + if (config.liveTools) retrievalArtifacts(context, config.retrieval) else emptyList()

fun retrievalFile(context: Context, id: String, retrieval: String): File {
    val artifact = retrievalArtifacts(context, retrieval).single { it.id == id }
    return File(context.filesDir, "models/${artifact.id}.${artifact.extension}").also { check(it.exists()) { "Missing ${artifact.label}" } }
}

data class ToolEvidence(val sources: List<JSONObject>, val trace: JSONObject = JSONObject())

interface LiveRetriever : AutoCloseable {
    fun search(query: String): ToolEvidence
}

fun floats(bytes: ByteArray): FloatArray {
    val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    return FloatArray(view.remaining()).also { view.get(it) }
}

fun vectorDot(a: FloatArray, b: FloatArray): Float {
    check(a.size == b.size) { "Embedding dimension mismatch: ${a.size} / ${b.size}" }
    var sum = 0.0
    for (index in a.indices) sum += a[index].toDouble() * b[index]
    return sum.toFloat()
}

fun vectorNorm(a: FloatArray): Float = sqrt(a.sumOf { it.toDouble() * it }).toFloat()
fun unitVector(a: FloatArray): FloatArray {
    val norm = vectorNorm(a)
    check(norm > 0 && norm.isFinite()) { "Invalid query embedding" }
    return FloatArray(a.size) { a[it] / norm }
}

fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

/** Models are loaded sequentially on CPU and freed after each embedding call. */
fun queryEmbedding(file: File, query: String, pooling: Int, contextTokens: Int): FloatArray =
    LlamaNative.embed(file.path.toByteArray(), query.toByteArray(), pooling, contextTokens)
