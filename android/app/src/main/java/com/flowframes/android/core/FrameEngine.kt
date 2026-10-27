package com.flowframes.android.core

/**
 * One RIFE implementation. Frames are planar RGB in [0,1] (CHW) for [interpolate], or Android
 * ARGB ints for [interpolateArgb], which is what the job uses: 4 bytes per pixel instead of 12,
 * so 1440p and 4K frames fit in the Java heap.
 */
interface FrameEngine : AutoCloseable {
    /** Short human-readable description for the job log (runtime, device, model). */
    val label: String

    /**
     * True when several threads may call [interpolate] on this same instance at once (ncnn: one
     * extractor per call). ONNX Runtime sessions are used one thread per session instead.
     */
    val threadSafe: Boolean

    fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray)

    /**
     * ARGB version. The default converts through per-thread float buffers (ONNX Runtime); the
     * ncnn engine overrides it with a native conversion.
     */
    fun interpolateArgb(frame0: IntArray, frame1: IntArray, width: Int, height: Int, t: Float, out: IntArray) {
        val n = width * height
        val s = ArgbScratch.get(n * 3)
        FrameMath.argbToPlanar(frame0, s.a, n)
        FrameMath.argbToPlanar(frame1, s.b, n)
        interpolate(s.a, s.b, width, height, t, s.o)
        FrameMath.planarToArgb(s.o, out, n)
    }
}

/** Float buffers of the default [FrameEngine.interpolateArgb], one set per worker thread. */
internal class ArgbScratch private constructor(size: Int) {
    val a = FloatArray(size)
    val b = FloatArray(size)
    val o = FloatArray(size)

    companion object {
        private val local = ThreadLocal<ArgbScratch>()

        fun get(size: Int): ArgbScratch {
            val s = local.get()
            if (s != null && s.a.size == size) return s
            return ArgbScratch(size).also { local.set(it) }
        }
    }
}
