package com.flowframes.android.core

/**
 * Options for ONNX Runtime's QNN execution provider.
 *
 * The HTP backend offloads the graph to the Hexagon NPU. This integration always asks for FP16
 * math: RIFE loses too much quality at int8. The HTP also requires fixed input shapes;
 * [RifeEngine] only enables QNN when it could pin `height`/`width`.
 *
 * [backendPath] is the absolute path to `libQnnHtp.so` (usually the app's native library
 * directory). When empty, ONNX Runtime loads the backend by name with `backend_type=htp`. The two
 * options are mutually exclusive.
 */
data class QnnConfig(val backendPath: String = "") {

    fun providerOptions(): Map<String, String> = buildMap {
        if (backendPath.isNotBlank()) {
            put("backend_path", backendPath.trim())
        } else {
            put("backend_type", "htp")
        }
        put("enable_htp_fp16_precision", "1")
    }
}
