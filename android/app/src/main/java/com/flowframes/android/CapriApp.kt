package com.flowframes.android

import android.app.Application
import com.flowframes.android.core.CrashLog
import com.flowframes.android.core.NcnnRifeEngine
import com.flowframes.android.job.AppGraph
import com.flowframes.android.job.EngineWarmup
import com.flowframes.android.job.SettingsStore

class CapriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First: every later log line and any crash end up in files/logs.
        CrashLog.init(this)
        AppGraph.init(SettingsStore(this))
        CrashLog.previousSessionReport?.let { report ->
            AppGraph.jobs.addLog("⚠ The previous session did not end normally:")
            AppGraph.jobs.addLines(report)
            AppGraph.jobs.addLog("— end of the previous session report —")
        }
        // Off the main thread: it loads the native library.
        Thread({
            NcnnRifeEngine.setShaderCacheDir(java.io.File(filesDir, "gpu-shader-cache"))
            CrashLog.nativeReportFile()?.let { NcnnRifeEngine.installCrashHandler(it) }
            if (CrashLog.gpuBuildCrashed) NcnnRifeEngine.setPipelineDiskCache(false)
            // Build the GPU engine now, in the background, and in this thread — after the shader
            // cache directory is set. Two minutes of shader compilation are paid here instead of at
            // the first interpolation, which then starts immediately.
            EngineWarmup.prepare(this, AppGraph.settings.current) { line -> AppGraph.jobs.addLog(line) }
        }, "shader-cache-init").start()
    }
}
