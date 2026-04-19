package dev.phoneassistant.offline.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class VoskModelManager {

    enum class ModelState {
        NOT_DOWNLOADED, DOWNLOADING, EXTRACTING, READY, ERROR
    }

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val mutex = Mutex()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun ensureModel(context: Context): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val modelDir = File(context.filesDir, MODEL_DIR_NAME)
            val markerFile = File(modelDir, ".ready")

            if (modelDir.exists() && markerFile.exists()) {
                _state.value = ModelState.READY
                return@withLock modelDir
            }

            // Already downloading or extracting from another call — skip
            if (_state.value == ModelState.DOWNLOADING || _state.value == ModelState.EXTRACTING) {
                throw IllegalStateException("模型正在下载中，请稍候")
            }

            try {
                _state.value = ModelState.DOWNLOADING
                _downloadProgress.value = 0f

                val zipFile = File(context.cacheDir, "vosk-model.zip")
                downloadModel(zipFile)

                _state.value = ModelState.EXTRACTING
                extractZip(zipFile, context.filesDir)

                val extractedDir = context.filesDir.listFiles()?.find {
                    it.isDirectory && it.name.startsWith("vosk-model-small-cn")
                }
                if (extractedDir != null && extractedDir.name != MODEL_DIR_NAME) {
                    if (modelDir.exists()) modelDir.deleteRecursively()
                    extractedDir.renameTo(modelDir)
                }

                markerFile.createNewFile()
                zipFile.delete()

                _state.value = ModelState.READY
                _downloadProgress.value = 1f
                modelDir
            } catch (e: Exception) {
                _state.value = ModelState.ERROR
                throw e
            }
        }
    }

    private fun downloadModel(destination: File) {
        // Support resuming interrupted downloads
        var existingLength = 0L
        if (destination.exists()) {
            existingLength = destination.length()
        }

        val requestBuilder = Request.Builder().url(MODEL_URL)
        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            // 206 = partial content (resume), 200 = full content
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("下载失败：HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("下载失败：空响应")

            // If server doesn't support Range (returned 200 instead of 206), start from scratch
            val isResuming = response.code == 206
            val startOffset = if (isResuming) existingLength else 0L
            if (!isResuming && existingLength > 0) {
                destination.delete()
            }

            // Total size: Content-Range header for resume, or Content-Length for fresh download
            val totalLength = if (isResuming) {
                val contentRange = response.header("Content-Range")
                // Content-Range: bytes 12345-99999/100000
                contentRange?.substringAfter("/")?.toLongOrNull() ?: (body.contentLength() + startOffset)
            } else {
                body.contentLength()
            }

            val fos = FileOutputStream(destination, isResuming)
            fos.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var totalRead = startOffset
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (totalLength > 0) {
                            _downloadProgress.value = (totalRead.toFloat() / totalLength)
                                .coerceIn(0f, 0.95f)
                        }
                    }
                }
            }
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(targetDir, entry.name)
                if (!file.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside target dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { output ->
                        zis.copyTo(output)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val MODEL_DIR_NAME = "vosk-model-cn"
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    }
}
