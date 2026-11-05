package com.cemig.gpudiagnostic

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HF HTTPS API + Git LFS basic uploads. The account token is sent only to the fixed Hub origin. */
class HuggingFaceClient(private val origin: String = "https://huggingface.co") {
    init { require(origin == "https://huggingface.co" || (BuildConfig.DEBUG && origin.startsWith("http://127.0.0.1:"))) }

    fun validateRepository(repository: String, token: String): Boolean {
        require(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/[A-Za-z0-9][A-Za-z0-9_.-]{0,95}").matches(repository)) { "Enter owner/repository." }
        require(!repository.contains("..")) { "Invalid repository name." }
        require(token.startsWith("hf_") && token.none { it.isWhitespace() }) { "Enter a Hugging Face access token." }
        val info = JSONObject(request("$origin/api/datasets/$repository", "GET", token = token).toString(Charsets.UTF_8))
        return info.optBoolean("private")
    }

    fun connect(repository: String, token: String, installationId: String) {
        validateRepository(repository, token)
        val details = JSONObject().put("installationId", installationId).put("connectedAtMs", System.currentTimeMillis())
            .put("appVersion", BuildConfig.VERSION_NAME)
        commit(repository, token, "Connect mobile benchmark", listOf(textFile("connections/$installationId.json", details)))
    }

    /** Publish the ZIP and its progress summary atomically in one Hub commit. */
    fun publish(repository: String, token: String, archive: File, progress: JSONObject, configuration: String?): String {
        validateRepository(repository, token)
        val hash = uploadBlob(repository, token, archive)
        val run = progress.getString("cloudRunId")
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val operations = mutableListOf(
            lfsFile("runs/$run/latest.zip", hash, archive.length()),
            textFile("runs/$run/progress.json", progress),
            textFile("latest.json", progress),
        )
        if (configuration != null) {
            require(BENCHMARK_CONFIGS.any { it.id == configuration })
            operations.add(lfsFile("runs/$run/configurations/$configuration.zip", hash, archive.length()))
        }
        return commit(repository, token, "Benchmark $run: ${configuration ?: "checkpoint"}", operations)
    }

    private fun textFile(path: String, value: JSONObject) = JSONObject().put("key", "file").put("value", JSONObject()
        .put("path", path).put("encoding", "base64")
        .put("content", Base64.encodeToString((value.toString(2) + "\n").toByteArray(), Base64.NO_WRAP)))

    private fun lfsFile(path: String, hash: String, bytes: Long) = JSONObject().put("key", "lfsFile").put("value", JSONObject()
        .put("path", path).put("algo", "sha256").put("oid", hash).put("size", bytes))

    private fun commit(repository: String, token: String, summary: String, operations: List<JSONObject>): String {
        val header = JSONObject().put("key", "header").put("value", JSONObject().put("summary", summary))
        val payload = (listOf(header) + operations).joinToString("\n", postfix = "\n") { it.toString() }.toByteArray()
        val response = JSONObject(request("$origin/api/datasets/$repository/commit/main", "POST", token = token,
            body = payload, contentType = "application/x-ndjson").toString(Charsets.UTF_8))
        return response.optString("commitOid").ifBlank { response.optString("commitUrl") }
    }

    private fun uploadBlob(repository: String, token: String, archive: File): String {
        val hash = sha256(archive)
        val objectInfo = JSONObject().put("oid", hash).put("size", archive.length())
        val body = JSONObject().put("operation", "upload").put("transfers", JSONArray().put("basic"))
            .put("objects", JSONArray().put(objectInfo)).put("hash_algo", "sha256").put("ref", JSONObject().put("name", "main"))
        val batch = JSONObject(request("$origin/datasets/$repository.git/info/lfs/objects/batch", "POST", token = token,
            body = body.toString().toByteArray(), contentType = "application/vnd.git-lfs+json").toString(Charsets.UTF_8))
        val item = batch.getJSONArray("objects").getJSONObject(0)
        check(!item.has("error")) { "Hugging Face refused the report blob." }
        check(item.getString("oid") == hash && item.getLong("size") == archive.length()) { "Unexpected upload object." }
        val actions = item.optJSONObject("actions") ?: return hash // Already present on the Hub.
        actions.optJSONObject("upload")?.let { upload ->
            val headers = upload.optJSONObject("header") ?: JSONObject()
            check(!headers.has("chunk_size")) { "This report requires multipart upload; keep the local ZIP and retry with a smaller report." }
            request(upload.getString("href"), "PUT", file = archive, headers = headers, contentType = "application/octet-stream")
        }
        actions.optJSONObject("verify")?.let { verify ->
            val address = verify.getString("href")
            request(address, "POST", token = if (address.startsWith("$origin/")) token else null,
                headers = verify.optJSONObject("header") ?: JSONObject(), body = objectInfo.toString().toByteArray())
        }
        return hash
    }

    private fun request(address: String, method: String, token: String? = null, body: ByteArray? = null,
                        file: File? = null, headers: JSONObject = JSONObject(), contentType: String = "application/json"): ByteArray {
        val url = URL(address)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.host == "127.0.0.1")) { "HTTPS is required." }
        require(token == null || address.startsWith("$origin/")) { "Refusing to forward the Hub token." }
        val connection = url.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        headers.keys().forEach { connection.setRequestProperty(it, headers.getString(it)) }
        try {
            if (body != null || file != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(file?.length() ?: body!!.size.toLong())
                connection.outputStream.use { output ->
                    if (file != null) file.inputStream().use { it.copyTo(output) } else output.write(body!!)
                }
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val hint = when (status) {
                    401, 403 -> "Check the repository-scoped token and its write permission."
                    404 -> "Check the dataset repository and token access."
                    429 -> "Rate limited; pending reports will retry."
                    else -> "Pending reports will retry; local reports are retained."
                }
                // Never echo remote bodies, signed URLs, headers, or credentials into reports/logs.
                throw IOException("Hugging Face HTTP $status. $hint")
            }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 2 * 1024 * 1024) { "Unexpectedly large API response." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
