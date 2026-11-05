package com.cemig.gpudiagnostic

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A trusted public bootstrap document locates the relay; session capabilities never go to redirects. */
class RelayClient(private val bootstrap: String = BOOTSTRAP_URL) {
    companion object {
        const val BOOTSTRAP_URL = "https://raw.githubusercontent.com/vinyteles/mobile-gpu-diagnostic/main/relay-endpoint-v2.json"
    }
    private var endpoint = ""
    private var identity = ""
    private var checkedAt = 0L
    fun invalidate() { checkedAt = 0 }

    fun connect(): String {
        if (endpoint.isNotBlank() && System.currentTimeMillis() - checkedAt < 60_000) return identity
        val config = request(bootstrap, "GET")
        val expected = config.getString("serviceInstanceId")
        check(expected.isNotBlank()) { "Missing relay identity." }
        // The server updates its HF index after tunnel restarts; GitHub pins the expected server identity.
        val discovery = config.optString("discoveryUrl").takeIf { it.isNotBlank() }?.let { address ->
            require(address == "https://huggingface.co/datasets/vinyteles/mobilebench-index-v2/resolve/main/endpoint.json")
            runCatching { request(address, "GET", redirects = 3) }.getOrNull()
                ?.takeIf { it.optString("serviceInstanceId") == expected && it.optString("url").startsWith("https://") }
        }
        val candidate = (discovery?.getString("url") ?: config.getString("endpoint")).trimEnd('/')
        val url = URL(candidate)
        check(url.userInfo == null && url.query == null && url.ref == null && url.path.isEmpty()) { "Invalid relay address." }
        val health = request("$candidate/v1/health", "GET")
        check(health.getString("serviceInstanceId") == expected) { "Upload server identity changed; local reports are safe." }
        endpoint = candidate; identity = expected; checkedAt = System.currentTimeMillis()
        return identity
    }

    fun register(session: JSONObject, secret: String): JSONObject {
        val server = connect()
        check(!session.has("serviceInstanceId") || session.getString("serviceInstanceId") == server) { "Saved session belongs to a different upload server." }
        val response = request("$endpoint/v1/sessions", "POST", body = JSONObject()
            .put("sessionSecret", secret).put("installationId", session.getString("installationId"))
            .put("batch", session.getInt("batch")).put("appVersion", BuildConfig.VERSION_NAME)
            .put("purpose", session.getString("purpose")))
        require(response.getString("sessionId").matches(Regex("s_[0-9a-f]{16,20}")))
        val repository = response.getString("repository")
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) && !repository.contains(".."))
        return response.put("serviceInstanceId", server)
    }

    fun upload(session: JSONObject, secret: String, archive: File, job: JSONObject): JSONObject {
        check(connect() == session.getString("serviceInstanceId"))
        val configuration = job.optString("configuration")
        val headers = mutableMapOf("X-Content-SHA256" to job.getString("sha256"), "X-App-Version" to BuildConfig.VERSION_NAME,
            "X-Checkpoint-Created-At-Ms" to job.getLong("createdAtMs").toString(),
            "X-Checkpoint-Kind" to if (configuration.isBlank()) "latest" else "configuration")
        if (configuration.isNotBlank()) headers["X-Configuration-Id"] = configuration
        val response = request("$endpoint/v1/sessions/${session.getString("sessionId")}/checkpoints", "POST", secret, file = archive, headers = headers)
        check(response.getString("sha256") == job.getString("sha256")) { "Upload receipt checksum differs." }
        return response
    }

    fun status(session: JSONObject, secret: String): JSONObject {
        check(connect() == session.getString("serviceInstanceId"))
        return request("$endpoint/v1/sessions/${session.getString("sessionId")}", "GET", secret)
    }

    private fun request(address: String, method: String, secret: String? = null, body: JSONObject? = null,
                        file: File? = null, headers: Map<String, String> = emptyMap(), redirects: Int = 0): JSONObject {
        val url = URL(address)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.host in listOf("127.0.0.1", "10.0.2.2")))
        require(secret == null || address.startsWith("$endpoint/"))
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false; connection.useCaches = false
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (secret != null) connection.setRequestProperty("Authorization", "Bearer $secret")
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        try {
            if (body != null || file != null) {
                val bytes = body?.toString()?.toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (file != null) "application/zip" else "application/json")
                connection.setFixedLengthStreamingMode(file?.length() ?: bytes!!.size.toLong())
                connection.outputStream.use { output -> if (file != null) file.inputStream().use { it.copyTo(output) } else output.write(bytes!!) }
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308) && redirects > 0 && method == "GET" && secret == null) {
                val target = URL(url, connection.getHeaderField("Location"))
                require(target.protocol == "https" && target.host == url.host && target.userInfo == null)
                connection.disconnect()
                return request(target.toString(), "GET", redirects = redirects - 1)
            }
            if (code !in 200..299) throw IOException("Upload service HTTP $code. Local reports retained; retrying.")
            val data = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "Upload service response too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(data.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
}
