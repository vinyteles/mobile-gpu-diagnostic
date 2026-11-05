package com.cemig.gpudiagnostic

import android.app.Application

/** Owns the controller across activity recreation; the worker process never creates one. */
class DiagnosticApp : Application() {
    val cloudUploader by lazy { CloudUploader(this) }
    val coordinator by lazy { Coordinator(this) }
}
