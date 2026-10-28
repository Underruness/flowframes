package com.flowframes.android.gen

import android.graphics.Bitmap
import java.io.File

/** Everything one generation needs. */
data class GenRequest(
    val prompt: String,
    val negative: String,
    val width: Int,
    val height: Int,
    val steps: Int,
    val cfg: Float,
    /** null draws a fresh seed and reports it back. */
    val seed: Long?,
    val sampler: Sampler,
    /** Denoising strength for image-to-image; 1.0 for text-to-image. */
    val strength: Float = 1f
)

enum class Sampler(val label: String, val native: Int) {
    /** Whatever the checkpoint was trained with (Euler a for SD 1.5, LCM for Turbo). */
    DEFAULT("Model default", -1),
    EULER_A("Euler a", 1),
    DPMPP2M("DPM++ 2M", 5),
    DDIM("DDIM", 10),
    LCM("LCM", 9)
}

enum class GenSize(val label: String, val width: Int, val height: Int) {
    SQUARE_512("512 × 512", 512, 512),
    PORTRAIT("512 × 768", 512, 768),
    LANDSCAPE("768 × 512", 768, 512),
    SQUARE_768("768 × 768", 768, 768)
}

/**
 * Keeps one loaded checkpoint alive between runs: loading a 2 GB safetensors takes tens of seconds
 * and the engine holds several GB once warm, so it is built once, reused, and closed when the model
 * or the thread count changes.
 */
class DiffusionEngine(private val onLog: (String) -> Unit = {}) : AutoCloseable {

    private var handle = 0L
    private var loadedPath: String? = null
    private var loadedThreads = 0
    private var loadedWeightType = Int.MIN_VALUE

    val modelName: String get() = if (handle != 0L) NativeDiffusion.modelName(handle) else "none"

    fun load(model: File, threads: Int, weightType: Int = -1): String {
        if (handle != 0L && loadedPath == model.absolutePath &&
            loadedThreads == threads && loadedWeightType == weightType
        ) {
            return modelName
        }
        close()
        onLog("Diffusion: loading ${model.name} (${ModelLibrary.formatBytes(model.length())}, $threads threads)")
        val started = System.currentTimeMillis()
        handle = NativeDiffusion.loadModel(model.absolutePath, threads, weightType)
        check(handle != 0L) { NativeDiffusion.lastError() }
        loadedPath = model.absolutePath
        loadedThreads = threads
        loadedWeightType = weightType
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        onLog("Diffusion: ${modelName} ready in ${String.format(java.util.Locale.US, "%.1f", seconds)} s")
        return modelName
    }

    /** Runs one generation and returns the ARGB bitmap. Blocking: call it off the main thread. */
    fun generate(
        request: GenRequest,
        init: Bitmap?,
        listener: (step: Int, total: Int) -> Unit
    ): Bitmap {
        check(handle != 0L) { "no model loaded" }
        val seed = request.seed ?: java.security.SecureRandom().nextLong().and(Long.MAX_VALUE)
        val initPixels = init?.let { bitmap ->
            IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        }
        val pixels = NativeDiffusion.generate(
            handle = handle,
            prompt = request.prompt,
            negative = request.negative,
            width = request.width,
            height = request.height,
            steps = request.steps,
            cfg = request.cfg,
            seed = seed,
            sampleMethod = request.sampler.native,
            scheduler = -1,
            initArgb = initPixels,
            initWidth = init?.width ?: 0,
            initHeight = init?.height ?: 0,
            strength = if (initPixels == null) 1f else request.strength,
            progress = StepListener { step, total -> listener(step, total) }
        ) ?: throw IllegalStateException(NativeDiffusion.lastError())
        return Bitmap.createBitmap(pixels, request.width, request.height, Bitmap.Config.ARGB_8888)
    }

    fun cancel() {
        if (handle != 0L) NativeDiffusion.cancel(handle)
    }

    override fun close() {
        if (handle != 0L) {
            runCatching { NativeDiffusion.freeModel(handle) }
            onLog("Diffusion: model released")
            handle = 0L
            loadedPath = null
        }
    }
}
