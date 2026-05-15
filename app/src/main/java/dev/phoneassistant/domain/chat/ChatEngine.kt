package dev.phoneassistant.domain.chat

import dev.phoneassistant.offline.planner.MnnLlmBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performance metrics from a generation run.
 */
data class PerfMetrics(
    val promptLen: Int = 0,
    val decodeLen: Int = 0,
    val prefillTimeMs: Long = 0,
    val decodeTimeMs: Long = 0
) {
    val prefillSpeed: Float
        get() = if (prefillTimeMs > 0) promptLen * 1000f / prefillTimeMs else 0f
    val decodeSpeed: Float
        get() = if (decodeTimeMs > 0) decodeLen * 1000f / decodeTimeMs else 0f
}

/**
 * Data emitted during streaming generation.
 */
data class StreamingState(
    val normalText: String = "",
    val thinkingText: String = "",
    val isStreaming: Boolean = false,
    val perfMetrics: PerfMetrics? = null
)

/**
 * Chat engine for multi-turn conversation with streaming output.
 * Uses MnnLlmBridge.generate() with keepHistory=true, matching the
 * official MNN demo's code path (C++ Response() with internal history).
 */
class ChatEngine {

    private val resultProcessor = GenerateResultProcessor()

    private val _streamingState = MutableStateFlow(StreamingState())
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val stopRequested = AtomicBoolean(false)

    /** Conversation history for UI display (role, content) pairs. */
    private val history = mutableListOf<Pair<String, String>>()

    fun clearHistory() {
        history.clear()
    }

    fun addUserMessage(content: String) {
        history.add("user" to content)
    }

    fun addAssistantMessage(content: String) {
        history.add("assistant" to content)
    }

    /**
     * Generate a response using the same code path as the official MNN demo:
     * bridge.generate(prompt, keepHistory=true) → submitNative → C++ Response().
     *
     * C++ LlmSession manages history internally (including system prompt).
     * Kotlin-side history is maintained only for UI display.
     */
    suspend fun generate(
        bridge: MnnLlmBridge,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        addUserMessage(userMessage)
        stopRequested.set(false)
        _isGenerating.value = true

        resultProcessor.reset()
        resultProcessor.generateBegin()

        _streamingState.value = StreamingState(isStreaming = true)

        try {
            // Use bridge.generate() — same as official demo's submitNative → Response()
            // C++ manages history internally with system prompt and keepHistory=true
            val metricsMap = bridge.generate(userMessage, keepHistory = false) { progress ->
                resultProcessor.process(progress)

                _streamingState.value = StreamingState(
                    normalText = resultProcessor.getNormalOutput(),
                    thinkingText = resultProcessor.getThinkingContent(),
                    isStreaming = true
                )

                stopRequested.get() // Return true to stop
            }

            // Process end of stream
            resultProcessor.process(null)

            val perfMetrics = parsePerfMetrics(metricsMap)
            val finalNormal = resultProcessor.getNormalOutput()
            val finalThinking = resultProcessor.getThinkingContent()

            _streamingState.value = StreamingState(
                normalText = finalNormal,
                thinkingText = finalThinking,
                isStreaming = false,
                perfMetrics = perfMetrics
            )

            // Add assistant response to UI history
            addAssistantMessage(finalNormal)

            finalNormal
        } catch (e: Exception) {
            _streamingState.value = StreamingState(
                normalText = "生成失败: ${e.message}",
                isStreaming = false
            )
            throw e
        } finally {
            _isGenerating.value = false
        }
    }

    /**
     * Generate a response using an external streaming provider (e.g. online API).
     * The provider receives conversation history and a token callback.
     * The token callback returns true if stop was requested.
     *
     * @param userMessage The new user message to add
     * @param provider A suspend function that streams tokens via the callback
     * @return The final complete response text
     */
    suspend fun generateStreaming(
        userMessage: String,
        provider: suspend (
            history: List<Pair<String, String>>,
            onToken: (String) -> Boolean
        ) -> Unit
    ): String = withContext(Dispatchers.IO) {
        addUserMessage(userMessage)
        stopRequested.set(false)
        _isGenerating.value = true

        val accumulated = StringBuilder()
        _streamingState.value = StreamingState(isStreaming = true)

        try {
            val historyCopy = history.toList()
            provider(historyCopy) { token ->
                accumulated.append(token)
                _streamingState.value = StreamingState(
                    normalText = accumulated.toString(),
                    isStreaming = true
                )
                stopRequested.get()
            }

            val finalText = accumulated.toString()
            _streamingState.value = StreamingState(
                normalText = finalText,
                isStreaming = false
            )

            addAssistantMessage(finalText)
            finalText
        } catch (e: Exception) {
            _streamingState.value = StreamingState(
                normalText = "生成失败: ${e.message}",
                isStreaming = false
            )
            throw e
        } finally {
            _isGenerating.value = false
        }
    }

    fun stopGeneration() {
        stopRequested.set(true)
    }

    private fun parsePerfMetrics(map: Map<String, Any>): PerfMetrics {
        return PerfMetrics(
            promptLen = (map["prompt_len"] as? Number)?.toInt() ?: 0,
            decodeLen = (map["decode_len"] as? Number)?.toInt() ?: 0,
            prefillTimeMs = (map["prefill_time"] as? Number)?.toLong() ?: 0,
            decodeTimeMs = (map["decode_time"] as? Number)?.toLong() ?: 0
        )
    }
}
