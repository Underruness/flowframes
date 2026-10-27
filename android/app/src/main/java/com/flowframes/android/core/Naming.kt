package com.flowframes.android.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Output file naming, compatible with FlowFrames' `{name}_{factor}x_{fps}fps` style patterns. */
object Naming {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    fun build(
        pattern: String,
        sourceName: String,
        factor: Int,
        fps: Double,
        codec: String,
        date: Date = Date()
    ): String {
        val base = sourceName.substringBeforeLast('.').ifBlank { "video" }
        val fpsText = if (fps <= 0.0) "0" else String.format(Locale.US, "%.2f", fps).trimEnd('0').trimEnd('.')
        var out = pattern.ifBlank { "{name}_{factor}x_{fps}fps" }
            .replace("{name}", base)
            .replace("{factor}", factor.toString())
            .replace("{fps}", fpsText)
            .replace("{codec}", codec.lowercase(Locale.US))
            .replace("{date}", SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(date))
        out = ILLEGAL.replace(out, "_").trim().trim('.')
        if (out.isEmpty()) out = base
        return "$out.mp4"
    }

    /** Makes sure the name does not collide with an existing file by appending `_2`, `_3`, ... */
    fun deduplicate(fileName: String, exists: (String) -> Boolean): String {
        if (!exists(fileName)) return fileName
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "${stem}_$i$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }
}
