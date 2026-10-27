package com.flowframes.android.core

import java.nio.ByteBuffer

/** Pixel conversions done in C++ (same library as the ncnn engine), with Kotlin fallbacks. */
object NativeFrames {

    val isAvailable: Boolean get() = NcnnRifeEngine.isAvailable

    /**
     * ARGB → YUV 4:2:0 into direct ByteBuffers (the encoder's input planes). Same integer maths
     * as [YuvConverter.argbToYuv420], about ten times faster than its per-pixel ByteBuffer.put.
     * Returns false when the native library is missing or the buffers are not direct.
     */
    fun argbToYuv420(
        argb: IntArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        spec: YuvConverter.Spec
    ): Boolean {
        if (!isAvailable || !y.isDirect || !u.isDirect || !v.isDirect) return false
        val coeffs = intArrayOf(
            spec.eY[0], spec.eY[1], spec.eY[2],
            spec.eU[0], spec.eU[1], spec.eU[2],
            spec.eV[0], spec.eV[1], spec.eV[2],
            spec.encYOff, spec.encUvOff
        )
        return runCatching {
            nativeArgbToYuv420(argb, width, height, y, u, v, yRowStride, uvRowStride, uvPixelStride, coeffs) == 0
        }.getOrDefault(false)
    }

    @JvmStatic private external fun nativeArgbToYuv420(
        argb: IntArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        coeffs: IntArray
    ): Int
}
