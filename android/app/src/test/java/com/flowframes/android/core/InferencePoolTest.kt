package com.flowframes.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The parallel pipeline is only worth its complexity if it is exactly equivalent to the sequential
 * one. These tests run the real model through one session and through a pool of three, and compare
 * the frames byte for byte.
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
