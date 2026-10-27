package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class YuvConverterTest {

    private fun bytes(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size)

    private fun hex(c: Int): String =
        "#%02X%02X%02X".format((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)

    /** Decoding a flat YUV image must give back (approximately) the encoded colour. */
    @Test
    fun `flat colour round trip`() {
        for (spec in listOf(YuvConverter.BT601_LIMITED, YuvConverter.BT709_LIMITED)) {
            val w = 32
            val h = 16
            val argb = IntArray(w * h) { 0xFFC86432.toInt() } // rgb(200,100,50)
            val y = bytes(w * h)
            val u = bytes(w * h / 4)
            val v = bytes(w * h / 4)
            YuvConverter.argbToYuv420(argb, w, h, y, u, v, w, w / 2, 1, spec)

            val out = IntArray(w * h)
            YuvConverter.yuv420ToArgb(y, u, v, w, w / 2, 1, w, h, spec, out)

            val c = out[0]
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            assertEquals("${spec.label} R", 200.0, r.toDouble(), 6.0)
            assertEquals("${spec.label} G", 100.0, g.toDouble(), 6.0)
            assertEquals("${spec.label} B", 50.0, b.toDouble(), 6.0)
            // Every pixel of a flat image must decode identically.
            assertTrue(out.all { it == c })
        }
    }

    @Test
    fun `round trip stays close for a gradient`() {
        val w = 16
        val h = 8
        // Smooth gradient: neighbouring pixels differ by at most a couple of levels, so chroma
        // subsampling (which averages each 2x2 block) cannot lose much.
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val r = 20 + x * 3
            val g = 40 + y * 4
            val b = 10 + (x + y) * 2
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val spec = YuvConverter.BT709_LIMITED
        val y = bytes(w * h)
        val u = bytes(w * h / 4 + 4)
        val v = bytes(w * h / 4 + 4)
        YuvConverter.argbToYuv420(argb, w, h, y, u, v, w, w / 2, 1, spec)
        val out = IntArray(w * h)
        YuvConverter.yuv420ToArgb(y, u, v, w, w / 2, 1, w, h, spec, out)
        for (i in argb.indices) {
            val a = argb[i]
            val b = out[i]
            val detail = "pixel $i expected=${hex(a)} got=${hex(b)}"
            assertTrue(detail, kotlin.math.abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF)) <= 10)
            assertTrue(detail, kotlin.math.abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF)) <= 10)
            assertTrue(detail, kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= 10)
        }
    }

    /** NV12/NV21 style interleaved chroma: pixelStride 2, U and V share one buffer with an offset. */
    @Test
    fun `semi-planar chroma with pixel stride 2`() {
        val w = 16
        val h = 8
        val argb = IntArray(w * h) { 0xFF3366CC.toInt() }
        val spec = YuvConverter.BT601_LIMITED
        val y = bytes(w * h)
        val uv = bytes(w * h / 2 + 2)
        val u = uv.duplicate()
        val v = uv.duplicate()
        u.position(0)
        v.position(1)

        YuvConverter.argbToYuv420(argb, w, h, y, u.slice(), v.slice(), w, w, 2, spec)
        val out = IntArray(w * h)
        YuvConverter.yuv420ToArgb(y, u.slice(), v.slice(), w, w, 2, w, h, spec, out)

        val c = out[0]
        assertEquals(0x33.toDouble(), ((c ushr 16) and 0xFF).toDouble(), 8.0)
        assertEquals(0x66.toDouble(), ((c ushr 8) and 0xFF).toDouble(), 8.0)
        assertEquals(0xCC.toDouble(), (c and 0xFF).toDouble(), 8.0)
    }

    @Test
    fun `row padding in the source is honoured`() {
        val w = 8
        val h = 4
        val pad = 12
        val spec = YuvConverter.BT601_LIMITED
        val argb = IntArray(w * h) { 0xFF102030.toInt() }
        val y = bytes((w + pad) * h)
        val u = bytes((w / 2 + pad) * (h / 2))
        val v = bytes((w / 2 + pad) * (h / 2))
        YuvConverter.argbToYuv420(argb, w, h, y, u, v, w + pad, w / 2 + pad, 1, spec)
        val out = IntArray(w * h)
        YuvConverter.yuv420ToArgb(y, u, v, w + pad, w / 2 + pad, 1, w, h, spec, out)
        assertTrue(out.all { it == out[0] })
        assertEquals(0x10.toDouble(), ((out[0] ushr 16) and 0xFF).toDouble(), 6.0)
    }
}
