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
 * [qnn] switches the session to the Qualcomm QNN execution provider (Hexagon HTP / NPU). It is
 * strict: `session.disable_cpu_ep_fallback=1` is set, so every node must be claimed by QNN. If the
 * HTP backend cannot run the whole graph, session creation fails and the job fails with that
 * cause; there is no CPU fallback. The HTP also requires fixed shapes, so a QNN engine refuses
 * dynamic ones instead of silently switching to the CPU.
 *
 * A created session therefore proves placement in a way the Java API cannot show directly: ONNX
 * Runtime would have rejected the session if any node still needed the CPU EP.
 *
 * This class only uses `java.*` + ONNX Runtime APIs so it can run in plain JVM unit tests.
 */
class RifeEngine(
    modelPath: String,
    intraOpThreads: Int = 4,
    private val useNnapi: Boolean = false,
    private val useXnnpack: Boolean = false,
    /** QNN HTP session options, or null to stay on the CPU. */
    private val qnn: QnnConfig? = null,
    /** Run independent branches of the graph concurrently instead of one operator at a time. */
    private val parallelOps: Boolean = false,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    private val profileDir: File? = null,
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
    /** Symbolic names this graph accepts, with the value that was probed for each. */
    private val resolvedDims: List<Pair<String, Int>>
    val threads: Int

    /** Provider backing the session, for the job log and the benchmark report. */
    var providers: String = "CPU"
        private set

    /**
     * True when ONNX Runtime created the strict QNN HTP session. Because
     * `session.disable_cpu_ep_fallback=1` is set, creation succeeds only when QNN claimed the whole
     * graph; a partly offloaded graph fails instead of running nodes on the CPU.
     */
    var qnnSessionCreated: Boolean = false
        private set

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private var profileFile: String? = null

    private var concatBuffer: FloatArray = FloatArray(0)
    private var concatBufferView: FloatBuffer = FloatBuffer.wrap(concatBuffer)

    init {
        require(File(modelPath).isFile) { "Model not found: $modelPath" }
        threads = if (intraOpThreads > 0) intraOpThreads else defaultThreads()

        val accepted = ArrayList<Pair<String, Int>>(4)
        if (frameWidth > 0 && frameHeight > 0) {
            // The exported RIFE graph names its dynamic dimensions `height` and `width`.
            // Unknown names are rejected by ORT, so each candidate is tried independently.
            val probe = OrtSession.SessionOptions()
            try {
                for ((name, value) in listOf(
                    "height" to frameHeight, "width" to frameWidth,
                    "H" to frameHeight, "W" to frameWidth
                )) {
                    if (runCatching { probe.setSymbolicDimensionValue(name, value.toLong()) }.isSuccess) {
                        accepted += name to value
                    }
                }
            } finally {
                runCatching { probe.close() }
            }
        }
        resolvedDims = accepted
        staticShapes = accepted.isNotEmpty()

        if (qnn != null) {
            check(staticShapes) {
                "QNN HTP requires fixed height/width; refusing to fall back to the CPU"
            }
            session = try {
                env.createSession(modelPath, newOptions(qnn))
            } catch (t: Throwable) {
                throw IllegalStateException(
                    "QNN HTP session failed (strict, no CPU fallback): " +
                        (t.message ?: t.javaClass.simpleName),
                    t
                )
            }
            providers = "QNN(HTP strict)"
            qnnSessionCreated = true
        } else {
            session = env.createSession(modelPath, newOptions(null))
            providers = buildString {
                if (useNnapi) append("NNAPI,")
                append(if (useXnnpack) "XNNPACK" else "CPU")
            }
        }

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

        val shape = if (staticShapes) "${frameWidth}x$frameHeight (static)" else "dynamic"
        val inputsSummary = inputs.joinToString(", ") { (name, info) ->
            val dims = (info.info as? TensorInfo)?.shape?.joinToString("x") ?: "?"
            "$name[$dims]"
        }
        log(
            "Engine: ONNX Runtime ${runCatching { env.version }.getOrDefault("?")} · $modelName · $shape · " +
                "$threads threads · $providers · ${if (parallelOps) "parallel" else "sequential"} ops · " +
                "inputs: $inputsSummary"
        )
        if (qnnSessionCreated) {
            log("QNN: HTP session created (FP16, strict: QNN claimed the whole graph; no CPU EP fallback)")
        }
    }

    /** Builds a fresh option set; a failed session must not leave options in a half-initialised state. */
    private fun newOptions(qnnConfig: QnnConfig?): OrtSession.SessionOptions {
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        opts.setExecutionMode(
            if (parallelOps) OrtSession.SessionOptions.ExecutionMode.PARALLEL
            else OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL
        )
        opts.setIntraOpNumThreads(threads)
        opts.setInterOpNumThreads(1)

        if (staticShapes) {
            // The probe in init established which symbolic names this graph accepts; reuse the
            // exact values so the QNN provider sees a fully static graph.
            for ((name, value) in resolvedDims) {
                opts.setSymbolicDimensionValue(name, value.toLong())
            }
        }

        val dir = profileDir
        if (dir != null) {
            runCatching {
                dir.mkdirs()
                val file = File(dir, "ort-profile-${System.nanoTime()}.json")
                opts.enableProfiling(file.absolutePath)
                profileFile = file.absolutePath
            }.onFailure { log("ORT: profiling unavailable (${it.message})") }
        }

        if (qnnConfig != null) {
            // All-or-nothing: if any node would need the CPU EP, session creation fails instead
            // of hiding a partial offload behind a CPU fallback.
            opts.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            opts.addQnn(qnnConfig.providerOptions())
        } else {
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
        }
        return opts
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
         * Default worker threads: every core but two, capped at 6.
         *
         * Using *all* cores is measurably slower for a sustained job, even though it looks fine in a
         * short benchmark. The decoder, the encoder and the pixel conversion need cores of their own;
         * when the inference pool takes them all, the job thread pool spins against the codec threads
         * and the whole pipeline loses about a third of its throughput (measured: 1.20 → 0.90 source
         * fps on a Snapdragon 8 Elite when this went from `cores - 2` to `cores`).
         */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().let {
            if (it <= 2) 1 else (it - 2).coerceAtMost(6)
        }
    }
}
