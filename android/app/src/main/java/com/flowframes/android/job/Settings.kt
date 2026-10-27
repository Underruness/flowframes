package com.flowframes.android.job

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AiQuality { FAST, HIGH }

/** Which runtime computes RIFE. */
enum class EngineKind(val label: String) {
    /** ncnn on the GPU when Vulkan works (self-tested at start), else ncnn on the CPU. */
    AUTO("Automatic"),
    NCNN_GPU("GPU (ncnn Vulkan)"),
    NCNN_CPU("CPU (ncnn)"),
    ORT_CPU("CPU (ONNX Runtime)")
}

/** RIFE weights for the ncnn engine (ONNX Runtime always uses its bundled 4.9). */
enum class NcnnModel(val dir: String, val label: String, val padding: Int) {
    V4_9("rife-v4.9", "RIFE 4.9", 32),
    V4_6("rife-v4.6", "RIFE 4.6 (faster)", 32)
}
enum class OutputCodec(val mime: String, val label: String) {
    H264("video/avc", "H.264"),
    HEVC("video/hevc", "HEVC (H.265)")
}

/** All user settings, mirroring FlowFrames' configuration options. */
data class Settings(
    /** Frame multiplier: 2x, 3x, 4x, 8x ... */
    val factor: Int = 2,
    /** FAST = RIFE without ensemble (about twice as fast), HIGH = ensemble for better quality. */
    val quality: AiQuality = AiQuality.FAST,
    /** Long side cap for interpolation/encoding; 0 keeps the source resolution. */
    val maxLongSide: Int = 1280,
    val keepAudio: Boolean = true,
    val dedupe: Boolean = false,
    val dedupeThreshold: Double = 0.0025,
    val fixSceneChanges: Boolean = true,
    val sceneThreshold: Double = 0.35,
    val loop: Boolean = false,
    val codec: OutputCodec = OutputCodec.H264,
    /** 0 = small file, 1 = balanced, 2 = high bitrate. */
    val bitratePreset: Int = 1,
    val namePattern: String = "{name}_{factor}x_{fps}fps",
    /** 0 disables the output FPS limit. */
    val maxOutputFps: Double = 0.0,
    val useNnapi: Boolean = false,
    /**
     * XNNPACK execution provider. Measured on a Snapdragon 8 Elite, CPU (MLAS) won by ~8 %: this
     * graph's GridSample/Div/Transpose operators are not covered by XNNPACK, so it only pays for
     * layout conversions. The in-app benchmark is the arbiter for any other device.
     */
    val useXnnpack: Boolean = false,
    /** Run independent graph branches concurrently (ONNX Runtime PARALLEL execution mode). */
    val parallelOps: Boolean = false,
    /** 0 = automatic. */
    val threads: Int = 0,
    /**
     * Inferences computed at the same time. One inference cannot fill eight cores, so several run
     * in parallel: 0 = automatic (see [com.flowframes.android.core.InferencePool]).
     */
    val workers: Int = 0,
    val outputTreeUri: String? = null,
    /** Runtime; AUTO picks the GPU when the phone's Vulkan driver passes the start-up self-test. */
    val engine: EngineKind = EngineKind.AUTO,
    val ncnnModel: NcnnModel = NcnnModel.V4_9,
    /**
     * ncnn fp16 arithmetic. Off by default: on a Snapdragon 8 Elite it failed (benchmark stuck), the
     * suspect being ncnn's Adreno cooperative-matrix kernels, now disabled separately ([coopMatrix]).
     * fp16 *storage* stays on, which is what the 6 fps measurement used.
     */
    val fp16: Boolean = false,
    /** Cooperative-matrix kernels for fp16 math; experimental on Adreno. */
    val coopMatrix: Boolean = false,
    /**
     * Hybrid mode: while the GPU computes most phases, CPU workers (ncnn, same model) take some
     * as well. Every worker pulls the next phase from one queue, so the split follows the speeds.
     */
    val cpuAssist: Boolean = false,
    /** CPU workers in hybrid mode, and the threads each one gets. */
    val cpuAssistWorkers: Int = 1,
    val cpuAssistThreads: Int = 4
) {
    fun planOptions(): com.flowframes.android.core.InterpPlan.Options = com.flowframes.android.core.InterpPlan.Options(
        factor = factor,
        loop = loop,
        dedupe = dedupe,
        dedupeThreshold = dedupeThreshold,
        fixSceneChanges = fixSceneChanges,
        sceneThreshold = sceneThreshold,
        maxOutputFps = maxOutputFps
    )
}

