package com.flowframes.android.core

/**
 * One RIFE implementation. Frames are planar RGB in [0,1] (CHW) for every engine, so the pipeline,
 * the pool and the tests do not care which one runs.
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
}
