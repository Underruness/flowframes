package com.flowframes.android.core

import java.util.Locale

/**
 * Small latency accumulator. A mean hides throttling: a run that starts at 350 ms/frame and ends at
 * 900 ms/frame has the same mean as a steady 625 ms run, and those need different fixes.
 */
class LatencyStats(private val capacity: Int = 4096) {

    private val samples = IntArray(capacity)
    private var count = 0
    private var total = 0
    private var sum = 0L
    private var min = Int.MAX_VALUE
    private var max = 0

    /** Number of samples retained for percentiles (bounded by [capacity]). */
    val size: Int get() = count

    fun add(ms: Int) {
        total++
        sum += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        if (count < capacity) samples[count++] = ms
    }

    /** Mean over every sample, including those dropped once the buffer is full. */
    fun mean(): Double = if (total == 0) 0.0 else sum.toDouble() / total

    /** Nearest-rank percentile, clamped to [0,1]. */
    fun percentile(p: Double): Double {
        if (count == 0) return 0.0
        val sorted = samples.copyOf(count).also { it.sort() }
        val rank = (p.coerceIn(0.0, 1.0) * (count - 1)).toInt()
        return sorted[rank].toDouble()
    }

    fun minMs(): Int = if (count == 0) 0 else min
    fun maxMs(): Int = max

    /** One log line, or an empty string when nothing was measured. */
    fun summary(label: String): String {
        if (count == 0) return ""
        return String.format(
            Locale.US,
            "%s: min %d ms · median %.0f ms · p95 %.0f ms · max %d ms (%d samples)",
            label, minMs(), percentile(0.5), percentile(0.95), maxMs(), count
        )
    }
}
