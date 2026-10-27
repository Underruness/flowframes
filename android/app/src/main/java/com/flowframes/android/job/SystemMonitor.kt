package com.flowframes.android.job

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.io.File
import java.util.Locale

/**
 * RAM and temperature readings for the UI and the job log.
 *
 * Temperatures come from /sys/class/thermal. Which zones an app may read depends on the vendor
 * (SELinux): on many Snapdragon phones the CPU, GPU and NPU (Hexagon "nsp") zones are readable,
 * on others only the battery is. Missing values are shown as unavailable, never guessed.
 */
object SystemMonitor {

    data class Snapshot(
        /** App memory (PSS) and its split, in MB. */
        val appPssMb: Int,
        val javaMb: Int,
        val nativeMb: Int,
        val graphicsMb: Int,
        /** Phone RAM, in MB. */
        val sysAvailMb: Long,
        val sysTotalMb: Long,
        val lowMemory: Boolean,
        /** °C, null when the zone is missing or not readable. */
        val cpuC: Double?,
        val gpuC: Double?,
        val npuC: Double?,
        val batteryC: Double?,
        val skinC: Double?,
        /** PowerManager thermal status label and headroom (1.0 = throttling starts). */
        val thermal: String,
        val headroom: Float?
    ) {
        fun shortLine(): String {
            fun t(v: Double?) = if (v == null) "n/a" else String.format(Locale.US, "%.0f°C", v)
            return "RAM app ${appPssMb} MB (java $javaMb, native $nativeMb, gpu $graphicsMb) · " +
                "phone ${sysTotalMb - sysAvailMb}/$sysTotalMb MB · " +
                "CPU ${t(cpuC)} GPU ${t(gpuC)} NPU ${t(npuC)} battery ${t(batteryC)}"
        }
    }

    private class Zone(val type: String, val temp: File)

    @Volatile private var zones: List<Zone>? = null

    private fun zones(): List<Zone> {
        zones?.let { return it }
        val found = ArrayList<Zone>()
        runCatching {
            File("/sys/class/thermal").listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?.forEach { dir ->
                    val type = runCatching { File(dir, "type").readText().trim() }.getOrNull() ?: return@forEach
                    val temp = File(dir, "temp")
                    if (readC(temp) != null) found += Zone(type.lowercase(Locale.US), temp)
                }
        }
        zones = found
        return found
    }

    private fun readC(file: File): Double? = runCatching {
        val raw = file.readText().trim().toDouble()
        val c = if (raw > 1000 || raw < -1000) raw / 1000.0 else raw
        c.takeIf { it > -40 && it < 150 }
    }.getOrNull()

    /** Virtual or limit zones that are not a die temperature (trip points, mitigation steps…). */
    private val ignored = listOf("step", "limit", "lmh", "max", "trip", "cdev", "mitig", "virt", "-usr", "ibat", "vbat", "soc_")

    private fun readings(prefixes: List<String>): List<Double> =
        zones().filter { z -> prefixes.any { z.type.startsWith(it) || z.type.contains(it) } && ignored.none { z.type.contains(it) } }
            .mapNotNull { readC(it.temp) }
            // Above 100 °C a phone has shut the core down long before: not a real reading.
            .filter { it in 5.0..100.0 }

    /**
     * Median rather than maximum: a Snapdragon exposes 20+ CPU zones and some of them are
     * virtual or report a fixed limit (the first version showed 105 °C at rest because of one).
     */
    private fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Zone types that could be read, with their values, for the log (helps calibrate n/a or odd values). */
    fun zoneSummary(): String {
        val z = zones()
        return if (z.isEmpty()) "no readable thermal zone (vendor restriction)"
        else "${z.size} readable thermal zones: " +
            z.take(60).joinToString(", ") { "${it.type}=${readC(it.temp)?.let { c -> String.format(Locale.US, "%.0f", c) } ?: "?"}" }
    }

    fun sample(context: Context): Snapshot {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        fun stat(key: String) = (mi.getMemoryStat(key)?.toIntOrNull() ?: 0) / 1024
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val sys = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        }.getOrNull()

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            runCatching { pm?.getThermalHeadroom(0)?.takeIf { !it.isNaN() } }.getOrNull()
        } else null

        return Snapshot(
            appPssMb = mi.totalPss / 1024,
            javaMb = stat("summary.java-heap"),
            nativeMb = stat("summary.native-heap"),
            graphicsMb = stat("summary.graphics"),
            sysAvailMb = sys.availMem / (1024 * 1024),
            sysTotalMb = sys.totalMem / (1024 * 1024),
            lowMemory = sys.lowMemory,
            cpuC = median(readings(listOf("cpu-", "cpu_", "cpuss", "cpu0", "cpu1", "cpu2", "cpu3", "cpu4", "cpu5", "cpu6", "cpu7", "apc"))),
            gpuC = readings(listOf("gpu")).maxOrNull(),
            // Qualcomm Hexagon NPU: nspss-*, nsphvx-*, nsphmx-*; others: npu, cdsp, apu (MediaTek).
            npuC = readings(listOf("nsp", "npu", "cdsp", "q6-hvx", "hvx", "tpu")).maxOrNull(),
            batteryC = battery ?: readings(listOf("battery")).maxOrNull(),
            skinC = readings(listOf("skin", "sys-therm", "quiet", "xo-therm")).maxOrNull(),
            thermal = runCatching { com.flowframes.android.core.DeviceInfo.thermalStatus(pm) }.getOrDefault("unknown"),
            headroom = headroom
        )
    }
}
