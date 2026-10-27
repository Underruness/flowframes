package com.flowframes.android.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log memory that survives a crash.
 *
 * - Every log line is appended (and flushed) to `files/logs/session.log`; at the next start it
 *   becomes `previous-session.log`.
 * - A Java crash writes its stack trace there before the process dies.
 * - A native crash (ncnn, the Vulkan driver) is written by the native signal handler to
 *   `native-crash.txt`.
 * - At the next start, Android's own exit record (crash, native crash, low memory kill, ANR…),
 *   the native report and the last lines of the previous session are put at the top of the log.
 * - A GPU engine build in progress is marked on disk; if the app dies during one, the driver
 *   pipeline cache is turned off for the next runs (in case the driver chokes on its saved blob).
 */
object CrashLog {
    private const val MAX_BYTES = 4L * 1024 * 1024

    private var dir: File? = null
    private var out: FileOutputStream? = null
    private val lock = Any()
    private var gpuBuilds = 0

    /** Report on how the previous session ended, null when it ended normally. */
    @Volatile var previousSessionReport: String? = null
        private set

    /** True when the previous session died during a GPU build (pipeline disk cache now off). */
    @Volatile var gpuBuildCrashed: Boolean = false
        private set

    fun init(context: Context) {
        val logs = File(context.filesDir, "logs").apply { mkdirs() }
        dir = logs
        val session = File(logs, "session.log")
        val previous = File(logs, "previous-session.log")
        if (session.exists()) {
            previous.delete()
            session.renameTo(previous)
        }
        synchronized(lock) {
            out = runCatching { FileOutputStream(session, true) }.getOrNull()
        }

        val report = StringBuilder()
        val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
        exitReasons(context, prefs.getLong("lastExitTs", 0L)) { ts, text ->
            prefs.edit().putLong("lastExitTs", ts).apply()
            if (text.isNotBlank()) report.appendLine(text)
        }
        val native = File(logs, "native-crash.txt")
        if (native.exists()) {
            report.appendLine("--- native crash report ---")
            report.appendLine(runCatching { native.readText().trim() }.getOrDefault("(unreadable)"))
            native.renameTo(File(logs, "native-crash-previous.txt"))
        }
        val marker = File(logs, "gpu-build.marker")
        if (marker.exists()) {
            val what = runCatching { marker.readText().trim() }.getOrDefault("")
            report.appendLine(
                "The previous session died during a GPU engine build ($what). The driver pipeline " +
                    "cache on disk is turned off for safety (Settings → Clear GPU shader cache turns it back on)."
            )
            marker.delete()
            prefs.edit().putBoolean("pipelineDiskOff", true).apply()
        }
        gpuBuildCrashed = prefs.getBoolean("pipelineDiskOff", false)
        if (report.isNotEmpty() && previous.exists()) {
            report.appendLine("--- last lines of the previous session ---")
            runCatching { previous.readLines().takeLast(60).forEach { report.appendLine(it) } }
        }
        previousSessionReport = report.toString().trim().takeIf { it.isNotEmpty() }
        previousSessionReport?.let { appendHistory(logs, it) }

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val rt = Runtime.getRuntime()
                write(
                    "FATAL Java crash in thread \"${thread.name}\": $sw" +
                        "heap ${(rt.totalMemory() - rt.freeMemory()) / 1048576}/${rt.maxMemory() / 1048576} MB"
                )
            }
            previousHandler?.uncaughtException(thread, error)
        }
    }

    /** Re-enables the driver pipeline cache (after the user cleared the GPU caches). */
    fun resetGpuSafety(context: Context) {
        context.getSharedPreferences("crashlog", Context.MODE_PRIVATE).edit().putBoolean("pipelineDiskOff", false).apply()
        gpuBuildCrashed = false
    }

    /** Appends one already formatted line; flushed at once so a crash cannot lose it. */
    fun write(line: String) {
        synchronized(lock) {
            val o = out ?: return
            runCatching {
                o.write((line + "\n").toByteArray())
                o.flush()
                if (o.channel.size() > MAX_BYTES) rotate()
            }
        }
    }

    private fun rotate() {
        val d = dir ?: return
        runCatching { out?.close() }
        val session = File(d, "session.log")
        val old = File(d, "session.old.log")
        old.delete()
        session.renameTo(old)
        out = runCatching { FileOutputStream(session, true) }.getOrNull()
    }

    /** Marks a GPU engine build (pipeline compilation) while [block] runs. */
    fun <T> gpuBuild(what: String, block: () -> T): T {
        val marker = dir?.let { File(it, "gpu-build.marker") }
        synchronized(lock) {
            if (gpuBuilds++ == 0) runCatching { marker?.writeText(what) }
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                if (--gpuBuilds == 0) runCatching { marker?.delete() }
            }
        }
    }

    fun nativeReportFile(): File? = dir?.let { File(it, "native-crash.txt") }

    private fun appendHistory(logs: File, text: String) {
        runCatching {
            val f = File(logs, "crash-history.log")
            if (f.length() > MAX_BYTES) f.delete()
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("===== $stamp =====\n$text\n\n")
        }
    }

    private fun exitReasons(context: Context, since: Long, onReport: (Long, String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull() ?: return
        val last = exits.filter { it.timestamp > since }.maxByOrNull { it.timestamp } ?: return
        val reason = reasonName(last.reason)
        val normal = last.reason == ApplicationExitInfo.REASON_EXIT_SELF ||
            last.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
            last.reason == ApplicationExitInfo.REASON_USER_STOPPED ||
            last.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(last.timestamp))
        val sb = StringBuilder()
        sb.appendLine(
            "Previous session ended at $stamp: $reason" +
                (last.description?.let { " — $it" } ?: "") +
                " · status ${last.status} · RAM pss ${last.pss / 1024} MB rss ${last.rss / 1024} MB"
        )
        if (!normal) {
            val trace = runCatching { last.traceInputStream?.use { it.readBytes() } }.getOrNull()
            if (trace != null && trace.isNotEmpty()) {
                if (last.reason == ApplicationExitInfo.REASON_ANR) {
                    sb.appendLine("--- ANR trace (first lines) ---")
                    String(trace).lines().take(80).forEach { sb.appendLine(it) }
                } else {
                    // Native crash: a tombstone protobuf; its text fields (signal, libraries,
                    // function names, abort message) are readable as plain strings.
                    sb.appendLine("--- tombstone (text fields) ---")
                    printableStrings(trace).take(120).forEach { sb.appendLine(it) }
                }
            }
        }
        onReport(last.timestamp, if (normal) "" else sb.toString().trim())
    }

    private fun printableStrings(bytes: ByteArray, min: Int = 6): List<String> {
        val result = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= min) {
                val s = cur.toString()
                if (s.contains(".so") || s.contains("::") || s.contains("SIG") || s.contains("abort") ||
                    s.contains("Abort") || s.contains("vk") || s.contains("ncnn") || s.contains("Thread") ||
                    s.contains("Fatal") || s.contains("signal") || s.contains("Cause") || result.size < 10
                ) result += s
            }
            cur.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) cur.append(c.toChar()) else flush()
        }
        flush()
        return result.distinct()
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed by Android: low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed by Android: excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_FREEZER -> "frozen by Android"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "normal exit"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $r"
    }

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()
}
