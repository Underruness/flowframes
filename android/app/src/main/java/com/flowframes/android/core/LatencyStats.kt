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

    /** First [WINDOW] samples and the most recent [WINDOW] samples, for the drift measure. */
    private val opening = IntArray(WINDOW)
    private var openingCount = 0
    private val recent = IntArray(WINDOW)
    private var recentPos = 0
    private var recentCount = 0

    /** Number of samples retained for percentiles (bounded by [capacity]). */
    val size: Int get() = count

    fun add(ms: Int) {
        total++
        sum += ms
        if (ms < min) min = ms
        if (ms > max) max = ms
        if (count < capacity) samples[count++] = ms
        if (openingCount < WINDOW) opening[openingCount++] = ms
        recent[recentPos] = ms
        recentPos = (recentPos + 1) % WINDOW
        if (recentCount < WINDOW) recentCount++
    }

    private fun mean(values: IntArray, n: Int): Double {
        if (n == 0) return 0.0
        var s = 0L
        for (i in 0 until n) s += values[i]
        return s.toDouble() / n
    }

    /**
     * Relative change between the opening samples and the most recent ones, in percent.
     *
     * A sustained workload that starts at 380 ms/frame and ends at 900 ms/frame reports ~+135 %:
     * that is thermal throttling or contention, which no single mean value can show. Returns 0.0
     * until both windows are full, so a short run never claims a trend it cannot see.
     */
    fun driftPercent(): Double {
        if (openingCount < WINDOW || recentCount < WINDOW) return 0.0
        val first = mean(opening, openingCount)
        if (first <= 0.0) return 0.0
        val last = mean(recent, recentCount)
        return (last - first) / first * 100.0
    }

    /** True once [driftPercent] is backed by enough samples. */
    fun hasDrift(): Boolean = openingCount >= WINDOW && recentCount >= WINDOW

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

    /** Opening-window mean, exposed for the diagnosis line. */
    fun openingMeanMs(): Double = mean(opening, openingCount)

    /** Most-recent-window mean, exposed for the diagnosis line. */
    fun recentMeanMs(): Double = mean(recent, recentCount)

    /** One log line, or an empty string when nothing was measured. */
    fun summary(label: String): String {
        if (count == 0) return ""
        val base = String.format(
            Locale.US,
            "%s: min %d ms · median %.0f ms · p95 %.0f ms · p99 %.0f ms · max %d ms (%d samples)",
            label, minMs(), percentile(0.5), percentile(0.95), percentile(0.99), maxMs(), count
        )
        if (!hasDrift()) return base
        val drift = driftPercent()
        if (drift >= -4.0 && drift <= 4.0) return "$base · drift %.0f%% (steady)".format(Locale.US, drift)
        return "$base · drift %+.0f%% (first vs last $WINDOW samples)".format(Locale.US, drift)
    }

    private companion object {
        /** Samples kept at each end of the run for the drift measure. */
        const val WINDOW = 64
    }
}
