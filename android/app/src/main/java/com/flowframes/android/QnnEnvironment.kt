package com.flowframes.android

import android.content.Context
import android.util.Log

/**
 * Gives the HTP backend the directory that contains `libQnnHtpVxxSkel.so`.
 *
 * The qnn-runtime AAR packages the skeleton in the app's native library directory, but FastRPC
 * does not search that directory by default. `android.system.Os` is not in the public SDK, so the
 * call is reflective and best effort.
 */
object QnnEnvironment {

    fun prepare(context: Context) {
        if (!BuildConfig.QNN_BUILD) return
        runCatching {
            val os = Class.forName("android.system.Os")
            os.getMethod(
                "setenv",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            ).invoke(null, "ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
        }.onFailure { Log.w("FlowFramesQnn", "ADSP_LIBRARY_PATH: ${it.message}") }
    }
}
