package com.flowframes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.core.Naming
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.EngineKind
import com.flowframes.android.job.NcnnModel
import com.flowframes.android.job.OutputCodec
import com.flowframes.android.job.Settings
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    onBack: () -> Unit,
    onPickOutputFolder: () -> Unit,
    onResetOutputFolder: () -> Unit,
    onRunBenchmark: () -> Unit,
    benchmarkRunning: Boolean,
    onCopyLogs: () -> Unit,
    onShareLogs: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Display") {
                SwitchRow(
                    title = "Advanced view",
                    description = "Shows the CPU and ONNX Runtime engine options, the detailed RAM split and every " +
                        "temperature (CPU, GPU, NPU). Off: only the frame rate, resolution, GPU and output settings, " +
                        "phone RAM and battery temperature.",
                    checked = settings.advanced,
                    onCheckedChange = { v -> onChange { s -> s.copy(advanced = v) } }
                )
            }

            Section("Interpolation") {
                Text("Output rate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                val targetMode = settings.targetFps > 0.0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !targetMode,
                        onClick = { onChange { s -> s.copy(targetFps = 0.0) } },
                        label = { Text("Multiplier") }
                    )
                    FilterChip(
                        selected = targetMode,
                        onClick = { onChange { s -> s.copy(targetFps = if (s.targetFps > 0.0) s.targetFps else 60.0) } },
                        label = { Text("Exact fps") }
                    )
                }
                if (!targetMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4, 6, 8).forEach { f ->
                            FilterChip(
                                selected = settings.factor == f,
                                onClick = { onChange { s -> s.copy(factor = f) } },
                                label = { Text("x$f") }
                            )
                        }
                    }
                    Caption("Each step adds ${settings.factor - 1} interpolated frame(s) between two source frames.")
                } else {
                    var fpsText by remember(targetMode) { mutableStateOf(fpsLabel(settings.targetFps)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30.0, 48.0, 60.0, 90.0).forEach { f ->
                            FilterChip(
                                selected = settings.targetFps == f,
                                onClick = {
                                    fpsText = fpsLabel(f)
                                    onChange { s -> s.copy(targetFps = f) }
                                },
                                label = { Text(String.format(Locale.US, "%.0f", f)) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(120.0, 144.0, 165.0, 240.0).forEach { f ->
                            FilterChip(
                                selected = settings.targetFps == f,
                                onClick = {
                                    fpsText = fpsLabel(f)
                                    onChange { s -> s.copy(targetFps = f) }
                                },
                                label = { Text(String.format(Locale.US, "%.0f", f)) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = fpsText,
                        onValueChange = { v ->
                            fpsText = v
                            v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1.0..480.0 }?.let { fps ->
                                onChange { s -> s.copy(targetFps = fps) }
                            }
                        },
                        label = { Text("Output fps (1–480, e.g. 59.94)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Caption(
                        "The output gets exactly this frame rate whatever the source is: 24 → 60 fps interpolates " +
                            "frames at fractional positions (x2.5). Frames that fall on a source frame reuse it, " +
                            "so they cost nothing. The output fps limit below does not apply in this mode."
                    )
                }

                Spacer(Modifier.height(4.dp))
                Text("Processing resolution", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(854 to "480p", 1280 to "720p", 1920 to "1080p").forEach { (v, label) ->
                        FilterChip(
                            selected = settings.maxLongSide == v,
                            onClick = { onChange { s -> s.copy(maxLongSide = v) } },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2560 to "1440p", 3840 to "4K", 0 to "Source").forEach { (v, label) ->
                        FilterChip(
                            selected = settings.maxLongSide == v,
                            onClick = { onChange { s -> s.copy(maxLongSide = v) } },
                            label = { Text(label) }
                        )
                    }
                }
                Caption(
                    "Higher resolutions look better but cost time and RAM roughly with the pixel count (1440p = 1.8×, 4K = 4× the 1080p time). " +
                        "Each frame costs about (width × height) / 330 000 × the per-frame time shown by the benchmark."
                )
            }

            Section(if (settings.advanced) "AI engine" else "GPU") {
                if (settings.advanced) {
                Text("Runtime", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EngineKind.AUTO to "Auto", EngineKind.NCNN_GPU to "GPU").forEach { (k, label) ->
                        FilterChip(
                            selected = settings.engine == k,
                            onClick = { onChange { s -> s.copy(engine = k) } },
                            label = { Text(label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(EngineKind.NCNN_CPU to "CPU (ncnn)", EngineKind.ORT_CPU to "CPU (ONNX)").forEach { (k, label) ->
                        FilterChip(
                            selected = settings.engine == k,
                            onClick = { onChange { s -> s.copy(engine = k) } },
                            label = { Text(label) }
                        )
                    }
                }
                Caption(
                    "Auto runs RIFE on the GPU through Vulkan (ncnn, the engine of FlowFrames on PC) after a " +
                        "self-test at the job's resolution, and falls back to the CPU if the driver fails it. " +
                        "The old ONNX Runtime CPU engine stays available for comparison."
                )
                } else if (settings.engine != EngineKind.NCNN_GPU && settings.engine != EngineKind.AUTO) {
                    Caption("The engine is set to ${settings.engine.label} (advanced view).")
                }

                Text("ncnn model", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NcnnModel.values().forEach { m ->
                        FilterChip(
                            selected = settings.ncnnModel == m,
                            onClick = { onChange { s -> s.copy(ncnnModel = m) } },
                            label = { Text(m.label) }
                        )
                    }
                }
                Caption("RIFE 4.6 is about 20% faster on the GPU; 4.9 gives slightly different (often cleaner) results.")

                SwitchRow(
                    title = "fp16 arithmetic (ncnn, experimental)",
                    description = "Half-precision math in the network. Off by default: on a Snapdragon 8 Elite it " +
                        "hung the GPU. Results stay invisible (50 dB) where it works; a hang is now detected " +
                        "and the job falls back to the CPU.",
                    checked = settings.fp16,
                    onCheckedChange = { v -> onChange { s -> s.copy(fp16 = v) } }
                )
                SwitchRow(
                    title = "Cooperative matrix (experimental)",
                    description = "ncnn's tensor-core kernels for fp16 math (Adreno 8xx). The likely cause of the " +
                        "fp16 hang; only used together with fp16 arithmetic.",
                    checked = settings.coopMatrix,
                    onCheckedChange = { v -> onChange { s -> s.copy(coopMatrix = v) } }
                )
                ParallelInferences(settings, onChange)

                if (settings.advanced) {
                SwitchRow(
                    title = "CPU assist (hybrid GPU + CPU)",
                    description = "CPU workers take interpolations from the same queue as the GPU. Gains when the " +
                        "CPU is idle; can lose if the phone throttles. Run the benchmark to compare.",
                    checked = settings.cpuAssist,
                    onCheckedChange = { v -> onChange { s -> s.copy(cpuAssist = v) } }
                )
                if (settings.cpuAssist) {
                    Text("CPU assist workers", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to 4, 1 to 6, 2 to 3).forEach { (w, t) ->
                            FilterChip(
                                selected = settings.cpuAssistWorkers == w && settings.cpuAssistThreads == t,
                                onClick = { onChange { s -> s.copy(cpuAssistWorkers = w, cpuAssistThreads = t) } },
                                label = { Text("$w × ${t}T") }
                            )
                        }
                    }
                }

                Text("Model quality", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.quality == AiQuality.FAST,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.FAST) } },
                        label = { Text("Fast") }
                    )
                    FilterChip(
                        selected = settings.quality == AiQuality.HIGH,
                        onClick = { onChange { s -> s.copy(quality = AiQuality.HIGH) } },
                        label = { Text("High quality") }
                    )
                }
                Caption("\"High quality\" runs RIFE in ensemble mode: fewer artefacts, roughly twice as slow, CPU only (ONNX Runtime).")

                Text("ONNX Runtime options", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

                SwitchRow(
                    title = "XNNPACK provider",
                    description = "Off by default: RIFE's GridSample warping is not supported by XNNPACK, so it only adds " +
                        "layout conversions. The benchmark measured the plain CPU path ~8% faster on a Snapdragon 8 Elite.",
                    checked = settings.useXnnpack,
                    onCheckedChange = { v -> onChange { s -> s.copy(useXnnpack = v) } }
                )

                SwitchRow(
                    title = "Parallel operators",
                    description = "Runs independent branches of the network at the same time instead of one operator " +
                        "after another. Helps when the graph has parallel branches; measure it.",
                    checked = settings.parallelOps,
                    onCheckedChange = { v -> onChange { s -> s.copy(parallelOps = v) } }
                )

                SwitchRow(
                    title = "NNAPI acceleration",
                    description = "Not recommended: it was 6x slower than the CPU here (5.4 s/frame against 0.8 s), " +
                        "and most drivers fall back to the CPU without saying so. Only enable it if the benchmark wins.",
                    checked = settings.useNnapi,
                    onCheckedChange = { v -> onChange { s -> s.copy(useNnapi = v) } }
                )

                Text(
                    "CPU threads: " +
                        if (settings.threads == 0) "automatic (cores - 2, at most 6)" else "${settings.threads} (manual)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.threads.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(threads = v.toInt()) } },
                    valueRange = 0f..8f,
                    steps = 7
                )
                Caption(
                    "Automatic leaves two cores for the decoder, the encoder and the pixel conversion. Using every " +
                        "core measured 25-30% slower on a real job, even though it looks slightly faster in a short test."
                )

                }
            }

            Section("Output video") {
                Text("Codec", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputCodec.entries.forEach { codec ->
                        FilterChip(
                            selected = settings.codec == codec,
                            onClick = { onChange { s -> s.copy(codec = codec) } },
                            label = { Text(codec.label) }
                        )
                    }
                }

                Text("Bitrate", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small", "Balanced", "High").forEachIndexed { index, label ->
                        FilterChip(
                            selected = settings.bitratePreset == index,
                            onClick = { onChange { s -> s.copy(bitratePreset = index) } },
                            label = { Text(label) }
                        )
                    }
                }

                SwitchRow(
                    title = "Keep audio",
                    description = "The original audio track is copied as-is, without re-encoding.",
                    checked = settings.keepAudio,
                    onCheckedChange = { v -> onChange { s -> s.copy(keepAudio = v) } }
                )

                Spacer(Modifier.height(4.dp))
                Text("Filename pattern", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(
                    value = settings.namePattern,
                    onValueChange = { v -> onChange { s -> s.copy(namePattern = v) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Caption("Variables: {name} {factor} {fps} {codec} {date}")
                val preview = Naming.build(
                    settings.namePattern,
                    "clip.mp4",
                    settings.factor,
                    (30.0 * settings.factor),
                    settings.codec.label
                )
                Caption("Preview: $preview")

                Spacer(Modifier.height(4.dp))
                Text("Output folder", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.outputTreeUri?.let { "Custom folder" }
                            ?: "App folder (no permission required)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onPickOutputFolder) { Text("Choose", fontSize = 12.sp) }
                }
                if (settings.outputTreeUri != null) {
                    Row {
                        OutlinedButton(onClick = onResetOutputFolder) { Text("Reset", fontSize = 12.sp) }
                    }
                }
            }

            if (settings.advanced) Section("2D animation and finishing") {
                SwitchRow(
                    title = "Frame deduplication",
                    description = "Skips identical frames. Enable for 2D animation, leave off for live-action video.",
                    checked = settings.dedupe,
                    onCheckedChange = { v -> onChange { s -> s.copy(dedupe = v) } }
                )
                if (settings.dedupe) {
                    Text(
                        "Threshold: " + String.format(Locale.US, "%.3f", settings.dedupeThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.dedupeThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(dedupeThreshold = v.toDouble()) } },
                        valueRange = 0.0005f..0.02f
                    )
                }

                SwitchRow(
                    title = "Fix scene changes",
                    description = "Avoids generating a morphed transition frame in the middle of a cut.",
                    checked = settings.fixSceneChanges,
                    onCheckedChange = { v -> onChange { s -> s.copy(fixSceneChanges = v) } }
                )
                if (settings.fixSceneChanges) {
                    Text(
                        "Sensitivity: " + String.format(Locale.US, "%.2f", settings.sceneThreshold),
                        fontSize = 13.sp
                    )
                    Slider(
                        value = settings.sceneThreshold.toFloat(),
                        onValueChange = { v -> onChange { s -> s.copy(sceneThreshold = v.toDouble()) } },
                        valueRange = 0.1f..0.9f
                    )
                }

                SwitchRow(
                    title = "Perfect loop",
                    description = "Also interpolates from the last frame back to the first, for looping videos.",
                    checked = settings.loop,
                    onCheckedChange = { v -> onChange { s -> s.copy(loop = v) } }
                )

                Text(
                    "Output FPS limit: " +
                        if (settings.maxOutputFps <= 0.0) "none"
                        else String.format(Locale.US, "%.0f fps", settings.maxOutputFps),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Slider(
                    value = settings.maxOutputFps.toFloat(),
                    onValueChange = { v -> onChange { s -> s.copy(maxOutputFps = v.toDouble()) } },
                    valueRange = 0f..240f,
                    steps = 7
                )
            }

            Section("Diagnostics") {
                Text(
                    "The engine benchmark loads the model several ways (parallel sessions, thread count, " +
                        "execution provider, execution mode, static shapes), adds a sustained pass to expose " +
                        "throttling, and reports the real cost per frame on this device. It takes about two " +
                        "minutes and writes the result to the log, which stays copyable.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onRunBenchmark, enabled = !benchmarkRunning) {
                        Text(if (benchmarkRunning) "Benchmark running…" else "Run engine benchmark", fontSize = 12.sp)
                    }
                    if (benchmarkRunning) {
                        CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onCopyLogs) { Text("Copy logs", fontSize = 12.sp) }
                    OutlinedButton(onClick = onShareLogs) { Text("Share logs", fontSize = 12.sp) }
                }
                val ctx = LocalContext.current
                OutlinedButton(onClick = {
                    Thread {
                        com.flowframes.android.core.NcnnRifeEngine.clearShaderCaches()
                        com.flowframes.android.core.CrashLog.resetGpuSafety(ctx)
                        com.flowframes.android.core.NcnnRifeEngine.setPipelineDiskCache(true)
                    }.start()
                    Toast.makeText(ctx, "GPU shader cache cleared: the next GPU run rebuilds it", Toast.LENGTH_SHORT).show()
                }) { Text("Clear GPU shader cache", fontSize = 12.sp) }
                Caption(
                    "The GPU engine is built when the app starts, in the background, and the compiled shaders are " +
                        "kept on disk: the first launch pays for the compilation (up to 1.5 min with fp16 + coopmat) " +
                        "and every interpolation afterwards starts immediately. Clear the cache only if a GPU " +
                        "configuration misbehaves after a driver update."
                )
                Caption(
                    "Logs include device model, core count, thermal status, engine configuration, per-stage " +
                        "timings, inference latency percentiles with their drift, per-minute progress and a " +
                        "diagnosis line naming the bottleneck."
                )
            }

            Section("About") {
                Text(
                    "FlowFrames Android 1.6.6 — a rewrite of the FlowFrames (Windows) pipeline for Android: " +
                        "MediaCodec decoding, RIFE interpolation on the GPU (ncnn Vulkan) or the CPU, hardware encoding " +
                        "and MP4 muxing.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "RIFE 4.9 / 4.6 models (MIT) · ncnn (BSD-3) · everything runs locally, nothing is uploaded.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Accent)
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Caption(description)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun fpsLabel(fps: Double): String =
    if (fps % 1.0 == 0.0) String.format(Locale.US, "%.0f", fps)
    else String.format(Locale.US, "%.3f", fps).trimEnd('0').trimEnd('.')

/** Slider: 1..4 inferences at a time, two by default. */
@Composable
private fun ParallelInferences(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    val workers = settings.workers.coerceIn(Settings.MIN_WORKERS, Settings.MAX_WORKERS)
    Text(
        "Parallel inferences: $workers at a time",
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp
    )
    Slider(
        value = workers.toFloat(),
        onValueChange = { v -> onChange { s -> s.copy(workers = v.toInt()) } },
        valueRange = Settings.MIN_WORKERS.toFloat()..Settings.MAX_WORKERS.toFloat(),
        steps = Settings.MAX_WORKERS - Settings.MIN_WORKERS - 1
    )
    Caption(
        "How many inferences run at the same time: with 2, one computes while the other copies its frames " +
            "in and out, which keeps the GPU busy. Each one holds its own copy of the model, so more costs " +
            "RAM; 1 leaves the decoder and the encoder the whole machine."
    )
}
