package com.flowframes.android.core

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer

/**
 * RIFE frame-interpolation engine on top of ONNX Runtime.
 *
 * Input frames are planar RGB in [0,1] (CHW), the timestep is the interpolation phase.
 * The model is fully convolutional with dynamic H/W, so any resolution works
 * (the graph pads to a multiple of 32 internally and crops back).
 *
 * This class only uses `java.*` + ONNX Runtime APIs so it can run in plain JVM unit tests.
 */
class RifeEngine(
    modelPath: String,
    intraOpThreads: Int = 4,
    useNnapi: Boolean = false,
    useXnnpack: Boolean = true,
    private val log: (String) -> Unit = {}
) : AutoCloseable {

    /** Two separate frame inputs (`img0`/`img1`) instead of one 6-channel tensor. */
    private val splitFrames: Boolean
    private val firstFrameInput: String
    private val secondFrameInput: String
    private val timestepInput: String?
    private val timestepShape: LongArray

    val modelName: String = File(modelPath).name

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private var concatBuffer: FloatArray = FloatArray(0)
    private var concatBufferView: FloatBuffer = FloatBuffer.wrap(concatBuffer)

    init {
        require(File(modelPath).isFile) { "Modèle introuvable: $modelPath" }
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (intraOpThreads > 0) opts.setIntraOpNumThreads(intraOpThreads)
        opts.setInterOpNumThreads(1)
        if (useNnapi) {
            try {
                opts.addNnapi()
                log("ONNX Runtime: accélération NNAPI activée")
            } catch (t: Throwable) {
                log("NNAPI indisponible, repli sur le CPU: ${t.message}")
            }
        }
        if (useXnnpack) {
            try {
                opts.addXnnpack(emptyMap())
                log("ONNX Runtime: XNNPACK activé ($intraOpThreads threads)")
            } catch (t: Throwable) {
                log("XNNPACK indisponible: ${t.message}")
            }
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

        val summary = inputs.joinToString { (name, info) ->
            val shape = (info.info as? TensorInfo)?.shape?.joinToString("x") ?: "?"
            "$name[$shape]"
        }
        log("Modèle chargé: $modelName — entrées: $summary")
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
        require(frame0.size >= planeSize * 3 && frame1.size >= planeSize * 3) { "buffers too small" }
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
                    OnnxTensor.createTensor(env, arrayOf(t).toFloatArray())
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(t)), longArrayOf(1))
                }
                tensors.add(tsTensor)
                feeds[name] = tsTensor
            }

            session.run(feeds).use { result ->
                val value = result.get(0)
                val tensor = value as? OnnxTensor
                    ?: throw IllegalStateException("Sortie du modèle inattendue: ${value.javaClass.simpleName}")
                tensor.use {
                    val buf = it.floatBuffer
                    if (buf.remaining() < planeSize * 3) {
                        throw IllegalStateException("Sortie trop petite: ${buf.remaining()} < ${planeSize * 3}")
                    }
                    buf.get(out, 0, planeSize * 3)
                }
            }
        } finally {
            tensors.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Default worker threads: leave one or two cores for the decoder/encoder. */
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().let {
            if (it <= 2) 1 else (it - 2).coerceAtMost(6)
        }
    }
}
