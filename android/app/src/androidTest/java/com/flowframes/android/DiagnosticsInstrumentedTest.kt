package com.flowframes.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flowframes.android.core.DeviceInfo
import com.flowframes.android.job.AiQuality
import com.flowframes.android.job.EngineBenchmark
import com.flowframes.android.job.Settings
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the diagnostic path the user relies on: device facts, the engine benchmark and the
 * ONNX Runtime profiler parsing. A crash here would leave a bug report with no usable data.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsInstrumentedTest {

    @Test
    fun deviceInfoCoversTheImportantFacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = DeviceInfo.lines(context)
        Log.i(TAG, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("Device:") })
        assertTrue(lines.any { it.startsWith("CPU:") })
        assertTrue(lines.any { it.startsWith("Memory:") })
        assertTrue(lines.any { it.startsWith("Thermal status:") })
        assertTrue(DeviceInfo.headline(context).contains("cores"))
    }

    @Test
    fun benchmarkProducesAReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(maxLongSide = 480, quality = AiQuality.FAST)
        val logged = mutableListOf<String>()
        val report = EngineBenchmark.run(
            context = context,
            settings = settings,
            runs = 1,
            report = { line -> logged += line }
        )
        Log.i(TAG, report)

        assertTrue(report.contains("=== FLOWFRAMES ENGINE BENCHMARK ==="))
        assertTrue(report.contains("=== END BENCHMARK ==="))
        // Static shape resolution must have been applied and logged by the engine.
        assertTrue("no static shape log:\n$report", logged.any { it.contains("(static)") })
        // At least one configuration has to succeed on a real device or emulator.
        val measured = report.lines().count { it.trim().matches(Regex("^[0-9.]+ ms .+")) }
        assertTrue("no configuration succeeded:\n$report", measured > 0)
    }

    private companion object {
        const val TAG = "FlowFramesDiag"
    }
}
