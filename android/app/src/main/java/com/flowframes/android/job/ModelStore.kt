package com.flowframes.android.job

import android.content.Context
import java.io.File

/** Copies the bundled RIFE model out of the APK assets into internal storage (once). */
object ModelStore {

    fun assetName(quality: AiQuality): String = when (quality) {
        AiQuality.FAST -> "rife49_ensemble_False_scale_1_sim.onnx"
        AiQuality.HIGH -> "rife49_ensemble_True_scale_1_sim.onnx"
    }

    fun prepare(context: Context, quality: AiQuality, log: (String) -> Unit = {}): String {
        val name = assetName(quality)
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, name)
        val expectedSize = runCatching {
            context.assets.openFd(name).use { it.length }
        }.getOrNull()

        val upToDate = target.isFile && (expectedSize == null || target.length() == expectedSize)
        if (!upToDate) {
            log("Extraction du modèle ${if (quality == AiQuality.HIGH) "haute qualité" else "rapide"}…")
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        }
        return target.absolutePath
    }
}
