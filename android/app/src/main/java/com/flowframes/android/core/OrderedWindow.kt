package com.flowframes.android.core

/**
 * Keeps at most [capacity] items in flight and retires them strictly in submission order.
 *
 * Interpolated frames are computed out of order (several workers, different phases) but must be
 * encoded in order, and they must not pile up: a window bounds both the memory held by in-flight
 * frames and how far the decoder may run ahead of the encoder.
 */
class OrderedWindow<T>(private val capacity: Int, private val retire: (T) -> Unit) {

    private val items = ArrayDeque<T>()

    val size: Int get() = items.size

    /** Adds an item, retiring the oldest ones while the window is over capacity. */
    fun offer(item: T) {
        items.addLast(item)
        while (items.size > capacity) retire(items.removeFirst())
    }

    /** Retires everything, in order. */
    fun drain() {
        while (items.isNotEmpty()) retire(items.removeFirst())
    }
}