/** Persists [Settings] in SharedPreferences and exposes them as a StateFlow for Compose. */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("flowframes_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())

    val state: StateFlow<Settings> get() = _state

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    fun clearOutputFolder() = update { it.copy(outputTreeUri = null) }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            factor = prefs.getInt("factor", d.factor).coerceIn(1, 32),
            quality = runCatching { AiQuality.valueOf(prefs.getString("quality", d.quality.name)!!) }
                .getOrDefault(d.quality),
            maxLongSide = prefs.getInt("maxLongSide", d.maxLongSide),
            keepAudio = prefs.getBoolean("keepAudio", d.keepAudio),
            dedupe = prefs.getBoolean("dedupe", d.dedupe),
            dedupeThreshold = prefs.getFloat("dedupeThreshold", d.dedupeThreshold.toFloat()).toDouble(),
            fixSceneChanges = prefs.getBoolean("fixSceneChanges", d.fixSceneChanges),
            sceneThreshold = prefs.getFloat("sceneThreshold", d.sceneThreshold.toFloat()).toDouble(),
            loop = prefs.getBoolean("loop", d.loop),
            codec = runCatching { OutputCodec.valueOf(prefs.getString("codec", d.codec.name)!!) }
                .getOrDefault(d.codec),
            bitratePreset = prefs.getInt("bitratePreset", d.bitratePreset).coerceIn(0, 2),
            namePattern = prefs.getString("namePattern", d.namePattern) ?: d.namePattern,
            maxOutputFps = prefs.getFloat("maxOutputFps", d.maxOutputFps.toFloat()).toDouble(),
            useNnapi = prefs.getBoolean("useNnapi", d.useNnapi),
            useXnnpack = prefs.getBoolean("useXnnpack", d.useXnnpack),
            parallelOps = prefs.getBoolean("parallelOps", d.parallelOps),
            threads = prefs.getInt("threads", d.threads).coerceIn(0, 16),
            workers = prefs.getInt("workers", d.workers).coerceIn(0, 6),
            outputTreeUri = prefs.getString("outputTreeUri", d.outputTreeUri),
            engine = runCatching { EngineKind.valueOf(prefs.getString("engine", d.engine.name)!!) }
                .getOrDefault(d.engine),
            ncnnModel = runCatching { NcnnModel.valueOf(prefs.getString("ncnnModel", d.ncnnModel.name)!!) }
                .getOrDefault(d.ncnnModel),
            // New key: 1.5.0 saved fp16=true by default, and that default is what failed.
            fp16 = prefs.getBoolean("fp16Arith", d.fp16),
            coopMatrix = prefs.getBoolean("coopMatrix", d.coopMatrix),
            cpuAssist = prefs.getBoolean("cpuAssist", d.cpuAssist),
            cpuAssistWorkers = prefs.getInt("cpuAssistWorkers", d.cpuAssistWorkers),
            cpuAssistThreads = prefs.getInt("cpuAssistThreads", d.cpuAssistThreads)
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putInt("factor", s.factor)
            .putString("quality", s.quality.name)
            .putInt("maxLongSide", s.maxLongSide)
            .putBoolean("keepAudio", s.keepAudio)
            .putBoolean("dedupe", s.dedupe)
            .putFloat("dedupeThreshold", s.dedupeThreshold.toFloat())
            .putBoolean("fixSceneChanges", s.fixSceneChanges)
            .putFloat("sceneThreshold", s.sceneThreshold.toFloat())
            .putBoolean("loop", s.loop)
            .putString("codec", s.codec.name)
            .putInt("bitratePreset", s.bitratePreset)
            .putString("namePattern", s.namePattern)
            .putFloat("maxOutputFps", s.maxOutputFps.toFloat())
            .putBoolean("useNnapi", s.useNnapi)
            .putBoolean("useXnnpack", s.useXnnpack)
            .putBoolean("parallelOps", s.parallelOps)
            .putInt("threads", s.threads)
            .putInt("workers", s.workers)
            .putString("outputTreeUri", s.outputTreeUri)
            .putString("engine", s.engine.name)
            .putString("ncnnModel", s.ncnnModel.name)
            .putBoolean("fp16Arith", s.fp16)
            .putBoolean("coopMatrix", s.coopMatrix)
            .putBoolean("cpuAssist", s.cpuAssist)
            .putInt("cpuAssistWorkers", s.cpuAssistWorkers)
            .putInt("cpuAssistThreads", s.cpuAssistThreads)
            .apply()
    }
}
