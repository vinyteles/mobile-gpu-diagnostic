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
class RelayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun report(): Pair<File, File> {
        val run = File(context.cacheDir, "relaytest-${System.nanoTime()}").apply { mkdirs() }
        val fixture = File(run, "rag-replay.json")
        context.assets.open("rag-replay.json").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        writeJson(File(run, "metadata.json"), JSONObject().put("runId", run.name).put("protocolId", PROTOCOL_ID)
            .put("fixtureSha256", sha256(fixture)).put("smokeTest", true).put("device", deviceInfo(context)))
        writeJson(File(run, "queue-state.json"), JSONObject().put("status", "completed").put("stage", "benchmark"))
        val config = BENCHMARK_CONFIGS.first()
        val record = JSONObject().put("id", config.id).put("modelSha256", config.model.sha256)
        val file = File(run, "${config.id}.json")
        runReplay(context, null, record, { writeJson(file, record) }, smokeMode = "replay_success")
        record.put("status", "completed"); writeJson(file, record)
        return run to ReportExporter(context).archive(run)
    }

    @Test fun sessionSecretsAreStableEncryptedAndSeparateAcrossBatches() {
        val settings = RelaySettings(context)
        val run = "credential-test-${System.nanoTime()}"
        val first = settings.sessionFor(run, 1, true)
        val secret = settings.secret(first)
        assertTrue(secret.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertEquals(secret, RelaySettings(context).secret(settings.sessionFor(run, 1, true)))
        assertFalse(File(context.filesDir, "relay-sessions/${first.getString("scope")}.json").readText().contains(secret))
        assertNotEquals(secret, settings.secret(settings.sessionFor(run, 2, true)))
        val configurationSecret = settings.secret(settings.sessionFor(run, 101, true))
        assertNotEquals(secret, configurationSecret)
        assertNotEquals(configurationSecret, settings.secret(settings.sessionFor(run, 102, true)))
        settings.newSession()
        assertNotEquals(secret, settings.secret(settings.sessionFor(run, 1, true)))
        assertEquals(secret, settings.secret(settings.session(first.getString("scope"))))
    }

    @Test fun retryKeepsLocalCheckpointAndDoesNotCallAcceptedReportSynced() {
        val (run, archive) = report()
        val outbox = File(context.filesDir, "relay-outbox")
        val server = ServerSocket(0)
        val failure = AtomicReference<Throwable?>(null)
        var registrationCount = 0
        var uploadCount = 0
        var statusCount = 0
        var secret = ""
        val thread = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
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
                    val path = request.split(' ')[1]
                    var code = 200
                    val response = when (path) {
                        "/bootstrap.json" -> JSONObject().put("endpoint", "http://127.0.0.1:${server.localPort}").put("serviceInstanceId", "test-relay")
                        "/v1/health" -> JSONObject().put("serviceInstanceId", "test-relay")
                        "/v1/sessions" -> {
                            registrationCount++
                            secret = JSONObject(body.toString(Charsets.UTF_8)).getString("sessionSecret")
                            JSONObject().put("sessionId", "s_0123456789abcdef").put("repository", "test/public-reports")
                        }
                        else -> {
                            assertEquals("Bearer $secret", headers["authorization"])
                            if (path.endsWith("/checkpoints")) {
                                uploadCount++
                                assertEquals('P'.code, body[0].toInt())
                                if (uploadCount == 1) code = 503
                                JSONObject().put("state", "accepted").put("sha256", headers.getValue("x-content-sha256"))
                            } else {
                                statusCount++
                                JSONObject().put("sync", JSONObject().put("state", if (statusCount == 1) "pending" else "synced").put("pendingUploads", 1))
                                    .put("latestPublished", JSONObject().put("completedAnswers", 120))
                            }
                        }
                    }.toString().toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray() + response)
                }
            } catch (error: Throwable) { if (!server.isClosed) failure.set(error) }
        }.apply { start() }
        try {
            val bootstrap = "http://127.0.0.1:${server.localPort}/bootstrap.json"
            val first = RelayUploader(context, RelayClient(bootstrap), automatic = false, allowSynthetic = true)
            first.enqueue(run, archive, BENCHMARK_CONFIGS.first().id)
            first.drain()
            assertTrue(first.status, first.status.startsWith("Upload pending"))
            assertTrue(outbox.listFiles().orEmpty().any { it.extension == "json" })
            val retry = RelayUploader(context, RelayClient(bootstrap), automatic = false, allowSynthetic = true)
            retry.drain()
            assertFalse(outbox.listFiles().orEmpty().any { it.extension == "json" })
            assertTrue(retry.status, retry.status.startsWith("Server has your report"))
            retry.drain()
            assertTrue(retry.status, retry.status.startsWith("Hugging Face synced"))
            assertEquals(1, registrationCount)
            assertEquals(2, uploadCount)
        } finally { server.close(); thread.join(20_000) }
        failure.get()?.let { throw it }
    }

    @Test fun optionalRealRelayUpload() {
        val options = InstrumentationRegistry.getArguments()
        assumeTrue("Supply -e liveRelay true for the real network check", options.getString("liveRelay") == "true")
        val uploader = RelayUploader(context, RelayClient(options.getString("relayBootstrap") ?: RelayClient.BOOTSTRAP_URL), automatic = false, allowSynthetic = true)
        val (run, archive) = report()
        uploader.enqueue(run, archive, BENCHMARK_CONFIGS.first().id)
        val deadline = System.currentTimeMillis() + 180_000
        do {
            uploader.drain()
            if (uploader.status.startsWith("Hugging Face synced")) break
            Thread.sleep(2000)
        } while (System.currentTimeMillis() < deadline)
        assertTrue(uploader.status, uploader.status.startsWith("Hugging Face synced"))
        assertTrue(uploader.repositoryUrl.startsWith("https://huggingface.co/datasets/vinyteles/mobilebench-relay-test-"))
    }
}
