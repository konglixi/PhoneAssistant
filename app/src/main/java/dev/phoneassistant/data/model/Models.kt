package dev.phoneassistant.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssistantPlan(
    val reply: String,
    val actions: List<DeviceAction> = emptyList()
)

@Serializable
data class DeviceAction(
    val type: String,
    val appName: String? = null,
    val packageName: String? = null,
    val text: String? = null,
    val value: String? = null,
    val seconds: Long? = null
)

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val content: String,
    val thinkingText: String? = null,
    val isStreaming: Boolean = false,
    val perfMetrics: dev.phoneassistant.domain.chat.PerfMetrics? = null,
    val imageUris: List<android.net.Uri>? = null,
    val audioUri: android.net.Uri? = null,
    val videoUri: android.net.Uri? = null
)

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class AssistantSettings(
    val apiKey: String = "",
    val model: String = DEFAULT_QWEN_MODEL,
    val mode: AssistantMode = AssistantMode.OFFLINE
)

const val DEFAULT_QWEN_MODEL = "qwen-plus"

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatCompletionMessage>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null
)

@Serializable
internal data class ChatCompletionMessage(
    val role: String,
    val content: String
)

@Serializable
internal data class ResponseFormat(
    val type: String
)

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
internal data class ChatChoice(
    val message: ChatCompletionMessage
)
