package com.flowframes.android.core

import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Several RIFE sessions working at the same time.
 *
 * One inference cannot keep eight cores busy: on a Snapdragon 8 Elite, 4, 6 and 8 intra-op threads
 * cost 857, 814 and 885 ms per frame — the threads wait on memory, they do not compute faster.
 * Independent inferences do fill the machine. Measured at 576×576 with `tools/concurrency_probe.py`:
 *
 * | Configuration | ms per inference | Throughput |
 * |---|---|---|
 * | 1 session × 8 threads | 259 | 3.9 inf/s |
 * | 2 sessions × 4 threads | 147 | 6.8 inf/s |
 * | 3 sessions × 2 threads | 162 | 6.2 inf/s |
 * | 4 sessions × 2 threads | **125** | **8.0 inf/s** |
 *
 * That is 2.1× the throughput for the same output, pixel for pixel: this is scheduling, not a
 * quality trade-off. Each session owns its own arena, so memory grows with [workers] — the caller
 * chooses the count and the benchmark measures it on the device.
 */
class InferencePool(
    modelPath: String,
    val workers: Int,
    val threadsPerWorker: Int,
    useNnapi: Boolean = false,
    useXnnpack: Boolean = false,
    parallelOps: Boolean = false,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    profileDir: File? = null,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    private val engines: List<RifeEngine>
    private val executor: ExecutorService
    private val next = AtomicInteger()

    /** One of the sessions, for the rare call that must block the calling thread. */
    val primary: RifeEngine get() = engines[0]

    /** Called on the worker thread after every interpolation, with its duration in nanoseconds. */
    @Volatile
    var onInference: ((Long) -> Unit)? = null

    init {
        require(workers >= 1) { "workers must be >= 1" }
        val start = System.currentTimeMillis()
        engines = ArrayList<RifeEngine>(workers)
        try {
            for (index in 0 until workers) {
                engines += RifeEngine(
                    modelPath = modelPath,
                    intraOpThreads = threadsPerWorker,
                    useNnapi = useNnapi,
                    useXnnpack = useXnnpack,
                    parallelOps = parallelOps,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    profileDir = if (index == 0) profileDir else null,
                    log = if (index == 0) log else ({ _: String -> })
                )
            }
        } catch (t: Throwable) {
            engines.forEach { runCatching { it.close() } }
            throw t
        }
        val created = AtomicInteger()
        executor = Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "rife-${created.incrementAndGet()}").apply { isDaemon = true }
        }
        log(
            "Inference pool: $workers sessions × $threadsPerWorker threads " +
                "(${workers * threadsPerWorker} threads, built in ${System.currentTimeMillis() - start} ms)"
        )
    }

    /** Total inference threads across every session. */
    val totalThreads: Int get() = workers * threadsPerWorker

    /**
     * Queues one interpolation. The array [out] belongs to the caller and is written by a worker
     * thread; the returned future completes when it is safe to encode it.
     */
    fun submit(
        frame0: FloatArray,
        frame1: FloatArray,
        width: Int,
        height: Int,
        t: Float,
        out: FloatArray
    ): Future<*> {
        val engine = engines[Math.floorMod(next.getAndIncrement(), workers)]
        return executor.submit {
            val start = System.nanoTime()
            try {
                engine.interpolate(frame0, frame1, width, height, t, out)
            } finally {
                onInference?.invoke(System.nanoTime() - start)
            }
        }
    }

    /** Runs every session once so the first real frame does not pay for lazy setup. */
    fun warmUp(width: Int, height: Int) {
        val futures = ArrayList<Future<*>>(workers)
        for (engine in engines) {
            val a = FloatArray(width * height * 3) { 0.5f }
            val b = FloatArray(width * height * 3) { 0.5f }
            val o = FloatArray(width * height * 3)
            futures += executor.submit { engine.interpolate(a, b, width, height, 0.5f, o) }
        }
        futures.forEach { runCatching { it.get() } }
    }

    override fun close() {
        executor.shutdown()
        val quiet = runCatching { executor.awaitTermination(60, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!quiet) {
            // A cancelled job can leave an inference running; closing the session under it would
            // crash in native code, so let the process reclaim it instead.
            executor.shutdownNow()
            log("Inference pool: a session was still busy at shutdown, it is left to the process")
            return
        }
        engines.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * Sessions to run in parallel. Two threads per session keeps a core pair per session and
         * leaves two cores for the decoder, the encoder and the pixel conversions.
         */
        fun defaultWorkers(): Int = Runtime.getRuntime().availableProcessors().let { cores ->
            when {
                cores >= 8 -> 3
                cores >= 6 -> 2
                else -> 2
            }
        }

        /** Threads per session so that the pool never occupies more than `cores - 2` cores. */
        fun defaultThreadsPerWorker(workers: Int): Int =
            Runtime.getRuntime().availableProcessors().let { cores ->
                val usable = if (cores <= 2) 1 else cores - 2
                (usable / workers.coerceAtLeast(1)).coerceAtLeast(1)
            }
    }
}

/**
 * Reuses frame buffers instead of allocating 4 MB arrays per frame: at 576×576 the job moves
 * roughly 20 MB per pair, and the collector should not see any of it.
 */
class FloatArrayPool(private val size: Int) {

    private val free = ArrayDeque<FloatArray>()

    @Synchronized
    fun acquire(): FloatArray = free.removeLastOrNull() ?: FloatArray(size)

    @Synchronized
    fun release(array: FloatArray) {
        if (array.size == size && free.size < 12) free.addLast(array)
    }
}
