package com.cemig.gpudiagnostic

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DiagnosticTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun directory(name: String) = File(context.cacheDir, "tests/$name-${System.nanoTime()}").apply { mkdirs() }

    @Test fun liveReaderDoesNotDeleteAnInProgressAtomicWrite() {
        val file = File(directory("atomic"), "checkpoint.json")
        writeJson(file, JSONObject().put("value", "old"))
        val writer = android.util.AtomicFile(file)
        val stream = writer.startWrite()
        stream.write("{\"value\":\"new\"}".toByteArray())
        repeat(10) { assertEquals("old", readJson(file)!!.getString("value")) }
        assertTrue(File(file.path + ".new").exists())
        writer.finishWrite(stream)
        assertEquals("new", readJson(file)!!.getString("value"))
    }

    private fun worker(mode: String, timeout: Long = 15_000): JSONObject {
        val run = directory(mode)
        val checkpoint = File(run, "gemma-test.json")
        writeJson(checkpoint, JSONObject().put("id", "gemma-test").put("status", "pending"))
        val arguments = Bundle().apply { putString("caseFile", checkpoint.path); putString("smoke", mode) }
        return WorkerBridge(context).run(arguments, { false }, timeout)
    }

    @Test fun workerCompletesAcrossProcessBoundary() {
        val result = worker("success")
        assertEquals("completed", result.getString("status"))
        assertTrue(result.getBoolean("smokeTest"))
        assertNotEquals(android.os.Process.myPid(), result.getInt("workerPid"))
    }

    @Test fun workerDeathPreservesCheckpointAndNextWorkerCanRun() {
        val failed = worker("crash")
        assertEquals("worker_exited", failed.getString("status"))
        assertEquals("hardware_probe", failed.getString("failedPhase"))
        assertEquals("completed", worker("success").getString("status"))
    }

    @Test fun hungWorkerTimesOutAndDoesNotBlockNextCase() {
        assertEquals("timeout", worker("hang", 1200).getString("status"))
        assertEquals("completed", worker("success").getString("status"))
    }

    @Test fun workerErrorIsExportedWithStackTrace() {
        val result = worker("error")
        assertEquals("error", result.getString("status"))
        assertTrue(result.getString("stackTrace").contains("Injected worker error"))
        val run = directory("export")
        writeJson(File(run, "gemma-error.json"), result)
        File(run, "gemma-error.log").writeText("native failure evidence")
        ZipFile(ReportExporter(context).archive(run)).use { zip ->
            val report = zip.getInputStream(zip.getEntry("report.json")).bufferedReader().readText()
            assertTrue(report.contains("Injected worker error"))
            assertEquals("native failure evidence", zip.getInputStream(zip.getEntry("gemma-error.log")).bufferedReader().readText())
        }
    }

    @Test fun downloadResumesWithRangeAndChecksSha256() { checkDownload(resume = true, ignoreRange = false, invalidHash = false) }
    @Test fun downloadRestartsWhenServerIgnoresRange() { checkDownload(resume = true, ignoreRange = true, invalidHash = false) }
    @Test fun corruptedDownloadIsNeverPublished() { checkDownload(resume = false, ignoreRange = false, invalidHash = true) }

    /** A bounded local HTTP server exercises real range responses and checksum failures. */
    private fun checkDownload(resume: Boolean, ignoreRange: Boolean, invalidHash: Boolean) {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        val server = ServerSocket(0)
        val rangeSeen = AtomicBoolean(false)
        val thread = Thread {
            repeat(if (invalidHash) 3 else 1) {
                runCatching {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = mutableListOf<String>()
                        while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.add(line) }
                        val requested = headers.firstOrNull { it.startsWith("Range:", true) }
                        rangeSeen.set(requested != null)
                        val offset = if (requested != null && !ignoreRange) 300 else 0
                        val output = socket.getOutputStream()
                        val status = if (offset > 0) "206 Partial Content" else "200 OK"
                        val range = if (offset > 0) "Content-Range: bytes $offset-${payload.size - 1}/${payload.size}\r\n" else ""
                        output.write("HTTP/1.1 $status\r\nContent-Length: ${payload.size - offset}\r\n${range}Connection: close\r\n\r\n".toByteArray())
                        output.write(payload, offset, payload.size - offset); output.flush()
                    }
                }
            }
        }.apply { start() }
        try {
            val dir = directory("download")
            if (resume) File(dir, "test.part").writeBytes(payload.copyOf(300))
            val model = ModelSpec("test", "test", "http://127.0.0.1:${server.localPort}/model", payload.size.toLong(), if (invalidHash) "0".repeat(64) else digest)
            val result = runCatching { ModelDownloader(dir, { false }, {}).download(model) }
            if (invalidHash) {
                assertTrue(result.isFailure)
                assertFalse(File(dir, "test.litertlm").exists())
            } else {
                assertArrayEquals(payload, result.getOrThrow().readBytes())
                assertEquals(resume, rangeSeen.get())
            }
        } finally {
            server.close(); thread.join(3000)
        }
    }
}
