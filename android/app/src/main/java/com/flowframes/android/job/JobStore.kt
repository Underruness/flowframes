package com.flowframes.android.job

import android.net.Uri
import com.flowframes.android.video.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow

enum class JobStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class JobItem(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val info: VideoInfo? = null,
    val status: JobStatus = JobStatus.QUEUED,
    val progress: Float = 0f,
    val detail: String = "",
    val error: String? = null,
    val outputName: String? = null,
    val outputLabel: String? = null
)

/**
 * In-process state shared by the UI and the foreground service (single process, no binder needed).
 */
class JobStore {

    val jobs = MutableStateFlow<List<JobItem>>(emptyList())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    val benchmark = MutableStateFlow<String?>(null)
    val benchmarkBest = MutableStateFlow<EngineBenchmark.Best?>(null)
    val benchmarkRunning = MutableStateFlow(false)

    @Volatile
    var cancelRequested: Boolean = false

    fun add(items: List<JobItem>) {
        jobs.value = jobs.value + items
    }

    fun addLog(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val next = logs.value + "[$stamp] $line"
        logs.value = if (next.size > 1000) next.takeLast(1000) else next
    }

    /** Adds a multi-line block (a benchmark report, a stack trace) one line at a time. */
    fun addLines(text: String) {
        text.lines().forEach { addLog(it) }
    }

    /** Full log as plain text, for the clipboard and the share sheet. */
    fun logText(): String = logs.value.joinToString("\n")

    fun update(id: String, transform: (JobItem) -> JobItem) {
        jobs.value = jobs.value.map { if (it.id == id) transform(it) else it }
    }

    fun remove(id: String) {
        jobs.value = jobs.value.filterNot { it.id == id }
    }

    fun clearFinished() {
        jobs.value = jobs.value.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
    }

    fun clearAll() {
        jobs.value = emptyList()
        logs.value = emptyList()
    }

    fun setBenchmark(text: String?, best: EngineBenchmark.Best? = null) {
        benchmark.value = text
        benchmarkBest.value = best
    }

    fun requestCancel() {
        cancelRequested = true
    }

    fun resetCancel() {
        cancelRequested = false
    }

    fun nextQueued(): JobItem? = jobs.value.firstOrNull { it.status == JobStatus.QUEUED }

    /** Jobs that still need processing. */
    fun pendingCount(): Int = jobs.value.count { it.status == JobStatus.QUEUED || it.status == JobStatus.RUNNING }
}

/** Tiny service locator, initialised by [com.flowframes.android.FlowFramesApp]. */
object AppGraph {
    lateinit var settings: SettingsStore
        private set
    val jobs = JobStore()

    fun init(settings: SettingsStore) {
        this.settings = settings
    }
}
