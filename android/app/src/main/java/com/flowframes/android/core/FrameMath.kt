package com.flowframes.android.core

/**
 * Pure-JVM frame maths shared by the decoder, the interpolation pipeline and the encoder.
 *
 * Frames are always plain ARGB `IntArray`s (as produced by `Bitmap.getPixels`) so that this file
 * stays free of Android APIs and can be unit tested on the JVM.
 */
object FrameMath {

    /** Packs an ARGB frame into 3 planar RGB channels in [0,1] (CHW order, no alpha). */
    fun argbToPlanar(src: IntArray, dst: FloatArray) {
        val n = src.size
        require(dst.size >= n * 3) { "planar buffer too small: ${dst.size} < ${n * 3}" }
        var r = 0
        var g = n
        var b = n * 2
        val inv = 1f / 255f
        for (i in 0 until n) {
            val c = src[i]
            dst[r++] = ((c ushr 16) and 0xFF) * inv
            dst[g++] = ((c ushr 8) and 0xFF) * inv
            dst[b++] = (c and 0xFF) * inv
        }
    }

    /** Inverse of [argbToPlanar]; values outside [0,1] are clamped. */
    fun planarToArgb(src: FloatArray, dst: IntArray) {
        val n = dst.size
        require(src.size >= n * 3) { "planar buffer too small" }
        var r = 0
        var g = n
        var b = n * 2
        for (i in 0 until n) {
            val rr = channel(src[r++])
            val gg = channel(src[g++])
            val bb = channel(src[b++])
            dst[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    private fun channel(v: Float): Int {
        val x = (v * 255f + 0.5f).toInt()
        return if (x < 0) 0 else if (x > 255) 255 else x
    }

    /** Bilinear resize of an ARGB frame. Returns [src] unchanged when the size already matches. */
    fun resizeArgb(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src
        val dst = IntArray(dw * dh)
        if (dw <= 0 || dh <= 0) return dst
        val xRatio = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val yRatio = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f
        val maxX = sw - 1
        val maxY = sh - 1
        for (y in 0 until dh) {
            val fy = y * yRatio
            val y0 = fy.toInt().coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val wy = fy - y0
            val row0 = y0 * sw
            val row1 = y1 * sw
            val outBase = y * dw
            for (x in 0 until dw) {
                val fx = x * xRatio
                val x0 = fx.toInt().coerceIn(0, maxX)
                val x1 = (x0 + 1).coerceAtMost(maxX)
                val wx = fx - x0
                val a = src[row0 + x0]
                val b = src[row0 + x1]
                val c = src[row1 + x0]
                val d = src[row1 + x1]
                dst[outBase + x] = bilinear(a, b, c, d, wx, wy)
            }
        }
        return dst
    }

    private fun bilinear(a: Int, b: Int, c: Int, d: Int, wx: Float, wy: Float): Int {
        val w00 = (1 - wx) * (1 - wy)
        val w10 = wx * (1 - wy)
        val w01 = (1 - wx) * wy
        val w11 = wx * wy
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val va = (a ushr shift) and 0xFF
            val vb = (b ushr shift) and 0xFF
            val vc = (c ushr shift) and 0xFF
            val vd = (d ushr shift) and 0xFF
            val v = va * w00 + vb * w10 + vc * w01 + vd * w11
            out = out or (((v + 0.5f).toInt().coerceIn(0, 255)) shl shift)
        }
        return out
    }

    data class Rgba(val pixels: IntArray, val width: Int, val height: Int)

    /** Rotates by a multiple of 90 degrees (0, 90, 180, 270). */
    fun rotateArgb(src: IntArray, w: Int, h: Int, degrees: Int): Rgba {
        val deg = ((degrees % 360) + 360) % 360
        if (deg == 0) return Rgba(src, w, h)
        if (deg == 180) {
            val dst = IntArray(src.size)
            for (i in src.indices) dst[i] = src[src.size - 1 - i]
            return Rgba(dst, w, h)
        }
        val dw = h
        val dh = w
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = src[y * w + x]
                if (deg == 90) {
                    // (x, y) -> (h - 1 - y, x)
                    dst[x * dw + (h - 1 - y)] = c
                } else {
                    // (x, y) -> (y, w - 1 - x)
                    dst[(w - 1 - x) * dw + y] = c
                }
            }
        }
        return Rgba(dst, dw, dh)
    }

    /** Mean absolute luma difference in [0,1]; `step` samples every n-th pixel. */
    fun meanAbsDiffLuma(a: IntArray, b: IntArray, step: Int = 4): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var sum = 0L
        var count = 0
        var i = 0
        while (i < n) {
            sum += kotlin.math.abs(luma(a[i]) - luma(b[i])).toLong()
            count++
            i += step
        }
        return if (count == 0) 0.0 else sum.toDouble() / count / 255.0
    }

    /** Fraction of sampled pixels whose luma changed by more than [threshold] (0..1). */
    fun changedFractionLuma(a: IntArray, b: IntArray, threshold: Double = 0.08, step: Int = 4): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        val limit = threshold * 255.0
        var changed = 0
        var count = 0
        var i = 0
        while (i < n) {
            if (kotlin.math.abs(luma(a[i]) - luma(b[i])) > limit) changed++
            count++
            i += step
        }
        return if (count == 0) 0.0 else changed.toDouble() / count
    }

    fun luma(color: Int): Int {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (r * 77 + g * 150 + b * 29) shr 8
    }

    /**
     * Scales [w]x[h] down so that it fits inside [maxW]x[maxH], keeping the aspect ratio.
     * Never upscales. Result dimensions are forced to even numbers (YUV 4:2:0 requirement).
     */
    fun fitWithin(w: Int, h: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
        var tw = w
        var th = h
        if (maxW > 0 && maxH > 0 && (w > maxW || h > maxH)) {
            val scale = minOf(maxW.toDouble() / w, maxH.toDouble() / h)
            tw = (w * scale).toInt().coerceAtLeast(2)
            th = (h * scale).toInt().coerceAtLeast(2)
        }
        tw -= tw % 2
        th -= th % 2
        return tw.coerceAtLeast(2) to th.coerceAtLeast(2)
    }
}
