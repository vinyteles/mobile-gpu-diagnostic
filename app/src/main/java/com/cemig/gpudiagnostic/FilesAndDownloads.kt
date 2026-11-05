package com.cemig.gpudiagnostic

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Persist a complete JSON checkpoint, preserving the previous copy if a write is interrupted. */
fun writeJson(file: File, value: JSONObject) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(value.toString(2).toByteArray())
        atomic.finishWrite(stream)
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}

fun readJson(file: File): JSONObject? = runCatching {
    // AtomicFile.openRead() deletes a concurrent writer's .new file. Readers only open the committed inode.
    JSONObject(file.inputStream().bufferedReader().use { it.readText() })
}.getOrNull()

fun sha256(file: File, cancelled: () -> Boolean = { false }, progress: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        var read = 0L
        var lastUpdate = 0L
        while (true) {
            if (cancelled()) throw CancellationException("Stopped by user")
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            read += count
            if (System.currentTimeMillis() - lastUpdate >= 500) {
                progress(read); lastUpdate = System.currentTimeMillis()
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

data class DownloadProgress(val phase: String, val bytes: Long, val totalBytes: Long)

/** Resume one pinned model, verify every byte, then atomically publish the usable file. */
class ModelDownloader(
    private val directory: File,
    private val cancelled: () -> Boolean,
    private val progress: (String) -> Unit,
    private val details: (DownloadProgress) -> Unit = {},
) {
    fun download(model: ModelSpec): File {
        // 1. Reuse only a complete, checksum-verified artifact.
        directory.mkdirs()
        val target = File(directory, "${model.id}.${model.extension}")
        val partial = File(directory, "${model.id}.part")
        if (target.exists()) {
            progress("Checking ${model.label} checksum…")
            details(DownloadProgress("verifying", 0, model.bytes))
            if (target.length() == model.bytes && sha256(target, cancelled) { details(DownloadProgress("verifying", it, model.bytes)) } == model.sha256) {
                details(DownloadProgress("ready", model.bytes, model.bytes))
                return target
            }
            check(target.delete()) { "Cannot replace invalid model file" }
        }
        if (partial.length() > model.bytes) check(partial.delete())
        val missing = model.bytes - partial.length()
        check(directory.usableSpace > missing + 512L * 1024 * 1024) { "Not enough storage for ${model.label}. Free more space, then tap Resume." }

        // 2. Resume using HTTP Range; retry network interruptions with the checkpoint intact.
        var lastError: Exception? = null
        for (attempt in 1..3) {
            if (cancelled()) throw CancellationException("Stopped by user")
            try {
                if (partial.length() < model.bytes) transfer(model, partial)
                check(partial.length() == model.bytes) { "Unexpected file size: ${partial.length()} / ${model.bytes}" }
                progress("Verifying ${model.label} SHA-256…")
                details(DownloadProgress("verifying", 0, model.bytes))
                if (sha256(partial, cancelled) { details(DownloadProgress("verifying", it, model.bytes)) } != model.sha256) {
                    partial.delete()
                    throw IOException("SHA-256 mismatch for ${model.label}; discarded the invalid download")
                }
                check(partial.renameTo(target)) { "Could not finalize downloaded model" }
                details(DownloadProgress("ready", model.bytes, model.bytes))
                return target
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                progress("Download attempt $attempt failed: ${error.message}. Resuming…")
            }
        }
        throw IOException("Download failed after 3 attempts: ${lastError?.message}", lastError)
    }

    private fun transfer(model: ModelSpec, partial: File) {
        val offset = partial.length()
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        try {
            val status = connection.responseCode
            check(status == 200 || status == 206) { "HTTP $status downloading ${model.label}" }
            val append = status == 206
            if (append) {
                val range = connection.getHeaderField("Content-Range") ?: ""
                check(range.startsWith("bytes $offset-") && range.endsWith("/${model.bytes}")) { "Unexpected Content-Range: $range" }
            }
            var received = if (append) offset else 0L
            details(DownloadProgress("downloading", received, model.bytes))
            var lastProgress = 0L
            connection.inputStream.buffered().use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        if (cancelled()) throw CancellationException("Stopped by user")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        check(received <= model.bytes) { "Server sent more bytes than the pinned model size" }
                        output.write(buffer, 0, count)
                        if (System.currentTimeMillis() - lastProgress > 500) {
                            progress("Downloading ${model.label}: ${received * 100 / model.bytes}% (${received / 1_000_000} / ${model.bytes / 1_000_000} MB)")
                            details(DownloadProgress("downloading", received, model.bytes))
                            lastProgress = System.currentTimeMillis()
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
