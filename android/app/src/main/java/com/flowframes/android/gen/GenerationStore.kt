package com.flowframes.android.gen

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class GenStatus { IDLE, LOADING, RUNNING, DONE, ERROR }

/** Everything the Create screen shows. */
data class GenUiState(
    val models: List<LocalModel> = emptyList(),
    val model: LocalModel? = null,
    val prompt: String = "a lighthouse on a cliff at dusk, painterly, warm light",
    val negative: String = "blurry, low quality, watermark, text",
    val size: GenSize = GenSize.SQUARE_512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    /** Empty = a fresh seed at every run. */
    val seedText: String = "",
    val sampler: Sampler = Sampler.DEFAULT,
    val status: GenStatus = GenStatus.IDLE,
    val step: Int = 0,
    val total: Int = 0,
    val elapsedMs: Long = 0,
    val result: Bitmap? = null,
    /** Seed actually used for [result], so it can be reused. */
    val resultSeed: Long? = null,
    val savedName: String? = null,
    val savedUri: String? = null,
    val message: String? = null,
    val error: String? = null
) {
    val busy: Boolean get() = status == GenStatus.LOADING || status == GenStatus.RUNNING
}

data class SavedImage(val name: String, val uri: Uri?)

/**
 * State and jobs of the Create screen.
 *
 * One generation at a time, on the Default dispatcher; the engine keeps its checkpoint loaded
 * between runs and is released when the model changes or the store is closed.
 */
class GenerationStore(
    context: Context,
    private val onLog: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val engine = DiffusionEngine(onLog)
    private val _state = MutableStateFlow(
        GenUiState(models = ModelLibrary.list(appContext)).let { state ->
            state.copy(model = state.models.firstOrNull())
        }
    )
    val state: StateFlow<GenUiState> = _state

    private var job: Job? = null
    private var cancelled = false

    fun update(transform: (GenUiState) -> GenUiState) {
        _state.value = transform(_state.value)
    }

    fun refreshModels() {
        val models = ModelLibrary.list(appContext)
        _state.value = _state.value.copy(
            models = models,
            model = models.firstOrNull { it.file == _state.value.model?.file } ?: models.firstOrNull()
        )
    }

    fun selectModel(model: LocalModel?) {
        if (model?.file?.absolutePath == _state.value.model?.file?.absolutePath) return
        engine.close()
        _state.value = _state.value.copy(model = model, result = null, error = null, message = null)
    }

    fun deleteModel(model: LocalModel) {
        engine.close()
        val removed = ModelLibrary.delete(model)
        onLog(if (removed) "Model deleted: ${model.name}" else "Could not delete ${model.name}")
        refreshModels()
    }

    fun importModel(uri: Uri, onProgress: (copied: Long, total: Long) -> Unit): LocalModel? {
        return runCatching {
            val model = ModelLibrary.import(appContext, uri, onProgress)
            onLog("Model imported: ${model.name} (${ModelLibrary.formatBytes(model.bytes)})")
            refreshModels()
            selectModel(model)
            model
        }.getOrElse { error ->
            onLog("Model import failed: ${error.message}")
            _state.value = _state.value.copy(error = error.message ?: "import failed")
            null
        }
    }

    fun start(scope: CoroutineScope) {
        val current = _state.value
        if (current.busy) return
        val model = current.model
        if (model == null) {
            _state.value = current.copy(status = GenStatus.ERROR, error = "Import a checkpoint first.")
            return
        }
        if (current.prompt.isBlank()) {
            _state.value = current.copy(status = GenStatus.ERROR, error = "Write a prompt first.")
            return
        }
        cancelled = false
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val request = GenRequest(
            prompt = current.prompt.trim(),
            negative = current.negative.trim(),
            width = current.size.width,
            height = current.size.height,
            steps = current.steps,
            cfg = current.cfg,
            seed = current.seedText.toLongOrNull(),
            sampler = current.sampler
        )
        _state.value = current.copy(
            status = GenStatus.LOADING,
            step = 0,
            total = request.steps,
            elapsedMs = 0,
            result = null,
            resultSeed = null,
            savedName = null,
            savedUri = null,
            error = null,
            message = "Loading ${model.name}…"
        )
        job = scope.launch {
            val started = System.currentTimeMillis()
            try {
                val bitmap = withContext(Dispatchers.Default) {
                    engine.load(model.file, threads)
                    _state.value = _state.value.copy(status = GenStatus.RUNNING, message = null)
                    engine.generate(request, null) { step, total ->
                        _state.value = _state.value.copy(step = step, total = total)
                    }
                }
                val seed = request.seed ?: 0L
                _state.value = _state.value.copy(
                    status = GenStatus.DONE,
                    step = request.steps,
                    total = request.steps,
                    elapsedMs = System.currentTimeMillis() - started,
                    result = bitmap,
                    resultSeed = if (seed != 0L) seed else null,
                    message = "Done in ${formatSeconds(System.currentTimeMillis() - started)}"
                )
                onLog("Generation done: ${request.width}x${request.height}, ${request.steps} steps, " +
                    "${formatSeconds(System.currentTimeMillis() - started)}")
            } catch (t: Throwable) {
                if (cancelled) {
                    _state.value = _state.value.copy(status = GenStatus.IDLE, message = "Generation cancelled")
                    onLog("Generation cancelled")
                } else {
                    val message = t.message ?: t.javaClass.simpleName
                    _state.value = _state.value.copy(status = GenStatus.ERROR, error = message, message = null)
                    onLog("Generation failed: $message")
                }
            }
        }
    }

    fun cancel() {
        cancelled = true
        engine.cancel()
    }

    /** Saves the latest image into Download/capri (or the app's Pictures folder before Android 10). */
    fun save(): SavedImage? {
        val current = _state.value
        val bitmap = current.result ?: return null
        current.savedUri?.let { uri -> return SavedImage(current.savedName ?: "image", Uri.parse(uri)) }
        val name = "capri-%d.png".format(Locale.US, System.currentTimeMillis() / 1000)
        return runCatching { writeImage(bitmap, name) }
            .onSuccess { saved ->
                _state.value = _state.value.copy(savedName = saved.name, savedUri = saved.uri?.toString())
                onLog("Image saved: ${saved.name}")
            }
            .onFailure { error ->
                _state.value = _state.value.copy(error = "Could not save: ${error.message}")
                onLog("Save failed: ${error.message}")
            }
            .getOrNull()
    }

    fun savedUri(): Uri? = _state.value.savedUri?.let { Uri.parse(it) }

    private fun writeImage(bitmap: Bitmap, name: String): SavedImage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Download/capri")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore refused the image")
            try {
                resolver.openOutputStream(uri)?.use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
                } ?: throw IllegalStateException("MediaStore returned no stream")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return SavedImage(name, uri)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }
        val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "capri").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG encoding failed" }
        }
        return SavedImage(name, null)
    }

    fun close() {
        job?.cancel()
        engine.close()
    }

    private fun formatSeconds(millis: Long): String =
        if (millis < 60_000) String.format(Locale.US, "%.1f s", millis / 1000.0)
        else String.format(Locale.US, "%d min %02d s", millis / 60_000, (millis / 1000) % 60)
}
