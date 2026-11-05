package com.cemig.gpudiagnostic

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Starts a remote worker, captures its logs, and converts process death or timeout into a result. */
class WorkerBridge(private val context: Context) {
    fun run(arguments: Bundle, cancelled: () -> Boolean, timeoutMs: Long = 360_000,
            periodicReport: (() -> Unit)? = null, questionCompleted: (() -> Unit)? = null): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "WorkerBridge must run off the UI thread" }
        val caseFile = File(arguments.getString("caseFile")!!)
        val main = Handler(Looper.getMainLooper())
        val ready = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val pid = AtomicInteger(0)
        val remote = AtomicReference<Messenger?>(null)
        val died = AtomicBoolean(false)
        val bound = AtomicBoolean(false)
        val ipcError = AtomicReference<String?>(null)
        val completedQuestion = AtomicInteger(0)
        val callback = Messenger(Handler(Looper.getMainLooper()) { response ->
            when (response.what) {
                READY -> { pid.set(response.arg1); ready.countDown() }
                DONE -> finished.countDown()
                QUESTION_COMPLETED -> completedQuestion.set(response.arg1)
            }
            true
        })
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(Messenger(binder))
                runCatching { remote.get()!!.send(Message.obtain(null, HELLO).apply { replyTo = callback }) }
                    .onFailure { ipcError.set(it.toString()); ready.countDown(); finished.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (bound.getAndSet(false)) runCatching { context.unbindService(this) }
                died.set(true); ready.countDown(); finished.countDown()
            }
            override fun onNullBinding(name: ComponentName) {
                ipcError.set("Worker returned a null binder"); ready.countDown(); finished.countDown()
            }
            override fun onBindingDied(name: ComponentName) {
                died.set(true); ready.countDown(); finished.countDown()
            }
        }
        val started = System.currentTimeMillis()
        var capture: java.lang.Process? = null
        var outcome = "error"
        try {
            // 1. Bind to a fresh process and establish its PID before inference begins.
            main.post {
                runCatching {
                    bound.set(context.bindService(Intent(context, InferenceService::class.java), connection, Context.BIND_AUTO_CREATE))
                    if (!bound.get()) { ipcError.set("bindService returned false"); ready.countDown() }
                }.onFailure { ipcError.set(it.toString()); ready.countDown() }
            }
            check(ready.await(20, TimeUnit.SECONDS) && pid.get() > 0 && !died.get()) { ipcError.get() ?: "Worker did not connect within 20 seconds" }
            val log = File(caseFile.parentFile, caseFile.nameWithoutExtension + ".log")
            capture = ProcessBuilder("logcat", "--pid=${pid.get()}", "-v", "threadtime", "-T", "1")
                .redirectErrorStream(true).redirectOutput(java.lang.ProcessBuilder.Redirect.appendTo(log)).start()
            arguments.putBoolean("checkpointPerQuestion", questionCompleted != null)
            remote.get()!!.send(Message.obtain(null, RUN).apply { data = arguments; replyTo = callback })

            // 2. Wait for a result, process death, user cancellation, or the hard deadline.
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var progressTime = SystemClock.elapsedRealtime()
            var checkpointTime = caseFile.lastModified()
            var lastReport = SystemClock.elapsedRealtime()
            while (true) {
                val question = completedQuestion.getAndSet(0)
                if (question > 0) {
                    try { questionCompleted?.invoke() }
                    finally {
                        runCatching { remote.get()?.send(Message.obtain(null, CHECKPOINT_QUEUED).apply { arg1 = question }) }
                    }
                    lastReport = SystemClock.elapsedRealtime()
                }
                if (caseFile.lastModified() != checkpointTime) {
                    checkpointTime = caseFile.lastModified()
                    progressTime = SystemClock.elapsedRealtime()
                }
                if (periodicReport != null && SystemClock.elapsedRealtime() - lastReport >= 60_000) {
                    periodicReport()
                    lastReport = SystemClock.elapsedRealtime()
                }
                if (cancelled()) { outcome = "cancelled"; break }
                if (finished.await(200, TimeUnit.MILLISECONDS)) {
                    outcome = if (died.get()) "worker_exited" else "completed"
                    break
                }
                if (SystemClock.elapsedRealtime() > deadline ||
                    (arguments.getBoolean("replay") && SystemClock.elapsedRealtime() - progressTime > 360_000)) {
                    outcome = "timeout"; break
                }
            }
        } catch (error: Throwable) {
            ipcError.set(error.toString())
        } finally {
            // 3. Unbind before killing the worker so Android does not restart it automatically.
            val released = CountDownLatch(1)
            main.post {
                if (bound.get()) runCatching { context.unbindService(connection) }
                if (pid.get() > 0 && pid.get() != Process.myPid()) runCatching { Process.killProcess(pid.get()) }
                released.countDown()
            }
            released.await(5, TimeUnit.SECONDS)
            capture?.destroy()
            capture?.waitFor(2, TimeUnit.SECONDS)
        }

        // 4. Preserve the worker's checkpoint and append controller-side failure evidence.
        val result = readJson(caseFile) ?: JSONObject()
        if (result.optString("status") !in listOf("completed", "error")) {
            result.put("status", outcome).put("failedPhase", result.optString("phase"))
            if (ipcError.get() != null) result.put("controllerError", ipcError.get())
        }
        result.optJSONArray("answers")?.let { answers ->
            for (index in 0 until answers.length()) {
                val answer = answers.getJSONObject(index)
                if (answer.optString("status") == "running") answer.put("status", outcome)
            }
        }
        result.put("controllerOutcome", outcome).put("finishedAtMs", System.currentTimeMillis())
            .put("elapsedMs", System.currentTimeMillis() - started).put("workerPid", pid.get())
        val nativeLog = File(caseFile.parentFile, caseFile.nameWithoutExtension + ".log")
        if (nativeLog.exists()) {
            val lines = nativeLog.readLines()
            result.put("nativeGpuMessages", JSONArray(lines.filter {
                it.contains("delegate_webgpu") || it.contains("GPU delegate", ignoreCase = true) ||
                    it.contains("GpuEnvironment") || it.contains("OpenCL", ignoreCase = true) ||
                    it.contains("Vulkan", ignoreCase = true) || it.contains("offload", ignoreCase = true)
            }.takeLast(20)))
            result.put("nativeFallbackMessages", JSONArray(lines.filter {
                it.contains("fallback", ignoreCase = true) || it.contains("falling back", ignoreCase = true) ||
                    it.contains("fell back", ignoreCase = true)
            }.takeLast(20)))
        }
        if (outcome == "worker_exited") {
            Thread.sleep(500)
            result.put("processExitHistory", exitHistory(pid.get(), started, caseFile))
        }
        writeJson(caseFile, result)
        return result
    }

    private fun exitHistory(pid: Int, started: Long, caseFile: File): JSONArray {
        val records = JSONArray()
        runCatching {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, pid, 5)
                .filter { it.timestamp >= started - 1000 }
                .forEach { info ->
                    val entry = JSONObject().put("reason", info.reason).put("status", info.status)
                        .put("description", info.description).put("timestampMs", info.timestamp)
                        .put("pssKiB", info.pss).put("rssKiB", info.rss).put("process", info.processName)
                    runCatching {
                        info.traceInputStream?.use { input ->
                            val trace = File(caseFile.parentFile, caseFile.nameWithoutExtension + "-$pid.trace")
                            trace.outputStream().use { output -> input.copyTo(output) }
                            entry.put("traceFile", trace.name)
                        }
                    }.onFailure { entry.put("traceError", it.toString()) }
                    records.put(entry)
                }
        }.onFailure { records.put(JSONObject().put("error", it.toString())) }
        return records
    }
}
