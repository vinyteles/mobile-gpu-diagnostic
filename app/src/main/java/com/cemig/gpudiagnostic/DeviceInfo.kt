package com.cemig.gpudiagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun memorySnapshot(context: Context): JSONObject {
    val info = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    val status = runCatching { File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") } }.getOrDefault(emptyList())
    return JSONObject().put("systemAvailableBytes", info.availMem).put("systemTotalBytes", info.totalMem)
        .put("systemLowMemory", info.lowMemory).put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
        .put("processMemory", JSONArray(status)).put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
}

fun deviceInfo(context: Context): JSONObject = JSONObject()
    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
    .put("socManufacturer", Build.SOC_MANUFACTURER).put("socModel", Build.SOC_MODEL)
    .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
    .put("securityPatch", Build.VERSION.SECURITY_PATCH).put("fingerprint", Build.FINGERPRINT)
    .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("hardware", Build.HARDWARE)
    .put("runtimes", JSONObject().put("litertlm", BuildConfig.LITERT_VERSION).put("llamaCppRevision", BuildConfig.LLAMA_REVISION))
    .put("applicationId", context.packageName)
    .put("appVersion", BuildConfig.VERSION_NAME).put("debugBuild", BuildConfig.DEBUG)
    .put("memory", memorySnapshot(context))
