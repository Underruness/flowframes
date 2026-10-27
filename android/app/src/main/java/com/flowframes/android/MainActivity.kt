package com.flowframes.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flowframes.android.job.AppGraph
import com.flowframes.android.core.RifeEngine
import com.flowframes.android.job.EngineBenchmark
import com.flowframes.android.job.EngineKind
import com.flowframes.android.job.InterpolationService
import com.flowframes.android.job.JobItem
import com.flowframes.android.ui.FlowFramesTheme
import com.flowframes.android.ui.MainScreen
import com.flowframes.android.ui.SettingsScreen
import com.flowframes.android.video.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlowFramesTheme {
                App()
            }
        }
    }

    @Composable
    private fun App() {
        val jobs by AppGraph.jobs.jobs.collectAsState()
        val logs by AppGraph.jobs.logs.collectAsState()
        val running by AppGraph.jobs.running.collectAsState()
        val settings by AppGraph.settings.state.collectAsState()
        val benchmark by AppGraph.jobs.benchmark.collectAsState()
        val benchmarkBest by AppGraph.jobs.benchmarkBest.collectAsState()
        val benchmarkRunning by AppGraph.jobs.benchmarkRunning.collectAsState()
        val scope = rememberCoroutineScope()
        var showSettings by remember { mutableStateOf(false) }

        val copyLogs: () -> Unit = {
            val text = AppGraph.jobs.logText()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("FlowFrames logs", text))
            AppGraph.jobs.addLog("Log copied to clipboard (${text.length} characters)")
        }

        val shareLogs: () -> Unit = {
            val text = AppGraph.jobs.logText().ifEmpty { "FlowFrames: log is empty" }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "FlowFrames Android logs")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching { startActivity(Intent.createChooser(send, "Share logs")) }
        }

        val runBenchmark: () -> Unit = {
            if (!benchmarkRunning && !running) {
                AppGraph.jobs.benchmarkRunning.value = true
                AppGraph.jobs.addLog("▶ Engine benchmark started")
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            EngineBenchmark.run(
                                context = this@MainActivity,
                                settings = AppGraph.settings.current,
                                report = { line -> AppGraph.jobs.addLog(line) }
                            )
                        }.getOrElse { t ->
                            EngineBenchmark.Report(
                                "=== FLOWFRAMES ENGINE BENCHMARK ===\n" +
                                    "Benchmark failed: ${t.message ?: t.javaClass.simpleName}",
                                null
                            )
                        }
                    }
                    AppGraph.jobs.setBenchmark(result.text, result.best)
                    AppGraph.jobs.benchmarkRunning.value = false
                }
            }
        }

        val addLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val items = withContext(Dispatchers.IO) { uris.map { buildJobItem(it) } }
                    AppGraph.jobs.add(items)
                }
            }
        }

        val treeLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                AppGraph.settings.update { it.copy(outputTreeUri = uri.toString()) }
                AppGraph.jobs.addLog("Output folder: $uri")
            }
        }

        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        BackHandler(enabled = showSettings) { showSettings = false }

        if (showSettings) {
            SettingsScreen(
                settings = settings,
                onChange = { transform -> AppGraph.settings.update(transform) },
                onBack = { showSettings = false },
                onPickOutputFolder = { treeLauncher.launch(null) },
                onResetOutputFolder = { AppGraph.settings.clearOutputFolder() },
                onRunBenchmark = runBenchmark,
                benchmarkRunning = benchmarkRunning || running,
                onCopyLogs = copyLogs,
                onShareLogs = shareLogs
            )
        } else {
            MainScreen(
                jobs = jobs,
                running = running,
                logs = logs,
                settings = settings,
                onAdd = { addLauncher.launch(arrayOf("video/*")) },
                onStart = { InterpolationService.start(this@MainActivity) },
                onCancel = { InterpolationService.cancel(this@MainActivity) },
                onOpenSettings = { showSettings = true },
                onClearFinished = { AppGraph.jobs.clearFinished() },
                onCopyLogs = copyLogs,
                onShareLogs = shareLogs,
                onRemove = { id -> AppGraph.jobs.remove(id) }
            )
        }

        benchmark?.let { report ->
            AlertDialog(
                onDismissRequest = { AppGraph.jobs.setBenchmark(null, null) },
                title = { Text("Engine benchmark", fontSize = 16.sp) },
                text = {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Row {
                        benchmarkBest?.let { best ->
                            TextButton(onClick = {
                                AppGraph.settings.update { s ->
                                    val gpu = best.engine == EngineKind.NCNN_GPU
                                    s.copy(
                                        // The GPU winner keeps "automatic": the job re-checks Vulkan at
                                        // every start and falls back to the CPU on a broken driver.
                                        engine = if (gpu) EngineKind.AUTO else best.engine,
                                        ncnnModel = best.ncnnModel,
                                        fp16 = best.fp16,
                                        coopMatrix = best.coopMatrix,
                                        cpuAssist = gpu && best.cpuWorkers > 0,
                                        cpuAssistWorkers = if (best.cpuWorkers > 0) best.cpuWorkers else s.cpuAssistWorkers,
                                        cpuAssistThreads = if (best.cpuWorkers > 0) best.cpuThreads else s.cpuAssistThreads,
                                        // 0 keeps "automatic", so a later CPU change still adapts.
                                        threads = if (gpu || best.totalThreads == RifeEngine.defaultThreads()) 0 else best.totalThreads,
                                        workers = if (gpu && best.workers == 2) 0 else best.workers,
                                        useXnnpack = best.useXnnpack,
                                        parallelOps = best.parallelOps,
                                        useNnapi = false
                                    )
                                }
                                AppGraph.jobs.addLog(
                                    "Applied benchmark winner: ${best.label} (${best.msPerFrame.toInt()} ms/frame)"
                                )
                                AppGraph.jobs.setBenchmark(null, null)
                            }) { Text("Apply best") }
                        }
                        TextButton(onClick = { AppGraph.jobs.setBenchmark(null, null) }) { Text("Close") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("FlowFrames benchmark", report))
                    }) { Text("Copy") }
                }
            )
        }
    }

    private fun buildJobItem(uri: Uri): JobItem {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video.mp4"

        val info = VideoInfo.read(this, uri)
        if (info == null) {
            AppGraph.jobs.addLog("⚠ $displayName: could not read metadata")
        }
        return JobItem(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = displayName,
            info = info
        )
    }
}
