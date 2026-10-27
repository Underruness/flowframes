package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * The fused (scale + rotate) decoder path and the planar encoder path replaced three-pass versions,
 * so they are pinned here against the reference converters.
 */
class YuvConverterFusedTest {

    private val spec = YuvConverter.BT709_LIMITED

    private fun planes(w: Int, h: Int, fill: (x: Int, y: Int) -> Int): Triple<ByteBuffer, ByteBuffer, ByteBuffer> {
        val y = ByteBuffer.allocateDirect(w * h)
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val u = ByteBuffer.allocateDirect(cw * ch)
        val v = ByteBuffer.allocateDirect(cw * ch)
        for (row in 0 until h) for (col in 0 until w) y.put(row * w + col, fill(col, row).toByte())
        for (row in 0 until ch) for (col in 0 until cw) {
            u.put(row * cw + col, 128.toByte())
            v.put(row * cw + col, 128.toByte())
        }
        return Triple(y, u, v)
    }

    @Test
    fun `identity size and rotation matches the reference converter`() {
        val (y, u, v) = planes(16, 12) { x, yy -> (x * 15 + yy * 3) and 0xFF }
        val expected = IntArray(16 * 12)
        YuvConverter.yuv420ToArgb(y, u, v, 16, 8, 1, 16, 12, spec, expected)

        val actual = IntArray(16 * 12)
        YuvConverter.yuv420ToArgbScaled(y, u, v, 16, 8, 1, 16, 12, 16, 12, 0, spec, actual)

        assertTrue(expected.contentEquals(actual))
    }

    @Test
    fun `rotation 90 matches rotateArgb without scaling`() {
        val w = 8
        val h = 6
        val (y, u, v) = planes(w, h) { x, yy -> (x * 20 + yy * 4 + 16) and 0xFF }
        val full = IntArray(w * h)
        YuvConverter.yuv420ToArgb(y, u, v, w, (w + 1) / 2, 1, w, h, spec, full)
        val rotated = FrameMath.rotateArgb(full, w, h, 90)

        val actual = IntArray(w * h)
        YuvConverter.yuv420ToArgbScaled(
            y, u, v, w, (w + 1) / 2, 1, w, h,
            rotated.width, rotated.height, 90, spec, actual
        )

        // Luma is resampled bilinearly, so allow a tiny tolerance for the 0.5 pixel centring.
        var maxDiff = 0
        for (i in rotated.pixels.indices) {
            val a = rotated.pixels[i]
            val b = actual[i]
            for (shift in intArrayOf(16, 8, 0)) {
                maxDiff = maxOf(maxDiff, abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF)))
            }
        }
        assertTrue("max channel difference was $maxDiff", maxDiff <= 2)
    }

    @Test
    fun `downscaling a flat color keeps that color`() {
        val (y, u, v) = planes(32, 32) { _, _ -> 100 }
        val out = IntArray(16 * 16)
        YuvConverter.yuv420ToArgbScaled(y, u, v, 32, 16, 1, 32, 32, 16, 16, 0, spec, out)
        val reference = IntArray(32 * 32)
        YuvConverter.yuv420ToArgb(y, u, v, 32, 16, 1, 32, 32, spec, reference)
        assertEquals(reference[0], out[0])
        for (px in out) assertEquals(reference[0], px)
    }

    @Test
    fun `planar to yuv round trip keeps a gray frame`() {
        val w = 16
        val h = 8
        val planar = FloatArray(w * h * 3) { 0.5f }
        val y = ByteBuffer.allocateDirect(w * h)
        val uvW = w / 2
        val uvH = h / 2
        val u = ByteBuffer.allocateDirect(uvW * uvH)
        val v = ByteBuffer.allocateDirect(uvW * uvH)

        YuvConverter.planarToYuv420(planar, w, h, y, u, v, w, uvW, 1, spec)

        val argb = IntArray(w * h)
        YuvConverter.yuv420ToArgb(y, u, v, w, uvW, 1, w, h, spec, argb)
        for (px in argb) {
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            assertTrue("gray drifted to $r/$g/$b", abs(r - 128) <= 3 && abs(g - 128) <= 3 && abs(b - 128) <= 3)
        }
    }

    @Test
    fun `planar to yuv with chroma pixel stride 2 writes interleaved pairs`() {
        val w = 8
        val h = 4
        // Left half red, right half blue in planar RGB (CHW).
        val planar = FloatArray(w * h * 3)
        val n = w * h
        for (row in 0 until h) for (col in 0 until w) {
            val i = row * w + col
            val left = col < w / 2
            planar[i] = if (left) 1f else 0f
            planar[n + i] = 0f
            planar[n * 2 + i] = if (left) 0f else 1f
        }
        val uvW = w / 2
        val uvH = h / 2
        val y = ByteBuffer.allocateDirect(w * h)
        val uv = ByteBuffer.allocateDirect(uvW * 2 * uvH) // interleaved NV12-style
        YuvConverter.planarToYuv420(planar, w, h, y, uv, uv, w, uvW * 2, 2, spec)

        // First chroma cell is red-ish: U well below neutral, V above.
        val u0 = uv.get(0).toInt() and 0xFF
        val v0 = uv.get(1).toInt() and 0xFF
        // Cells 2 and 3 cover the right (blue) half: cell 2 lives at bytes 4 and 5.
        val u1 = uv.get(4).toInt() and 0xFF
        val v1 = uv.get(5).toInt() and 0xFF
        assertTrue("red cell U=$u0 V=$v0", u0 < 110 && v0 > 150)
        assertTrue("blue cell U=$u1 V=$v1", u1 > 150 && v1 < 125)
    }
}
