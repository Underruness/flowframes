package com.flowframes.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The encoder must receive interpolated frames in submission order, however the workers finish
 * them, and the decoder must not run further ahead than the window allows.
 */
class OrderedWindowTest {

    @Test
    fun `items retire in submission order`() {
        val retired = ArrayList<Int>()
        val window = OrderedWindow<Int>(capacity = 10) { retired += it }
        (1..5).forEach { window.offer(it) }
        window.drain()
        assertEquals(listOf(1, 2, 3, 4, 5), retired)
    }

    @Test
    fun `the window retires as soon as it overflows`() {
        val retired = ArrayList<Int>()
        val window = OrderedWindow<Int>(capacity = 2) { retired += it }

        window.offer(1)
        window.offer(2)
        assertEquals("nothing should retire while inside the window", emptyList<Int>(), retired)

        window.offer(3)
        assertEquals(listOf(1), retired)
        assertEquals(2, window.size)

        window.offer(4)
        assertEquals(listOf(1, 2), retired)
    }

    @Test
    fun `drain empties a window that is already overflowing`() {
        val retired = ArrayList<Int>()
        val window = OrderedWindow<Int>(capacity = 1) { retired += it }
        (1..4).forEach { window.offer(it) }
        window.drain()
        assertEquals(listOf(1, 2, 3, 4), retired)
        assertEquals(0, window.size)
    }

    @Test
    fun `capacity is the number of items allowed in flight`() {
        val retired = ArrayList<String>()
        val window = OrderedWindow<String>(capacity = 1) { retired += it }
        window.offer("a")
        assertEquals("one item is allowed in flight", emptyList<String>(), retired)
        window.offer("b")
        assertEquals(listOf("a"), retired)
        window.drain()
        assertEquals(listOf("a", "b"), retired)
    }
}
