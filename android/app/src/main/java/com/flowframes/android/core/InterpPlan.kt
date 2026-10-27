package com.flowframes.android.core

/**
 * Scheduling logic for interpolation, mirroring FlowFrames' behaviour:
 * frame de-duplication, scene-change handling, loop interpolation and FPS limiting.
 */
object InterpPlan {

    enum class Decision {
        /** Generate intermediate frames between the two frames. */
        INTERPOLATE,

        /** Scene cut detected: emit the next frame as-is (no morphing). */
        CUT,

        /** The next frame duplicates the previous one and de-duplication is enabled: drop it. */
        SKIP_DUPLICATE
    }

    data class Options(
        val factor: Int = 2,
        val loop: Boolean = false,
        val dedupe: Boolean = false,
        val dedupeThreshold: Double = 0.0025,
        val fixSceneChanges: Boolean = true,
        val sceneThreshold: Double = 0.35,
        val maxOutputFps: Double = 0.0
    )

    /** Phases strictly between two frames for a given multiplier: 1/2 for 2x, 1/3+2/3 for 3x, ... */
    fun phases(factor: Int): DoubleArray {
        val n = factor.coerceIn(1, 32)
        if (n <= 1) return DoubleArray(0)
        return DoubleArray(n - 1) { (it + 1).toDouble() / n }
    }

    /**
     * Reduces the requested multiplier so that `srcFps * factor` does not exceed [Options.maxOutputFps].
     * A cap of 0 (or less) disables the limit.
     */
    fun effectiveFactor(requested: Int, srcFps: Double, maxOutputFps: Double): Int {
        val req = requested.coerceIn(1, 32)
        if (maxOutputFps <= 0.0 || srcFps <= 0.0) return req
        var f = req
        while (f > 1 && srcFps * f > maxOutputFps + 1e-6) f--
        return f
    }

    fun outputFps(srcFps: Double, factor: Int): Double =
        if (srcFps <= 0.0) 0.0 else srcFps * factor.coerceAtLeast(1)

    /**
     * Decides what to do with the pair (previous, next).
     *
     * @param lumaDiff mean absolute luma difference in [0,1]
     * @param changedFraction fraction of pixels that changed noticeably
     */
    fun decide(previous: IntArray?, next: IntArray, opts: Options,
               lumaDiff: Double, changedFraction: Double): Decision {
        if (previous != null && opts.dedupe && lumaDiff <= opts.dedupeThreshold) return Decision.SKIP_DUPLICATE
        if (previous != null && opts.fixSceneChanges && changedFraction >= opts.sceneThreshold) return Decision.CUT
        return Decision.INTERPOLATE
    }

    /** Number of frames produced by one interpolated pair (first frame + intermediates). */
    fun framesPerPair(factor: Int): Int = factor.coerceAtLeast(1)
}
