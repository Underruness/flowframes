package com.flowframes.android.gen

/**
 * The JNI surface of the diffusion engine (`libcapri_diffusion.so`).
 *
 * The library is loaded lazily: a phone where it is missing (an APK repacked without the arm64
 * library) must still open the Studio and show why Create is unavailable instead of crashing.
 */
internal object NativeDiffusion {

    val unavailableReason: String? = runCatching {
        System.loadLibrary("capri_diffusion")
    }.exceptionOrNull()?.let { it.message ?: "libcapri_diffusion.so not found" }

    val available: Boolean get() = unavailableReason == null

    /** Loads a checkpoint. Returns a native handle, or 0 on failure (see [lastError]). */
    external fun loadModel(path: String, threads: Int, weightType: Int): Long

    external fun freeModel(handle: Long)

    external fun modelName(handle: Long): String

    /**
     * Runs one generation. [initArgb] turns it into image-to-image (with [strength]). Returns the
     * ARGB pixels of the result, or null on failure (see [lastError]).
     */
    external fun generate(
        handle: Long,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        sampleMethod: Int,
        scheduler: Int,
        initArgb: IntArray?,
        initWidth: Int,
        initHeight: Int,
        strength: Float,
        progress: StepListener?
    ): IntArray?

    external fun cancel(handle: Long)

    external fun lastError(): String

    external fun systemInfo(): String
}

/** Progress callback: the engine calls this once per denoising step. */
fun interface StepListener {
    fun onStep(step: Int, total: Int)
}
