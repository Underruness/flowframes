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
 * storage, fp32 math: the default). fp16 math is opt-in: it hung the benchmark on a Snapdragon
 * 8 Elite with ncnn 20261020, most likely in its Adreno cooperative-matrix path. The model directory must
 * hold `flownet.param` and `flownet.bin`.
 */
class NcnnRifeEngine(
    modelDir: String,
    /** Vulkan device index, or -1 for the CPU. */
    val gpuId: Int = 0,
    val cpuThreads: Int = 4,
    /** Canvas multiple the model needs: 32 for most v4 models, 64 for v4.25/v4.26. */
    val padding: Int = 32,
    val fp16: Boolean = false,
    /**
     * Cooperative-matrix kernels (only with [fp16]). ncnn 20261020 re-enabled them on Adreno and
     * they are the prime suspect of the fp16 hangs seen on a Snapdragon 8 Elite: off by default.
     */
    val coopMatrix: Boolean = false
) : FrameEngine {

    private var handle: Long
    @Volatile private var firstFrameDone = false
    val deviceName: String
    val modelName: String = File(modelDir).name

    init {
        check(isAvailable) { "native RIFE library unavailable: $loadError" }
        handle = if (gpuId >= 0) {
            CrashLog.gpuBuild("$modelName${if (fp16) " fp16" else ""}${if (fp16 && coopMatrix) " coopmat" else ""}") {
                nativeCreate(modelDir, gpuId, cpuThreads, padding, fp16, coopMatrix)
            }
        } else nativeCreate(modelDir, gpuId, cpuThreads, padding, fp16, coopMatrix)
        if (handle == 0L) throw IllegalStateException("ncnn: ${nativeLastError()}")
        deviceName = nativeDeviceName(handle)
    }

    override val label: String
        get() = "ncnn ${if (gpuId >= 0) "Vulkan GPU" else "CPU ×$cpuThreads"} · $deviceName · $modelName" +
            (if (fp16) " · fp16 math" else " · fp32 math") + (if (fp16 && coopMatrix) " · coopmat" else "")

    override val threadSafe: Boolean get() = true

    override fun interpolate(frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray) {
        val h = handle
        check(h != 0L) { "engine closed" }
        val ret = if (gpuId >= 0 && !firstFrameDone) {
            CrashLog.gpuBuild("$modelName first frame") { nativeInterpolate(h, frame0, frame1, width, height, t, out) }
                .also { firstFrameDone = true }
        } else nativeInterpolate(h, frame0, frame1, width, height, t, out)
        if (ret != 0) throw IllegalStateException("ncnn inference failed (code $ret) on $deviceName")
    }

    /** 8-bit path: the frames stay ARGB ints on the Java side, the float conversion is native. */
    override fun interpolateArgb(frame0: IntArray, frame1: IntArray, width: Int, height: Int, t: Float, out: IntArray) {
        val h = handle
        check(h != 0L) { "engine closed" }
        val ret = if (gpuId >= 0 && !firstFrameDone) {
            // The first frame builds the lazy utility pipelines and saves the cache.
            CrashLog.gpuBuild("$modelName first frame") { nativeInterpolateArgb(h, frame0, frame1, width, height, t, out) }
                .also { firstFrameDone = true }
        } else nativeInterpolateArgb(h, frame0, frame1, width, height, t, out)
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

        /** Driver and capability summary of a Vulkan device, for the logs. */
        fun gpuInfo(index: Int): String = if (isAvailable) runCatching { nativeGpuInfo(index) }.getOrDefault("") else ""

        fun gpuName(index: Int): String = if (isAvailable) runCatching { nativeGpuName(index) }.getOrDefault("") else ""

        /**
         * Directory of the persistent GPU shader caches (SPIR-V + driver pipelines). Without it,
         * every start compiles everything again: 98 s with fp16 + coopmat on an Adreno 840.
         */
        fun setShaderCacheDir(dir: File) {
            if (!isAvailable) return
            dir.mkdirs()
            runCatching { nativeSetShaderCacheDir(dir.absolutePath) }
        }

        /** Compile counts, time spent and cache hits since the process started. */
        fun shaderCacheStats(): String = if (isAvailable) runCatching { nativeShaderCacheStats() }.getOrDefault("") else ""

        fun clearShaderCaches() {
            if (isAvailable) runCatching { nativeClearShaderCaches() }
        }

        /** Native crash reports (signal, faulting library, backtrace, native stage) go to [file]. */
        fun installCrashHandler(file: File) {
            if (isAvailable) runCatching { nativeInstallCrashHandler(file.absolutePath) }
        }

        /** Driver pipeline cache on disk; the app turns it off after a crash during a GPU build. */
        fun setPipelineDiskCache(enabled: Boolean) {
            if (isAvailable) runCatching { nativeSetPipelineDiskCache(enabled) }
        }

        /** What native code is doing right now (model load, pipeline build, inference…). */
        fun nativeStageNow(): String = if (isAvailable) runCatching { nativeStage() }.getOrDefault("") else ""

        @JvmStatic private external fun nativeStage(): String
        @JvmStatic private external fun nativeInstallCrashHandler(path: String)
        @JvmStatic private external fun nativeSetPipelineDiskCache(enabled: Boolean)
        @JvmStatic private external fun nativeGpuCount(): Int
        @JvmStatic private external fun nativeGpuName(index: Int): String
        @JvmStatic private external fun nativeGpuInfo(index: Int): String
        @JvmStatic private external fun nativeLastError(): String
        @JvmStatic private external fun nativeCreate(
            modelDir: String, gpuId: Int, threads: Int, padding: Int, fp16: Boolean, coopMatrix: Boolean
        ): Long
        @JvmStatic private external fun nativeDeviceName(handle: Long): String
        @JvmStatic private external fun nativeInterpolate(
            handle: Long, frame0: FloatArray, frame1: FloatArray, width: Int, height: Int, t: Float, out: FloatArray
        ): Int
        @JvmStatic private external fun nativeInterpolateArgb(
            handle: Long, frame0: IntArray, frame1: IntArray, width: Int, height: Int, t: Float, out: IntArray
        ): Int
        @JvmStatic private external fun nativeSetShaderCacheDir(dir: String)
        @JvmStatic private external fun nativeShaderCacheStats(): String
        @JvmStatic private external fun nativeClearShaderCaches()
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
