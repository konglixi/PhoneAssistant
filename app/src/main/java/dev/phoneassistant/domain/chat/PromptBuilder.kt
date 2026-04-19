package dev.phoneassistant.domain.chat

import android.content.Context
import android.net.Uri
import dev.phoneassistant.utils.FileUtils

/**
 * Builds prompts with multimodal tags for MNN LLM.
 * Inserts <img>, <audio>, <video> tags that the native processor handles.
 *
 * Adapted from MnnLlmChat PromptUtils.
 */
object PromptBuilder {

    /**
     * Build a prompt string from text and optional media attachments.
     *
     * @param context Android context for URI resolution
     * @param text User text message
     * @param imageUris List of image URIs to attach
     * @param audioUri Optional audio file URI
     * @param videoUri Optional video file URI
     * @return Formatted prompt with media tags
     */
    fun buildPrompt(
        context: Context,
        text: String,
        imageUris: List<Uri>? = null,
        audioUri: Uri? = null,
        videoUri: Uri? = null
    ): String {
        return when {
            audioUri != null -> {
                val audioPath = FileUtils.ensureLocalPath(context, audioUri, "audio")
                "<audio>$audioPath</audio>$text"
            }
            !imageUris.isNullOrEmpty() -> {
                val sb = StringBuilder()
                for (uri in imageUris) {
                    val imagePath = FileUtils.ensureLocalPath(context, uri, "image")
                    sb.append("<img>$imagePath</img>")
                }
                sb.append(text)
                sb.toString()
            }
            videoUri != null -> {
                val videoPath = FileUtils.ensureLocalPath(context, videoUri, "video")
                "<video>$videoPath</video>$text"
            }
            else -> text
        }
    }
}
