package com.flowframes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowframes.android.job.SystemMonitor
import com.flowframes.android.job.formatClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live view of the benchmark: its log lines as they arrive (auto-scrolled), the elapsed time and
 * RAM / temperatures. Hiding it does not stop the benchmark; the log stays in the main screen.
 */
@Composable
fun BenchmarkLiveDialog(logs: List<String>, onHide: () -> Unit, onCopy: () -> Unit) {
    val start = logs.indexOfLast { it.contains("▶ Engine benchmark started") }
    val lines = if (start >= 0) logs.subList(start, logs.size) else logs.takeLast(200)
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    val startedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableStateOf(startedAt) }
    var snap by remember { mutableStateOf<SystemMonitor.Snapshot?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            snap = withContext(Dispatchers.IO) { runCatching { SystemMonitor.sample(context) }.getOrNull() }
            delay(1000)
        }
    }

    AlertDialog(
        onDismissRequest = onHide,
        title = {
            Column {
                Text("Benchmark running · ${formatClock(now - startedAt)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        text = {
            Column {
                snap?.let { s ->
                    fun t(v: Double?) = if (v == null) "n/a" else String.format(java.util.Locale.US, "%.0f°", v)
                    Text(
                        "RAM app ${s.appPssMb} MB (java ${s.javaMb}, gpu ${s.graphicsMb}) · " +
                            "CPU ${t(s.cpuC)} GPU ${t(s.gpuC)} NPU ${t(s.npuC)} batt ${t(s.batteryC)}",
                        fontSize = 10.sp,
                        color = Accent
                    )
                    Spacer(Modifier.height(6.dp))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 420.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(6.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.5.sp,
                            lineHeight = 12.sp,
                            color = when {
                                line.contains("failed") || line.contains("FATAL") || line.contains("crash") -> Danger
                                line.contains("→") -> Accent
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onHide) { Text("Hide") } },
        dismissButton = {
            Row { TextButton(onClick = onCopy) { Text("Copy log") } }
        }
    )
}
