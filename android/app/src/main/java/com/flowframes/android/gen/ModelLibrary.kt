package com.flowframes.android.gen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/** A checkpoint the app can hand to the engine. Models live in files/models, never in the APK. */
data class LocalModel(val file: File) {
    val name: String get() = file.name
    val bytes: Long get() = file.length()

    /** "sd-turbo-Q8_0.gguf · 1.1 GB" */
    val label: String get() = "${file.name} · ${ModelLibrary.formatBytes(bytes)}"
}

/**
 * Import and inventory of checkpoints. A model is picked from the phone's storage (Documents UI)
 * and copied into the app's private folder: the engine reads it with fopen, which a content://
 * URI does not support, and the copy keeps working after the picker's grant expires.
 */
object ModelLibrary {

    private val EXTENSIONS = setOf("safetensors", "ckpt", "gguf", "pt", "pth")

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun list(context: Context): List<LocalModel> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.US) in EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LocalModel(it) }
            ?: emptyList()

    /** Human-readable size, matching the short style used across Capri. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
        else -> String.format(Locale.US, "%.0f kB", bytes / 1000.0)
    }

    fun freeBytes(context: Context): Long = context.filesDir.usableSpace

    /**
     * Copies [uri] into files/models. [onProgress] gets (copied, total) with total = -1 when the
     * provider does not report a size.
     */
    fun import(
        context: Context,
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit
    ): LocalModel {
        val resolver = context.contentResolver
        var name = "model.safetensors"
        var declaredSize = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
                    }
                }
        }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val extension = safeName.substringAfterLast('.', "").lowercase(Locale.US)
        require(extension in EXTENSIONS) {
            "Unsupported file: ${name}. Use a .safetensors, .ckpt or .gguf checkpoint."
        }
        if (declaredSize > 0) {
            val free = freeBytes(context)
            require(free > declaredSize + 256L * 1024 * 1024) {
                "Not enough space: ${formatBytes(declaredSize)} needed, ${formatBytes(free)} free."
            }
        }
        val target = File(dir(context), safeName)
        if (target.exists()) {
            throw IllegalArgumentException("A model named $safeName is already imported.")
        }
        val temporary = File(dir(context), "$safeName.part")
        var copied = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered(4 * 1024 * 1024).use { output ->
                    val buffer = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, declaredSize)
                    }
                    output.flush()
                }
            } ?: throw IllegalStateException("The provider returned no data for $name")
            check(temporary.renameTo(target)) { "Could not finish the copy of $safeName" }
        } catch (t: Throwable) {
            runCatching { temporary.delete() }
            throw t
        }
        return LocalModel(target)
    }

    fun delete(model: LocalModel): Boolean = runCatching { model.file.delete() }.getOrDefault(false)
}
