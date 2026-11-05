package com.cemig.gpudiagnostic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HuggingFaceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dummyToken = "hf_synthetic_upload_test_only"

    private fun report(): Pair<File, File> {
        val run = File(context.cacheDir, "cloudtest-${System.nanoTime()}").apply { mkdirs() }
        val fixture = File(run, "rag-replay.json")
        context.assets.open("rag-replay.json").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        writeJson(File(run, "metadata.json"), JSONObject().put("runId", run.name).put("protocolId", PROTOCOL_ID)
            .put("fixtureSha256", sha256(fixture)).put("smokeTest", true).put("device", deviceInfo(context)))
        writeJson(File(run, "queue-state.json"), JSONObject().put("status", "completed").put("stage", "benchmark"))
        val config = BENCHMARK_CONFIGS.first()
        val result = JSONObject().put("id", config.id).put("modelSha256", config.model.sha256)
        val file = File(run, "${config.id}.json")
        runReplay(context, null, result, { writeJson(file, result) }, smokeMode = "replay_success")
        result.put("status", "completed"); writeJson(file, result)
        return run to ReportExporter(context).archive(run)
    }

    @Test fun credentialIsEncryptedAndCanBeForgotten() {
        val settings = CloudSettings(context)
        try {
            settings.save("test/public-reports", dummyToken)
            assertEquals(dummyToken, settings.token())
            val raw = context.getSharedPreferences("hugging-face-uploads", Context.MODE_PRIVATE).getString("credential", "")!!
            assertFalse(raw.contains(dummyToken))
        } finally { settings.disable() }
        assertNull(settings.token())
    }

    @Test fun failedUploadSurvivesAndRetryPublishesZipWithoutForwardingAccountTokenToBlob() {
        val (run, archive) = report()
        val settings = CloudSettings(context)
        settings.save("test/public-reports", dummyToken)
        val outbox = File(context.filesDir, "cloud-outbox")
        try {
            val failed = fakeHub(fail = true) { client ->
                val uploader = CloudUploader(context, client, automatic = false)
                uploader.enqueue(run, archive, BENCHMARK_CONFIGS.first().id)
                uploader.drain()
                assertTrue(uploader.status, uploader.status.contains("pending"))
                assertTrue(outbox.listFiles().orEmpty().any { it.name.startsWith(run.name) && it.extension == "json" })
            }
            assertEquals(2, failed)
            val succeeded = fakeHub(fail = false) { client ->
                CloudUploader(context, client, automatic = false).drain()
                assertFalse(outbox.listFiles().orEmpty().any { it.name.startsWith(run.name) && it.extension == "json" })
            }
            assertEquals(5, succeeded)
        } finally { settings.disable() }
    }

    /** The supplied token file stays in app-private storage and is deleted even on failure. */
    @Test fun optionalRealHubUpload() {
        val options = InstrumentationRegistry.getArguments()
        val supplied = options.getString("hfTokenFile")
        assumeTrue("Supply an app-private credential file for the real Hub integration check", supplied != null)
        val tokenFile = File(supplied!!)
        val uploader = CloudUploader(context, automatic = false)
        try {
            uploader.configure(options.getString("hfRepository") ?: "vinyteles/mobilebench-validation-20261105", tokenFile.readText().trim())
            val (run, archive) = report()
            uploader.enqueue(run, archive, BENCHMARK_CONFIGS.first().id)
            uploader.drain()
            assertTrue(uploader.status, uploader.status.startsWith("Hugging Face synced"))
        } finally { tokenFile.delete(); uploader.disable() }
    }

    private fun fakeHub(fail: Boolean, action: (HuggingFaceClient) -> Unit): Int {
        val server = ServerSocket(0)
        val exception = AtomicReference<Throwable?>(null)
        var requests = 0
        val thread = Thread {
            try {
                repeat(if (fail) 2 else 5) {
                    server.accept().use { socket ->
                        socket.soTimeout = 15_000
                        val input = socket.getInputStream().buffered()
                        fun line(): String {
                            val bytes = java.io.ByteArrayOutputStream()
                            while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) bytes.write(byte) }
                            return bytes.toString("UTF-8")
                        }
                        val request = line()
                        val headers = mutableMapOf<String, String>()
                        while (true) { val header = line(); if (header.isEmpty()) break; headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < body.size) { val count = input.read(body, offset, body.size - offset); check(count > 0); offset += count }
                        requests++
                        val path = request.split(' ')[1]
                        val payload = when {
                            path == "/blob" -> {
                                assertNull(headers["authorization"])
                                assertEquals('P'.code, body[0].toInt())
                                "{}"
                            }
                            else -> {
                                assertEquals("Bearer $dummyToken", headers["authorization"])
                                when {
                                    path.contains("/commit/") -> {
                                        val lines = body.toString(Charsets.UTF_8).trim().lines().map { JSONObject(it) }
                                        assertTrue(lines.any { it.optString("key") == "lfsFile" && it.getJSONObject("value").getString("path").contains("/configurations/") })
                                        "{\"commitOid\":\"synthetic-commit\"}"
                                    }
                                    path.endsWith("/batch") -> {
                                        val item = JSONObject(body.toString(Charsets.UTF_8)).getJSONArray("objects").getJSONObject(0)
                                        item.put("actions", JSONObject().put("upload", JSONObject().put("href", "http://127.0.0.1:${server.localPort}/blob"))
                                            .put("verify", JSONObject().put("href", "http://127.0.0.1:${server.localPort}/verify")))
                                        JSONObject().put("objects", org.json.JSONArray().put(item)).toString()
                                    }
                                    path == "/verify" -> "{}"
                                    else -> "{\"private\":false}"
                                }
                            }
                        }.toByteArray()
                        val status = if (fail && requests == 2) "503 Unavailable" else "200 OK"
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(payload)
                        }
                    }
                }
            } catch (error: Throwable) { exception.set(error) }
        }.apply { start() }
        try { action(HuggingFaceClient("http://127.0.0.1:${server.localPort}")) }
        finally { server.close(); thread.join(20_000) }
        exception.get()?.let { throw it }
        return requests
    }
}
