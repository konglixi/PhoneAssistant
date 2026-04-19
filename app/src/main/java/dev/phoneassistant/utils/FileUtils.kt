package dev.phoneassistant.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * File utility functions for handling URI-to-path conversion,
 * file copying, and media metadata extraction.
 *
 * Adapted from MnnLlmChat FileUtils.
 */
object FileUtils {

    /**
     * Get the file path for a Uri. Only works for file:// URIs.
     * For content:// URIs, the file must be copied first via [copyUriToFile].
     */
    fun getPathForUri(uri: Uri): String? {
        return if (uri.scheme == "file") {
            uri.path
        } else {
            null
        }
    }

    /**
     * Copy a content URI to a local file and return the file path.
     */
    fun copyUriToFile(context: Context, uri: Uri, destFile: File): String {
        destFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Cannot open URI: $uri")
        return destFile.absolutePath
    }

    /**
     * Ensure a URI is accessible as a local file path.
     * If it's a content:// URI, copies it to a temp file.
     */
    fun ensureLocalPath(context: Context, uri: Uri, prefix: String = "media"): String {
        if (uri.scheme == "file") {
            return uri.path ?: throw IllegalStateException("File URI has null path")
        }

        // Content URI — copy to cache
        val extension = context.contentResolver.getType(uri)?.let {
            when {
                it.contains("jpeg") || it.contains("jpg") -> ".jpg"
                it.contains("png") -> ".png"
                it.contains("webp") -> ".webp"
                it.contains("wav") -> ".wav"
                it.contains("mp4") -> ".mp4"
                it.contains("webm") -> ".webm"
                else -> ""
            }
        } ?: ""

        val tempDir = File(context.cacheDir, "media_temp")
        tempDir.mkdirs()
        val tempFile = File(tempDir, "${prefix}_${System.currentTimeMillis()}$extension")
        return copyUriToFile(context, uri, tempFile)
    }

    /**
     * Get audio duration in seconds.
     */
    fun getAudioDuration(filePath: String): Double {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()
            durationMs / 1000.0
        } catch (e: Exception) {
            0.0
        }
    }

    /**
     * Generate a destination file path for a resource type.
     */
    fun generateResourcePath(context: Context, type: String): File {
        val dir = File(context.filesDir, "resources/$type")
        dir.mkdirs()
        return File(dir, "${type}_${System.currentTimeMillis()}")
    }

    /**
     * Format file size in human-readable form.
     */
    fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(size / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
