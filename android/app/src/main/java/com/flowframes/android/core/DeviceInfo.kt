package com.flowframes.android.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.Locale

/**
 * Device and runtime facts that change how the engine behaves. Collected for the diagnostic
 * report: without them a slow run cannot be compared with a fast one.
 */
object DeviceInfo {

    fun lines(context: Context): List<String> {
        val out = ArrayList<String>(12)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        out += "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        out += "CPU: ${socName()} · ${Runtime.getRuntime().availableProcessors()} cores · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
        out += "Cores online: ${onlineCores()} · max ${maxFreqMhz()} MHz"
        out += "Memory: ${memoryClassMb(am)} MB heap · ${totalRamMb(am)} MB RAM · low-RAM=${am?.isLowRamDevice ?: false}"
        out += "Thermal status: ${thermalStatus(pm)}"
        out += "App: ${versionName(context)} (${versionCode(context)})"
        return out
    }

    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun versionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN }?.let { soc ->
                Build.SOC_MANUFACTURER.takeIf { it.isNotBlank() && it != Build.UNKNOWN }?.let { "$it $soc" } ?: soc
            } ?: Build.HARDWARE
        } else {
            "${Build.HARDWARE} ${Build.BOARD}"
        }

    private fun onlineCores(): Int = runCatching {
        File("/sys/devices/system/cpu").listFiles()
            ?.count { it.name.matches(Regex("cpu[0-9]+")) && File(it, "online").let { f -> !f.exists() || f.readText().trim() == "1" } }
            ?: 0
    }.getOrDefault(0)

    private fun maxFreqMhz(): Int = runCatching {
        File("/sys/devices/system/cpu")
            .listFiles()
            ?.filter { it.name.matches(Regex("cpu[0-9]+")) }
            ?.mapNotNull { dir ->
                val khz = File(dir, "cpufreq/cpuinfo_max_freq").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
                khz
            }?.maxOrNull()?.div(1000)?.toInt() ?: 0
    }.getOrDefault(0)

    private fun memoryClassMb(am: ActivityManager?): Int =
        am?.memoryClass ?: 0

    private fun totalRamMb(am: ActivityManager?): Long = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            (mi.totalMem / (1024 * 1024))
        } else 0L
    }.getOrDefault(0L)

    fun thermalStatus(pm: PowerManager?): String {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        return runCatching {
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "level ${pm.currentThermalStatus}"
            }
        }.getOrDefault("unknown")
    }

    /** Compact one-line header used at the top of every job log. */
    fun headline(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return String.format(
            Locale.US,
            "%s %s · API %d · %s · %d cores · %d MB RAM",
            Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
            Runtime.getRuntime().availableProcessors(),
            totalRamMb(am)
        )
    }
}
