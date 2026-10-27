package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FrameMathTest {

    @Test
    fun `planar round trip keeps colours`() {
        val w = 8
        val h = 4
        val argb = IntArray(w * h) { i ->
            val r = (i * 7) and 0xFF
            val g = (i * 11 + 3) and 0xFF
            val b = (i * 13 + 5) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val planar = FloatArray(w * h * 3)
        FrameMath.argbToPlanar(argb, planar)
        val back = IntArray(w * h)
        FrameMath.planarToArgb(planar, back)
        assertArrayEquals(argb, back)
    }

    @Test
    fun `planar layout is channel-major`() {
        val w = 2
        val h = 1
        val argb = intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt())
        val planar = FloatArray(6)
        FrameMath.argbToPlanar(argb, planar)
        // R plane = 0x10, 0x40 ; G plane = 0x20, 0x50 ; B plane = 0x30, 0x60
        assertEquals(0x10 / 255f, planar[0], 1e-6f)
        assertEquals(0x40 / 255f, planar[1], 1e-6f)
        assertEquals(0x20 / 255f, planar[2], 1e-6f)
        assertEquals(0x50 / 255f, planar[3], 1e-6f)
        assertEquals(0x30 / 255f, planar[4], 1e-6f)
        assertEquals(0x60 / 255f, planar[5], 1e-6f)
    }

    @Test
    fun `resize keeps a uniform image uniform`() {
        val w = 32
        val h = 18
        val color = 0xFF3366AA.toInt()
        val src = IntArray(w * h) { color }
        val dst = FrameMath.resizeArgb(src, w, h, 11, 7)
        assertTrue(dst.all { it == color })
    }

    @Test
    fun `resize returns the same array when dimensions match`() {
        val src = IntArray(16) { it }
        assertTrue(src === FrameMath.resizeArgb(src, 4, 4, 4, 4))
    }

    @Test
    fun `rotate 90 and 270 are inverse`() {
        val w = 3
        val h = 2
        val src = IntArray(w * h) { 0xFF000000.toInt() or it }
        val r90 = FrameMath.rotateArgb(src, w, h, 90)
        assertEquals(h, r90.width)
        assertEquals(w, r90.height)
        val back = FrameMath.rotateArgb(r90.pixels, r90.width, r90.height, 270)
        assertEquals(w, back.width)
        assertEquals(h, back.height)
        assertArrayEquals(src, back.pixels)
    }

    @Test
    fun `rotate 180 keeps dimensions and mirrors`() {
        val src = intArrayOf(1, 2, 3, 4, 5, 6)
        val r = FrameMath.rotateArgb(src, 3, 2, 180)
        assertArrayEquals(intArrayOf(6, 5, 4, 3, 2, 1), r.pixels)
        assertEquals(3, r.width)
    }

    @Test
    fun `diff metrics separate duplicates from scene cuts`() {
        val w = 40
        val h = 40
        val a = IntArray(w * h) { 0xFF202020.toInt() }
        val identical = a.copyOf()
        val slight = IntArray(w * h) { i -> if (i % 97 == 0) 0xFF282828.toInt() else 0xFF202020.toInt() }
        val cut = IntArray(w * h) { 0xFFF0F0F0.toInt() }

        assertEquals(0.0, FrameMath.meanAbsDiffLuma(a, identical), 1e-9)
        assertTrue(FrameMath.meanAbsDiffLuma(a, slight) < 0.01)
        assertTrue(FrameMath.changedFractionLuma(a, cut) > 0.9)
        assertTrue(FrameMath.changedFractionLuma(a, identical) < 1e-9)
    }

    @Test
    fun `fitWithin never upscales and keeps even dimensions`() {
        assertEquals(1280 to 720, FrameMath.fitWithin(3840, 2160, 1280, 1280))
        assertEquals(640 to 360, FrameMath.fitWithin(640, 360, 1920, 1920))
        assertEquals(1080 to 1920, FrameMath.fitWithin(1080, 1920, 1920, 1920))
        val (w, h) = FrameMath.fitWithin(1919, 1079, 1920, 1920)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    private fun assertArrayEquals(a: IntArray, b: IntArray) {
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("index $i", a[i], b[i])
        }
    }

    @Test
    fun `luma matches the bt601 weights`() {
        assertEquals(0, FrameMath.luma(0xFF000000.toInt()))
        assertEquals(255, FrameMath.luma(0xFFFFFFFF.toInt()))
        assertTrue(abs(FrameMath.luma(0xFFFF0000.toInt()) - 76) <= 1)
        assertTrue(abs(FrameMath.luma(0xFF00FF00.toInt()) - 150) <= 1)
    }
}
