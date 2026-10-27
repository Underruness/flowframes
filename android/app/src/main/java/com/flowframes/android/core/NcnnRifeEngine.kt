package com.flowframes.android.core

import java.io.File

/**
 * RIFE v4 on ncnn: Vulkan compute on the phone's GPU (Adreno/Mali/Xclipse), or ncnn's ARM CPU
 * kernels when [gpuId] is -1.
 *
 * This is the implementation the desktop FlowFrames uses (rife-ncnn-vulkan), with the warp shader
 * kept in fp32 for its sampling coordinates. On the GPU the whole graph, `GridSample`-style warps
 * included, runs in compute shaders — the operator that ONNX Runtime can only run on the CPU and
 * that cost ~43 % of every inference there.
 *
 * Output matches the ONNX Runtime engine on the same RIFE 4.9 weights to ~60 dB PSNR (fp16
 * storage) and ~50 dB with fp16 arithmetic, i.e. invisible differences; the model directory must
 * hold `flownet.param` and `flownet.bin`.
 */
class NcnnRifeEngine(
    modelDir: String,
    /** Vulkan device index, or -1 for the CPU. */
    val gpuId: Int = 0,
    val cpuThreads: Int = 4,
    /** Canvas multiple the model needs: 32 for most v4 models, 64 for v4.25/v4.26. */
    val padding: Int = 32,
    val fp16: Boolean = true
) : FrameEngine {

    private var handle: Long
    val deviceName: String
    val modelName: String = File(modelDir).name

    init {
        check(isAvailable) { "native RIFE library unavailable: $loadError" }
        handle = nativeCreate(modelDir, gpuId, cpuThreads, padding, fp16)
        if (handle == 0L) throw IllegalStateException("ncnn: ${nativeLastError()}")
        deviceName = nativeDeviceName(handle)
    }

    override val label: String
        get() = "ncnn ${if (gpuId >= 0) "Vulkan GPU" else "CPU ×$cpuThreads"} · $deviceName · $modelName" +
            (if (fp16) " · fp16" else " · fp32")

    override val threadSafe: Boolean get() = true

    override fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray) {
        val h = handle
        check(h != 0L) { "engine closed" }
        val ret = nativeInterpolate(h, frame0, frame1, width, height, t, out)
        if (ret != 0) throw IllegalStateException("ncnn inference failed (code $ret) on $deviceName")
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    companion object {
        @Volatile
        private var loadError: String? = null

        /** True when libflowframes_rife.so loaded (it is absent from plain JVM unit tests). */
        val isAvailable: Boolean = runCatching { System.loadLibrary("flowframes_rife") }
            .onFailure { loadError = it.message ?: it.javaClass.simpleName }
            .isSuccess

        /** Vulkan devices ncnn can use; 0 when the phone has no usable Vulkan driver. */
        fun gpuCount(): Int = if (isAvailable) runCatching { nativeGpuCount() }.getOrDefault(0) else 0

        fun gpuName(index: Int): String = if (isAvailable) runCatching { nativeGpuName(index) }.getOrDefault("") else ""

        @JvmStatic private external fun nativeGpuCount(): Int
        @JvmStatic private external fun nativeGpuName(index: Int): String
        @JvmStatic private external fun nativeLastError(): String
        @JvmStatic private external fun nativeCreate(modelDir: String, gpuId: Int, threads: Int, padding: Int, fp16: Boolean): Long
        @JvmStatic private external fun nativeDeviceName(handle: Long): String
        @JvmStatic private external fun nativeInterpolate(
            handle: Long, frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray
        ): Int
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
