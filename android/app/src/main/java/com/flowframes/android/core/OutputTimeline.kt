package com.flowframes.android.core

import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * When output frames are due, as positions between two source frames.
 *
 * - Multiplier mode (x2, x3 ...): every source interval gets `factor` evenly spaced frames, the
 *   last one being the next source frame itself.
 * - Target fps mode: output frames sit on a fixed grid (`origin + k / fps`) whatever the source
 *   rate is, so 24 → 60 fps (x2.5) or 30 → 50 fps work; RIFE v4 interpolates any timestep. Frames
 *   that land on a source frame reuse it instead of running the model.
 */
class OutputTimeline private constructor(
    /** Multiplier, or 0 in target fps mode. */
    val factor: Int,
    /** Output fps in target mode, 0 otherwise. */
    val targetFps: Double
) {
    /** One output frame: its timestamp and its position t in [0, 1] between the two sources. */
    data class Item(val ptsUs: Long, val t: Double) {
        val isFirst: Boolean get() = t <= 0.0
        val isSecond: Boolean get() = t >= 1.0
        val needsInference: Boolean get() = !isFirst && !isSecond
    }

    val isTarget: Boolean get() = targetFps > 0.0

    /** Timestamp of the first source frame; the target grid starts there. */
    var originUs: Long = 0L

    private val periodUs: Double = if (targetFps > 0.0) 1_000_000.0 / targetFps else 0.0

    /** Output frames due in (startUs, endUs], in order. */
    fun between(startUs: Long, endUs: Long): List<Item> {
        val span = endUs - startUs
        if (span <= 0L) return listOf(Item(endUs, 1.0))
        if (!isTarget) {
            return (1..factor).map { i -> Item(startUs + span * i / factor, if (i == factor) 1.0 else i.toDouble() / factor) }
        }
        // k is the grid index; the small epsilon keeps a frame exactly on a boundary in one pair.
        val kFirst = floor((startUs - originUs) / periodUs + EPS).toLong() + 1
        val kLast = floor((endUs - originUs) / periodUs + EPS).toLong()
        if (kLast < kFirst) return emptyList()
        val items = ArrayList<Item>((kLast - kFirst + 1).toInt())
        for (k in kFirst..kLast) {
            val ts = originUs + k * periodUs
            var t = (ts - startUs) / span
            if (t >= 1.0 - SNAP) t = 1.0
            if (t <= SNAP) t = 0.0
            items += Item(ts.roundToLong(), t)
        }
        return items
    }

    /** Same frames for a scene cut: no morphing, each one shows the nearest source frame. */
    fun cut(startUs: Long, endUs: Long): List<Item> {
        if (!isTarget) return listOf(Item(endUs, 1.0))
        return between(startUs, endUs).map { Item(it.ptsUs, if (it.t < 0.5) 0.0 else 1.0) }
    }

    /** Output rate for a source rate. */
    fun outputFps(srcFps: Double): Double = if (isTarget) targetFps else srcFps * factor

    /** Upper bound of the interpolated frames per source interval, for buffer budgeting. */
    fun maxInferencesPerPair(srcFps: Double): Int =
        if (isTarget) kotlin.math.ceil(targetFps / srcFps.coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
        else (factor - 1).coerceAtLeast(0)

    companion object {
        private const val EPS = 1e-6
        /** Within 1/1000 of a source frame (about 40 µs at 24 fps), the source frame is used. */
        private const val SNAP = 1e-3

        fun multiplier(factor: Int) = OutputTimeline(factor.coerceIn(1, 32), 0.0)

        fun targetFps(fps: Double): OutputTimeline {
            require(fps > 0.0) { "target fps must be > 0" }
            return OutputTimeline(0, fps)
        }
    }
}
