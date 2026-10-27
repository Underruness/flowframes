package com.flowframes.android

import android.app.Application
import com.flowframes.android.job.AppGraph
import com.flowframes.android.job.SettingsStore

class FlowFramesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(SettingsStore(this))
    }
}
