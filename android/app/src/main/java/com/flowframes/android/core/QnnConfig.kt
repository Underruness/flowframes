package com.flowframes.android.core

/**
 * Options for ONNX Runtime's QNN execution provider.
 *
 * The HTP backend can offload the graph to the Hexagon NPU. This integration always asks for FP16
 * math: RIFE loses too much quality at int8. The HTP also requires fixed input shapes; [RifeEngine]
 * only enables QNN when it could pin `height`/`width`.
 *
 * [QnnConfig] only describes the provider. [RifeEngine] additionally sets
 * `session.disable_cpu_ep_fallback=1`, so a session is either fully claimed by QNN or creation
 * fails; unsupported nodes never fall back to the CPU.
 *
 * [backendPath] is the absolute path to `libQnnHtp.so` (usually the app's native library
 * directory). When empty, ONNX Runtime loads the backend by name with `backend_type=htp`. The two
 * options are mutually exclusive.
 *
 * [profilingLevel] and [profilingFilePath] enable the QNN HTP profiler when both are set. The
 * backend writes a CSV with the operators it executed (plus a `_qnn.log` next to it for
 * `qnn-profile-viewer`). Profiling is meant for the benchmark: it adds overhead, so the timed QNN
 * configuration leaves it off.
 */
data class QnnConfig(
    val backendPath: String = "",
    val profilingLevel: String? = null,
    val profilingFilePath: String? = null
) {

    fun providerOptions(): Map<String, String> = buildMap {
        if (backendPath.isNotBlank()) {
            put("backend_path", backendPath.trim())
        } else {
            put("backend_type", "htp")
        }
        put("enable_htp_fp16_precision", "1")
        if (profilingLevel != null && profilingFilePath != null) {
            put("profiling_level", profilingLevel)
            put("profiling_file_path", profilingFilePath)
        }
    }
}
