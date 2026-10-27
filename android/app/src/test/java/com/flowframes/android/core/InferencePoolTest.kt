package com.flowframes.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The parallel pipeline is only worth its complexity if it is exactly equivalent to the sequential
 * one and if a session never runs two inferences at once. The first two tests run the real model
 * through one session and through a pool of three and compare the frames byte for byte; the last
 * one uses fake engines, because the scheduling contract has to be checked without a model.
 */
class InferencePoolTest {

    private val modelFile = File("src/main/assets/rife49_ensemble_False_scale_1_sim.onnx")

    private fun resource(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/rife/$name")!!.use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(buf.remaining())
        buf.get(out)
        return out
    }

    @Test
    fun `pooled sessions produce exactly the sequential frames`() {
        if (!modelFile.isFile) return
        val width = 64
        val height = 48
        val size = width * height * 3
        val frame0 = resource("frame0.f32")
        val frame1 = resource("frame1.f32")
        val phases = floatArrayOf(0.25f, 0.5f, 0.75f)

        val expected = ArrayList<FloatArray>(phases.size)
        RifeEngine(modelFile.absolutePath, intraOpThreads = 2).use { single ->
            for (t in phases) {
                val out = FloatArray(size)
                single.interpolate(frame0, frame1, width, height, t, out)
                expected += out
            }
        }

        InferencePool(modelFile.absolutePath, workers = 3, threadsPerWorker = 2).use { pool ->
            val produced = List(phases.size) { FloatArray(size) }
            val futures = phases.mapIndexed { index, t ->
                pool.submit(frame0, frame1, width, height, t, produced[index])
            }
            futures.forEachIndexed { index, future ->
                future.get()
                assertArrayEquals(
                    "frame at t=${phases[index]} differs from the sequential run",
                    expected[index], produced[index], 0f
                )
            }
        }
    }

    @Test
    fun `every submitted frame lands in its own buffer`() {
        if (!modelFile.isFile) return
        val width = 64
        val height = 48
        val size = width * height * 3
        val frame0 = resource("frame0.f32")
        val frame1 = resource("frame1.f32")

        InferencePool(modelFile.absolutePath, workers = 2, threadsPerWorker = 2).use { pool ->
            val outs = List(4) { FloatArray(size) }
            // Opposite phases must not end up in the same buffer.
            val futures = outs.indices.map { index ->
                pool.submit(frame0, frame1, width, height, if (index % 2 == 0) 0.1f else 0.9f, outs[index])
            }
            futures.forEach { it.get() }
            for (i in 1 until outs.size) {
                if (i % 2 == 1) {
                    val same = outs[i].indices.all { outs[i][it] == outs[0][it] }
                    assertTrue("phase 0.9 in buffer $i produced the same pixels as phase 0.1", !same)
                }
            }
        }
    }

    /** Fake engine that records how many inferences it is running at the same time. */
    private class FakeEngine(private val delayMs: Long, private val shared: Shared) : InferenceEngine() {

        /** Inferences in flight on **this** engine, and the highest value ever seen. */
        private val mine = AtomicInteger()
        val peakMine = AtomicInteger()

        /** Counters shared by every engine of the test. */
        class Shared {
            /** Inferences in flight across every engine, and the highest value ever seen. */
            val total = AtomicInteger()
            val peakTotal = AtomicInteger()

            val runs = AtomicInteger()
        }

        override fun close() {}

        override fun interpolate(
            frame0: FloatArray,
            frame1: FloatArray,
            width: Int,
            height: Int,
            t: Float,
            out: FloatArray
        ) {
            shared.runs.incrementAndGet()
            val mineNow = mine.incrementAndGet()
            peakMine.accumulateAndGet(mineNow) { a, b -> maxOf(a, b) }
            val totalNow = shared.total.incrementAndGet()
            shared.peakTotal.accumulateAndGet(totalNow) { a, b -> maxOf(a, b) }
            try {
                Thread.sleep(delayMs)
                // Every engine writes its phase, so the test can prove which task filled which buffer.
                java.util.Arrays.fill(out, t)
            } finally {
                shared.total.decrementAndGet()
                mine.decrementAndGet()
            }
        }
    }

    @Test
    fun `a session never runs two inferences at the same time`() {
        // The pool used to be one fixed worker pool with round-robin engine assignment: the task
        // submitted second could start first and land on a session that was still busy, which loses
        // exactly the isolation the measured 2.1x throughput depends on. One thread per session
        // makes that impossible, and the counters below fail the test if it ever comes back.
        val shared = FakeEngine.Shared()
        val engines = List(3) { FakeEngine(delayMs = 30, shared = shared) }

        InferencePool(engines, threadsPerWorker = 2).use { pool ->
            val outs = List(3) { FloatArray(4) }
            val futures = outs.indices.map { index ->
                pool.submit(FloatArray(4), FloatArray(4), 2, 2, index / 3f, outs[index])
            }
            futures.forEach { it.get() }

            engines.forEachIndexed { index, engine ->
                assertEquals("session $index ran two inferences at once", 1, engine.peakMine.get())
            }
            assertEquals("the pool reported a session overlap", 1, pool.peakConcurrencyPerSession)
            // At least two sessions must have worked at the same time, otherwise the pool has
            // silently become sequential.
            assertTrue(
                "no two sessions ran at the same time (peak ${shared.peakTotal.get()})",
                shared.peakTotal.get() >= 2
            )
            assertEquals(3, shared.runs.get())
            // Round-robin: one task per session, each one written into its own buffer.
            outs.forEachIndexed { index, out ->
                assertArrayEquals("buffer $index", FloatArray(4) { index / 3f }, out, 0f)
            }
        }
    }

    @Test
    fun `auto configuration keeps two cores for the codecs`() {
        val cores = Runtime.getRuntime().availableProcessors()
        val workers = InferencePool.defaultWorkers()
        val perWorker = InferencePool.defaultThreadsPerWorker(workers)
        assertTrue("workers must be at least 2", workers >= 2)
        assertTrue("threads per worker must be at least 1", perWorker >= 1)
        assertTrue(
            "pool uses $workers×$perWorker threads on $cores cores, leaving nothing for the codecs",
            workers * perWorker <= maxOf(2, cores - 2)
        )
    }
}
