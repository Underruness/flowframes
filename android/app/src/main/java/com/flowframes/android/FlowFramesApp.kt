package com.flowframes.android

import android.app.Application
import com.flowframes.android.job.AppGraph
import com.flowframes.android.job.SettingsStore

class FlowFramesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(SettingsStore(this))
        // Off the main thread: it loads the native library.
        Thread({
            com.flowframes.android.core.NcnnRifeEngine.setShaderCacheDir(java.io.File(filesDir, "gpu-shader-cache"))
        }, "shader-cache-init").start()
    }
}
