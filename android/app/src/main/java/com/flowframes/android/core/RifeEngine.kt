package com.flowframes.android.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.util.Locale

/**
 * RIFE frame-interpolation engine on top of ONNX Runtime.
 *
 * Input frames are planar RGB in [0,1] (CHW), the timestep is the interpolation phase.
 *
 * Two settings matter for throughput:
 *  - [frameWidth]/[frameHeight] resolve the model's symbolic `height`/`width` dimensions. With
 *    static shapes the execution providers can pre-plan their kernels and the graph's shape
 *    arithmetic folds away; the result is bit-identical (verified against the dynamic graph).
 *  - [profileDir] enables ONNX Runtime's per-node profiler, used by the in-app benchmark.
 *
 * This class only uses `java.*` + ONNX Runtime APIs so it can run in plain JVM unit tests.
 */
class RifeEngine(
    modelPath: String,
    intraOpThreads: Int = 4,
    useNnapi: Boolean = false,
    useXnnpack: Boolean = true,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    profileDir: File? = null,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    /** Two separate frame inputs (`img0`/`img1`) instead of one 6-channel tensor. */
    private val splitFrames: Boolean
    private val firstFrameInput: String
    private val secondFrameInput: String
    private val timestepInput: String?
    private val timestepShape: LongArray

    val modelName: String = File(modelPath).name
    val staticShapes: Boolean
    val threads: Int

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val profileFile: String?

    private var concatBuffer: FloatArray = FloatArray(0)
    private var concatBufferView: FloatBuffer = FloatBuffer.wrap(concatBuffer)

    init {
        require(File(modelPath).isFile) { "Model not found: $modelPath" }
        threads = if (intraOpThreads > 0) intraOpThreads else defaultThreads()
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)

        var static = false
        if (frameWidth > 0 && frameHeight > 0) {
            // The exported RIFE graph names its dynamic dimensions `height` and `width`.
            // Unknown names are rejected by ORT, so each candidate is tried independently.
            for ((name, value) in listOf(
                "height" to frameHeight, "width" to frameWidth,
                "H" to frameHeight, "W" to frameWidth
            )) {
                val ok = runCatching { opts.setSymbolicDimensionValue(name, value.toLong()) }.isSuccess
                if (ok) static = true
            }
        }
        staticShapes = static

        if (useNnapi) {
            runCatching { opts.addNnapi() }
                .onSuccess { log("ORT: NNAPI enabled") }
                .onFailure { log("ORT: NNAPI unavailable (${it.message}), using CPU") }
        }
        if (useXnnpack) {
            runCatching { opts.addXnnpack(emptyMap()) }
                .onSuccess { log("ORT: XNNPACK enabled") }
                .onFailure { log("ORT: XNNPACK unavailable (${it.message})") }
        }
        profileFile = profileDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                val f = File(dir, "ort-profile-${System.currentTimeMillis()}.json")
                opts.enableProfiling(f.absolutePath)
                f.absolutePath
            }.onFailure { log("ORT: profiling unavailable (${it.message})") }.getOrNull()
        }

        session = env.createSession(modelPath, opts)

        val inputs = session.inputInfo.entries.toList()
        val frameInputs = inputs.filter { (name, info) ->
            val shape = (info.info as? TensorInfo)?.shape
            name != "timestep" && shape != null && shape.size >= 4
        }.map { it.key }.sorted()

        val ts = inputs.firstOrNull { (name, info) ->
            name == "timestep" || ((info.info as? TensorInfo)?.shape?.size ?: 0) <= 1
        }

        splitFrames = frameInputs.size >= 2
        firstFrameInput = frameInputs.getOrElse(0) { "input" }
        secondFrameInput = if (splitFrames) frameInputs[1] else firstFrameInput
        timestepInput = ts?.key
        timestepShape = (ts?.value?.info as? TensorInfo)?.shape ?: longArrayOf(1)

        val shape = if (static) "${frameWidth}x$frameHeight (static)" else "dynamic"
        val inputsSummary = inputs.joinToString(", ") { (name, info) ->
            val dims = (info.info as? TensorInfo)?.shape?.joinToString("x") ?: "?"
            "$name[$dims]"
        }
        log(
            "ORT ${runCatching { env.version }.getOrDefault("?")}: $modelName · $shape · $threads threads · " +
                "EP ${if (useNnapi) "NNAPI," else ""}${if (useXnnpack) "XNNPACK" else "CPU"} · inputs: $inputsSummary"
        )
    }

    /**
     * Interpolates the frame at phase [t] between [frame0] and [frame1].
     *
     * @param frame0 planar RGB, size 3*width*height
     * @param frame1 planar RGB, size 3*width*height
     * @param out planar RGB result, size 3*width*height
     */
    fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray) {
        val planeSize = width * height
        require(frame0.size >= planeSize * 3 && frame1.size >= planeSize * 3) { "frame buffers too small" }
        require(out.size >= planeSize * 3) { "output buffer too small" }

        val frameShape = longArrayOf(1, 3, height.toLong(), width.toLong())
        val tensors = ArrayList<OnnxTensor>(3)
        try {
            val feeds = HashMap<String, OnnxTensor>(3)
            if (splitFrames) {
                val t0 = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame0, 0, planeSize * 3), frameShape)
                tensors.add(t0)
                feeds[firstFrameInput] = t0
                val t1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame1, 0, planeSize * 3), frameShape)
                tensors.add(t1)
                feeds[secondFrameInput] = t1
            } else {
                val need = planeSize * 6
                if (concatBuffer.size < need) {
                    concatBuffer = FloatArray(need)
                    concatBufferView = FloatBuffer.wrap(concatBuffer)
                }
                System.arraycopy(frame0, 0, concatBuffer, 0, planeSize * 3)
                System.arraycopy(frame1, 0, concatBuffer, planeSize * 3, planeSize * 3)
                val t0 = OnnxTensor.createTensor(
                    env, concatBufferView, longArrayOf(1, 6, height.toLong(), width.toLong())
                )
                tensors.add(t0)
                feeds[firstFrameInput] = t0
            }
            timestepInput?.let { name ->
                val tsTensor = if (timestepShape.isEmpty()) {
                    OnnxTensor.createTensor(env, floatArrayOf(t))
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1))
                }
                tensors.add(tsTensor)
                feeds[name] = tsTensor
            }

            session.run(feeds).use { result ->
                val value = result.get(0)
                val tensor = value as? OnnxTensor
                    ?: throw IllegalStateException("Unexpected model output: ${value.javaClass.simpleName}")
                tensor.use {
                    val buf = it.floatBuffer
                    if (buf.remaining() < planeSize * 3) {
                        throw IllegalStateException("Output too small: ${buf.remaining()} < ${planeSize * 3}")
                    }
                    buf.get(out, 0, planeSize * 3)
                }
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    /** Runs one inference on noise, so the first measured frame is not paying for lazy setup. */
    fun warmUp(width: Int, height: Int) {
        val a = FloatArray(width * height * 3) { 0.5f }
        val b = FloatArray(width * height * 3) { 0.5f }
        val o = FloatArray(width * height * 3)
        interpolate(a, b, width, height, 0.5f, o)
    }

    /**
     * Stops ONNX Runtime profiling and returns the heaviest operators, heaviest first.
     * Format: `GridSample 137.2 ms (14 calls)`.
     */
    fun stopProfiling(top: Int = 8): List<String> {
        val path = profileFile ?: return emptyList()
        val json = runCatching { session.endProfiling() }.getOrNull() ?: path
        val file = File(json)
        if (!file.isFile) return emptyList()
        val totals = HashMap<String, Double>()
        val counts = HashMap<String, Int>()
        runCatching {
            val events = org.json.JSONArray(file.readText())
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                if (ev.optString("cat") != "Node") continue
                val dur = ev.optDouble("dur", -1.0)
                if (dur < 0) continue
                val op = ev.optJSONObject("args")?.optString("op_name").orEmpty().ifEmpty { "?" }
                totals[op] = (totals[op] ?: 0.0) + dur / 1000.0
                counts[op] = (counts[op] ?: 0) + 1
            }
        }
        file.delete()
        return totals.entries.sortedByDescending { it.value }.take(top).map { (op, ms) ->
            String.format(Locale.US, "%s %.1f ms (%d calls)", op, ms, counts[op] ?: 0)
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /**
         * Default worker threads. The decoder and encoder are hardware blocks that mostly sleep, so
         * using every core for inference is faster than reserving some of them.
         */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().let {
            if (it <= 2) 1 else it.coerceAtMost(8)
        }
    }
}
