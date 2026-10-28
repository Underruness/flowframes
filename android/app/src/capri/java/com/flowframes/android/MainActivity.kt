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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.flowframes.android.core.RifeEngine
import com.flowframes.android.job.AppGraph
import com.flowframes.android.job.EngineBenchmark
import com.flowframes.android.job.EngineKind
import com.flowframes.android.job.EngineWarmup
import com.flowframes.android.job.InterpolationService
import com.flowframes.android.job.JobItem
import com.flowframes.android.job.Settings
import com.flowframes.android.ui.CapriSettingsScreen
import com.flowframes.android.ui.CapriStudio
import com.flowframes.android.ui.CapriTheme
import com.flowframes.android.video.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Capri: the redesigned edition. Same service, same settings store and same pipeline as FlowFrames -
 * only the UI, the name, the icon and the application id differ, so the two install side by side.
 */
class MainActivity : ComponentActivity() {

    private enum class Tab { STUDIO, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CapriTheme {
                CapriApp()
            }
        }
    }

    @Composable
    private fun CapriApp() {
        val jobs by AppGraph.jobs.jobs.collectAsState()
        val logs by AppGraph.jobs.logs.collectAsState()
        val running by AppGraph.jobs.running.collectAsState()
        val settings by AppGraph.settings.state.collectAsState()
        val benchmark by AppGraph.jobs.benchmark.collectAsState()
        val benchmarkBest by AppGraph.jobs.benchmarkBest.collectAsState()
        val benchmarkRunning by AppGraph.jobs.benchmarkRunning.collectAsState()
        val warmup by EngineWarmup.status.collectAsState()
        val scope = rememberCoroutineScope()
        var tab by remember { mutableStateOf(Tab.STUDIO) }

        val copyLogs: () -> Unit = {
            val text = AppGraph.jobs.logText()
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Capri logs", text))
            AppGraph.jobs.addLog("Log copied to clipboard (${text.length} characters)")
        }

        val shareLogs: () -> Unit = {
            // A share intent over ~500 kB fails (binder limit): the most recent part is what matters.
            val text = AppGraph.jobs.logText().ifEmpty { "Capri: log is empty" }.takeLast(200_000)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Capri Android logs")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            runCatching { startActivity(Intent.createChooser(send, "Share logs")) }
        }

        val runBenchmark: () -> Unit = {
            if (!benchmarkRunning && !running) {
                AppGraph.jobs.benchmarkRunning.value = true
                AppGraph.jobs.addLog("Benchmark (Capri): starting")
                tab = Tab.STUDIO
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching {
                            EngineBenchmark.run(
                                context = this@MainActivity,
                                settings = AppGraph.settings.current,
                                report = { line -> AppGraph.jobs.addLog(line) }
                            )
                        }.getOrElse { t ->
                            AppGraph.jobs.addLog("Benchmark failed:")
                            AppGraph.jobs.addLines(com.flowframes.android.core.CrashLog.stackTrace(t))
                            EngineBenchmark.Report(
                                "=== CAPRI ENGINE BENCHMARK ===\n" +
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

        BackHandler(enabled = tab == Tab.SETTINGS) { tab = Tab.STUDIO }

        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color.Black) {
                    NavigationBarItem(
                        selected = tab == Tab.STUDIO,
                        onClick = { tab = Tab.STUDIO },
                        icon = { Icon(Icons.Filled.Movie, contentDescription = null) },
                        label = { Text("Studio", fontSize = 11.sp) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                        label = { Text("Settings", fontSize = 11.sp) }
                    )
                }
            }
        ) { padding ->
            Crossfade(
                targetState = tab,
                animationSpec = tween(220),
                label = "tab",
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) { current ->
                when (current) {
                    Tab.STUDIO -> CapriStudio(
                        jobs = jobs,
                        running = running,
                        logs = logs,
                        settings = settings,
                        warmup = warmup,
                        onAdd = { addLauncher.launch(arrayOf("video/*")) },
                        onStart = { InterpolationService.start(this@MainActivity) },
                        onCancel = { InterpolationService.cancel(this@MainActivity) },
                        onCopyLogs = copyLogs,
                        onShareLogs = shareLogs,
                        onRemove = { id -> AppGraph.jobs.remove(id) }
                    )

                    Tab.SETTINGS -> CapriSettingsScreen(
                        settings = settings,
                        onChange = { transform -> AppGraph.settings.update(transform) },
                        onPickOutputFolder = { treeLauncher.launch(null) },
                        onResetOutputFolder = { AppGraph.settings.clearOutputFolder() },
                        onRunBenchmark = runBenchmark,
                        benchmarkRunning = benchmarkRunning || running,
                        onCopyLogs = copyLogs,
                        onShareLogs = shareLogs
                    )
                }
            }
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
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                },
                confirmButton = {
                    Row {
                        benchmarkBest?.let { best ->
                            TextButton(onClick = {
                                AppGraph.settings.update { s -> settingsFromBenchmark(s, best) }
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
                        getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Capri benchmark", report))
                    }) { Text("Copy") }
                }
            )
        }
    }

    /** The benchmark winner, applied to the settings - same rules as the historical edition. */
    private fun settingsFromBenchmark(s: Settings, best: EngineBenchmark.Best): Settings {
        val gpu = best.engine == EngineKind.NCNN_GPU
        return s.copy(
            // The GPU winner keeps "automatic": the job re-checks Vulkan at every start and falls
            // back to the CPU on a broken driver.
            engine = if (gpu) EngineKind.NCNN_GPU else best.engine,
            ncnnModel = best.ncnnModel,
            fp16 = best.fp16,
            coopMatrix = best.coopMatrix,
            cpuAssist = gpu && best.cpuWorkers > 0,
            cpuAssistWorkers = if (best.cpuWorkers > 0) best.cpuWorkers else s.cpuAssistWorkers,
            cpuAssistThreads = if (best.cpuWorkers > 0) best.cpuThreads else s.cpuAssistThreads,
            // 0 keeps "automatic", so a later CPU change still adapts.
            threads = if (gpu || best.totalThreads == RifeEngine.defaultThreads()) 0 else best.totalThreads,
            workers = best.workers,
            useXnnpack = best.useXnnpack,
            parallelOps = best.parallelOps,
            useNnapi = false
        )
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
            AppGraph.jobs.addLog("Warning: $displayName: could not read metadata")
        }
        return JobItem(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = displayName,
            info = info
        )
    }
}
