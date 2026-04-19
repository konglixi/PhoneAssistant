package dev.phoneassistant.data.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import java.util.concurrent.TimeUnit

/**
 * Manages downloading, extracting, and tracking multiple MNN models.
 *
 * Downloads model files individually from ModelScope using the resolve API:
 *   https://modelscope.cn/models/{repo}/resolve/master/{filePath}
 *
 * The file list is fetched from:
 *   https://modelscope.cn/api/v1/models/{repo}/repo/files
 */
class ModelRepository {

    enum class DownloadState {
        NOT_DOWNLOADED, DOWNLOADING, READY, ERROR
    }

    data class ModelDownloadStatus(
        val state: DownloadState = DownloadState.NOT_DOWNLOADED,
        val progress: Float = 0f,
        val error: String? = null
    )

    private val _statusMap = MutableStateFlow<Map<String, ModelDownloadStatus>>(emptyMap())
    val statusMap: StateFlow<Map<String, ModelDownloadStatus>> = _statusMap.asStateFlow()

    private val mutexMap = mutableMapOf<String, Mutex>()

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1)) // Avoid HTTP/2 multiplexing issues with ModelScope
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun refreshStatuses(context: Context) {
        _statusMap.update { current ->
            val updated = current.toMutableMap()
            for (model in ModelCatalog.models) {
                val modelDir = File(context.filesDir, model.dirName)
                val marker = File(modelDir, ".ready")
                updated[model.id] = if (modelDir.exists() && marker.exists()) {
                    ModelDownloadStatus(DownloadState.READY, 1f)
                } else {
                    current[model.id] ?: ModelDownloadStatus()
                }
            }
            updated
        }
    }

    suspend fun ensureModel(context: Context, model: ModelInfo): File = withContext(Dispatchers.IO) {
        val mutex = synchronized(mutexMap) { mutexMap.getOrPut(model.id) { Mutex() } }
        mutex.withLock {
            val modelDir = File(context.filesDir, model.dirName)
            val marker = File(modelDir, ".ready")

            if (modelDir.exists() && marker.exists()) {
                updateStatus(model.id, ModelDownloadStatus(DownloadState.READY, 1f))
                return@withLock modelDir
            }

            val current = _statusMap.value[model.id]
            if (current?.state == DownloadState.DOWNLOADING) {
                throw IllegalStateException("模型 ${model.name} 正在下载中，请稍候")
            }

            try {
                updateStatus(model.id, ModelDownloadStatus(DownloadState.DOWNLOADING, 0f))

                // Clean and recreate model directory
                if (modelDir.exists()) modelDir.deleteRecursively()
                modelDir.mkdirs()

                downloadModelFiles(model, modelDir)

                marker.createNewFile()
                updateStatus(model.id, ModelDownloadStatus(DownloadState.READY, 1f))
                Log.d(TAG, "Model ${model.id} ready at ${modelDir.absolutePath}")
                modelDir
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${model.id}: ${e.message}", e)
                updateStatus(model.id, ModelDownloadStatus(DownloadState.ERROR, 0f, e.message))
                throw e
            }
        }
    }

    suspend fun deleteModel(context: Context, model: ModelInfo) = withContext(Dispatchers.IO) {
        val mutex = synchronized(mutexMap) { mutexMap.getOrPut(model.id) { Mutex() } }
        mutex.withLock {
            val modelDir = File(context.filesDir, model.dirName)
            if (modelDir.exists()) modelDir.deleteRecursively()
            updateStatus(model.id, ModelDownloadStatus(DownloadState.NOT_DOWNLOADED, 0f))
        }
    }

    fun getModelDir(context: Context, model: ModelInfo): File {
        return File(context.filesDir, model.dirName)
    }

    fun isModelReady(context: Context, model: ModelInfo): Boolean {
        val modelDir = File(context.filesDir, model.dirName)
        return modelDir.exists() && File(modelDir, ".ready").exists()
    }

    // ── Internal: per-file download from ModelScope ──

    private data class RepoFile(val path: String, val size: Long)

    /**
     * Fetch file list from ModelScope API, then download each file.
     * Skips .gitattributes and README.md.
     */
    private fun downloadModelFiles(model: ModelInfo, targetDir: File) {
        val repo = model.modelScopeRepo
        Log.d(TAG, "Fetching file list for $repo")

        // 1) Get file list
        val files = fetchFileList(repo)
        if (files.isEmpty()) {
            throw IllegalStateException("模型仓库为空或无法获取文件列表: $repo")
        }

        val totalBytes = files.sumOf { it.size }
        var downloadedBytes = 0L
        Log.d(TAG, "Will download ${files.size} files, total ${totalBytes / 1024 / 1024} MB")

        // 2) Download each file
        for (file in files) {
            val destFile = File(targetDir, file.path)
            destFile.parentFile?.mkdirs()

            downloadSingleFile(repo, file.path, destFile)
            downloadedBytes += file.size

            val progress = if (totalBytes > 0) {
                (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 0.99f)
            } else 0f
            updateStatus(model.id, ModelDownloadStatus(DownloadState.DOWNLOADING, progress))
        }
    }

    private fun fetchFileList(repo: String): List<RepoFile> {
        val url = "$MODELSCOPE_API/models/$repo/repo/files"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("获取模型文件列表失败: HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("获取模型文件列表失败: 空响应")

            val json = JSONObject(body)
            if (json.optInt("Code") != 200) {
                val msg = json.optString("Message", "unknown error")
                throw IllegalStateException("获取模型文件列表失败: $msg")
            }

            val filesArray = json.getJSONObject("Data").getJSONArray("Files")
            val result = mutableListOf<RepoFile>()
            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.getJSONObject(i)
                val path = fileObj.getString("Path")
                val size = fileObj.getLong("Size")
                val type = fileObj.getString("Type")
                // Skip directories, .gitattributes, README
                if (type != "blob") continue
                if (path == ".gitattributes" || path == "README.md" || path == "configuration.json") continue
                result.add(RepoFile(path, size))
            }
            return result
        }
    }

    private fun downloadSingleFile(repo: String, filePath: String, destination: File) {
        var lastException: Exception? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                downloadSingleFileAttempt(repo, filePath, destination)
                return
            } catch (e: SocketException) {
                lastException = e
                Log.w(TAG, "Attempt $attempt/$MAX_RETRIES failed for $filePath: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(1000L * attempt) // backoff
                }
            } catch (e: java.io.IOException) {
                lastException = e
                Log.w(TAG, "Attempt $attempt/$MAX_RETRIES IO error for $filePath: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    Thread.sleep(1000L * attempt)
                }
            }
        }
        throw lastException ?: IllegalStateException("下载 $filePath 失败")
    }

    private fun downloadSingleFileAttempt(repo: String, filePath: String, destination: File) {
        // Support resuming
        var existingLength = 0L
        if (destination.exists()) {
            existingLength = destination.length()
        }

        val url = "$MODELSCOPE_BASE/models/$repo/resolve/master/$filePath"
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }

        Log.d(TAG, "Downloading $filePath (existing: $existingLength)")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("下载 $filePath 失败: HTTP ${response.code} ${response.message}")
            }

            val body = response.body
                ?: throw IllegalStateException("下载 $filePath 失败: 空响应")

            val isResuming = response.code == 206
            if (!isResuming && existingLength > 0) {
                destination.delete()
            }

            FileOutputStream(destination, isResuming).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            Log.d(TAG, "Downloaded $filePath -> ${destination.length()} bytes")
        }
    }

    private fun updateStatus(modelId: String, status: ModelDownloadStatus) {
        _statusMap.update { current -> current.toMutableMap().apply { this[modelId] = status } }
    }

    companion object {
        private const val TAG = "ModelRepository"
        private const val MODELSCOPE_BASE = "https://modelscope.cn"
        private const val MODELSCOPE_API = "https://modelscope.cn/api/v1"
        private const val USER_AGENT = "PhoneAssistant/1.0"
        private const val MAX_RETRIES = 3
    }
}
