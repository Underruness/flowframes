package com.flowframes.android

import android.Manifest
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.flowframes.android.job.AppGraph
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
        val scope = rememberCoroutineScope()
        var showSettings by remember { mutableStateOf(false) }

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
                AppGraph.jobs.addLog("Dossier de sortie: $uri")
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
                onResetOutputFolder = { AppGraph.settings.clearOutputFolder() }
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
                onRemove = { id -> AppGraph.jobs.remove(id) }
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
            AppGraph.jobs.addLog("⚠ $displayName : impossible de lire les métadonnées")
        }
        return JobItem(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = displayName,
            info = info
        )
    }
}
