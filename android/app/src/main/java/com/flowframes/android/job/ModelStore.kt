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
            log("Extracting the ${if (quality == AiQuality.HIGH) "high quality" else "fast"} model…")
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
            }
        }
        return target.absolutePath
    }

    /**
     * Extracts the ncnn RIFE model (`assets/ncnn/<dir>/flownet.{param,bin}`) and returns its
     * directory. Files are compared by size so an app update with new weights replaces them.
     */
    fun prepareNcnn(context: Context, model: NcnnModel, log: (String) -> Unit = {}): String {
        val dir = File(context.filesDir, "models/ncnn/${model.dir}").apply { mkdirs() }
        for (name in listOf("flownet.param", "flownet.bin")) {
            val asset = "ncnn/${model.dir}/$name"
            val target = File(dir, name)
            val expectedSize = runCatching { context.assets.openFd(asset).use { it.length } }.getOrNull()
            val upToDate = target.isFile && (expectedSize == null || target.length() == expectedSize)
            if (!upToDate) {
                log("Extracting ${model.label} ($name)…")
                val tmp = File(dir, "$name.tmp")
                context.assets.open(asset).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                }
                if (!tmp.renameTo(target)) throw IllegalStateException("Cannot write ${target.absolutePath}")
            }
        }
        return dir.absolutePath
    }
}
