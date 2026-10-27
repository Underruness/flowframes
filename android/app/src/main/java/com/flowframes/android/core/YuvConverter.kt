package com.flowframes.android.core

import java.nio.ByteBuffer

/**
 * YUV 4:2:0 <-> ARGB conversion with configurable colour matrix and range.
 *
 * Works directly on [ByteBuffer]s (the ones exposed by `android.media.Image.Plane`) so the same
 * code runs on device and in JVM unit tests. All reads/writes are absolute: buffer positions are
 * never modified.
 */
object YuvConverter {

    /**
     * @param yMul/yOff  luma expansion (Q8): `Y' = y * yMul + yOff`
     * @param cr/cgu/cgv/cb  chroma coefficients (Q8) used when decoding
     * @param eY/eU/eV  three RGB coefficient triplets (Q8) used when encoding
     * @param encYOff/encUvOff offsets added after the luma/chroma shifts
     */
    data class Spec(
        val label: String,
        val yMul: Int,
        val yOff: Int,
        val cr: Int,
        val cgu: Int,
        val cgv: Int,
        val cb: Int,
        val eY: IntArray,
        val encYOff: Int,
        val eU: IntArray,
        val eV: IntArray,
        val encUvOff: Int
    )

    val BT601_LIMITED = Spec(
        label = "bt601-limited", yMul = 298, yOff = -16 * 298,
        cr = 409, cgu = 100, cgv = 208, cb = 516,
        eY = intArrayOf(66, 129, 25), encYOff = 16,
        eU = intArrayOf(-38, -74, 112), eV = intArrayOf(112, -94, -18), encUvOff = 128
    )

    val BT709_LIMITED = Spec(
        label = "bt709-limited", yMul = 298, yOff = -16 * 298,
        cr = 459, cgu = 55, cgv = 136, cb = 541,
        eY = intArrayOf(47, 157, 16), encYOff = 16,
        eU = intArrayOf(-26, -86, 112), eV = intArrayOf(112, -102, -10), encUvOff = 128
    )

    val BT601_FULL = Spec(
        label = "bt601-full", yMul = 256, yOff = 0,
        cr = 359, cgu = 88, cgv = 183, cb = 454,
        eY = intArrayOf(77, 150, 29), encYOff = 0,
        eU = intArrayOf(-43, -85, 128), eV = intArrayOf(128, -107, -21), encUvOff = 128
    )

    val BT709_FULL = Spec(
        label = "bt709-full", yMul = 256, yOff = 0,
        cr = 403, cgu = 48, cgv = 120, cb = 475,
        eY = intArrayOf(54, 183, 19), encYOff = 0,
        eU = intArrayOf(-29, -99, 128), eV = intArrayOf(128, -116, -12), encUvOff = 128
    )

    /**
     * Converts a YUV 4:2:0 image to ARGB pixels.
     *
     * @param uvPixelStride 1 for planar (I420), 2 for semi-planar (NV12/NV21)
     */
    fun yuv420ToArgb(
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        width: Int, height: Int,
        spec: Spec, out: IntArray
    ) {
        require(out.size >= width * height) { "output too small" }
        for (row in 0 until height) {
            val yBase = row * yRowStride
            val uvRow = row shr 1
            val uvBase = uvRow * uvRowStride
            val outBase = row * width
            for (col in 0 until width) {
                val yv = y.get(yBase + col).toInt() and 0xFF
                val cu = uvBase + (col shr 1) * uvPixelStride
                val uv = (u.get(cu).toInt() and 0xFF) - 128
                val vv = (v.get(cu).toInt() and 0xFF) - 128
                val yScaled = yv * spec.yMul + spec.yOff
                val r = (yScaled + spec.cr * vv + 128) shr 8
                val g = (yScaled - spec.cgu * uv - spec.cgv * vv + 128) shr 8
                val b = (yScaled + spec.cb * uv + 128) shr 8
                out[outBase + col] = (0xFF shl 24) or
                    (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
    }

    /** Converts ARGB pixels into YUV 4:2:0 planes (chroma is averaged over each 2x2 block). */
    fun argbToYuv420(
        argb: IntArray, width: Int, height: Int,
        y: ByteBuffer, u: ByteBuffer, v: ByteBuffer,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        spec: Spec
    ) {
        require(argb.size >= width * height) { "input too small" }
        val eY = spec.eY
        val eU = spec.eU
        val eV = spec.eV
        for (row in 0 until height) {
            val yBase = row * yRowStride
            val outBase = row * width
            for (col in 0 until width) {
                val c = argb[outBase + col]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val yv = ((eY[0] * r + eY[1] * g + eY[2] * b + 128) shr 8) + spec.encYOff
                y.put(yBase + col, clamp(yv.toByte()))
            }
        }
        for (row in 0 until height step 2) {
            val uvBase = (row shr 1) * uvRowStride
            for (col in 0 until width step 2) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var n = 0
                for (dy in 0..1) {
                    val yy = row + dy
                    if (yy >= height) continue
                    val base = yy * width
                    for (dx in 0..1) {
                        val xx = col + dx
                        if (xx >= width) continue
                        val c = argb[base + xx]
                        rSum += (c ushr 16) and 0xFF
                        gSum += (c ushr 8) and 0xFF
                        bSum += c and 0xFF
                        n++
                    }
                }
                if (n == 0) continue
                val r = rSum / n
                val g = gSum / n
                val b = bSum / n
                val uu = ((eU[0] * r + eU[1] * g + eU[2] * b + 128) shr 8) + spec.encUvOff
                val vv = ((eV[0] * r + eV[1] * g + eV[2] * b + 128) shr 8) + spec.encUvOff
                val idx = uvBase + (col shr 1) * uvPixelStride
                u.put(idx, clamp(uu.toByte()))
                v.put(idx, clamp(vv.toByte()))
            }
        }
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun clamp(v: Byte): Byte = v
}
