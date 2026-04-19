package dev.phoneassistant.offline.planner

/**
 * JNI bridge to MNN LLM engine.
 *
 * Uses a self-built native library (phoneassistant_llm) that wraps MNN's
 * C++ LLM API via [mls::LlmSession]. The native .so files (libMNN.so,
 * libllm.so, libMNN_Express.so) live in jniLibs/arm64-v8a/.
 */
class MnnLlmBridge {

    private var nativePtr: Long = 0

    val isLoaded: Boolean get() = nativePtr != 0L

    /**
     * Load the model from the given directory.
     * @param configPath path to the directory containing config.json and model weights
     * @param mergedConfig JSON string of merged configuration (default "{}")
     * @param extraConfig JSON string of extra configuration (default "{}")
     * @param history optional chat history strings
     * @throws IllegalStateException if model loading fails
     */
    fun load(
        configPath: String,
        mergedConfig: String = "{}",
        extraConfig: String = "{}",
        history: List<String>? = null
    ) {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
        }
        val normalizedPath = if (configPath.endsWith("/")) configPath else "$configPath/"
        nativePtr = initNative(normalizedPath, history, mergedConfig, extraConfig)
    }

    /**
     * Run text generation with streaming progress callback.
     * @param prompt the input prompt
     * @param keepHistory whether to keep conversation history (unused in current impl — session manages internally)
     * @param listener callback receiving generated tokens; return true to stop
     * @return map of performance metrics (prompt_len, decode_len, prefill_time, decode_time)
     */
    fun generate(
        prompt: String,
        keepHistory: Boolean = true,
        listener: GenerateProgressListener
    ): Map<String, Any> {
        check(nativePtr != 0L) { "Model not loaded. Call load() first." }
        @Suppress("UNCHECKED_CAST")
        return submitNative(nativePtr, prompt, keepHistory, listener) as Map<String, Any>
    }

    /**
     * Run inference with full conversation history.
     * @param history list of Pair(role, content) representing the conversation
     * @param listener progress callback
     * @return performance metrics map
     */
    fun generateWithHistory(
        history: List<android.util.Pair<String, String>>,
        listener: GenerateProgressListener
    ): Map<String, Any> {
        check(nativePtr != 0L) { "Model not loaded. Call load() first." }
        @Suppress("UNCHECKED_CAST")
        return submitFullHistoryNative(nativePtr, history, listener) as Map<String, Any>
    }

    /** Reset conversation context (clear KV cache, keep system prompt). */
    fun reset() {
        if (nativePtr != 0L) resetNative(nativePtr)
    }

    /** Clear conversation history. */
    fun clearHistory() {
        if (nativePtr != 0L) clearHistoryNative(nativePtr)
    }

    /** Release the model and free native memory. */
    fun release() {
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0
        }
    }

    // --- Configuration ---

    fun updateSystemPrompt(prompt: String) {
        if (nativePtr != 0L) updateSystemPromptNative(nativePtr, prompt)
    }

    fun updateMaxNewTokens(max: Int) {
        if (nativePtr != 0L) updateMaxNewTokensNative(nativePtr, max)
    }

    fun updateAssistantPrompt(prompt: String) {
        if (nativePtr != 0L) updateAssistantPromptNative(nativePtr, prompt)
    }

    fun updateConfig(json: String) {
        if (nativePtr != 0L) updateConfigNative(nativePtr, json)
    }

    fun getSystemPrompt(): String? {
        return if (nativePtr != 0L) getSystemPromptNative(nativePtr) else null
    }

    fun getDebugInfo(): String {
        return if (nativePtr != 0L) getDebugInfoNative(nativePtr) else ""
    }

    fun dumpConfig(): String {
        return if (nativePtr != 0L) dumpConfigNative(nativePtr) else "{}"
    }

    // --- Audio output ---

    fun setEnableAudioOutput(enable: Boolean) {
        if (nativePtr != 0L) updateEnableAudioOutputNative(nativePtr, enable)
    }

    fun setAudioDataListener(listener: Any?) {
        if (nativePtr != 0L) setWaveformCallbackNative(nativePtr, listener)
    }

    // --- Native method declarations ---

    private external fun initNative(
        modelDir: String,
        chatHistory: List<String>?,
        mergeConfigStr: String,
        configJsonStr: String
    ): Long

    private external fun releaseNative(ptr: Long)

    private external fun submitNative(
        ptr: Long,
        input: String,
        keepHistory: Boolean,
        progressListener: GenerateProgressListener
    ): Any // Returns HashMap<String, Object>

    private external fun submitFullHistoryNative(
        ptr: Long,
        history: List<android.util.Pair<String, String>>,
        progressListener: GenerateProgressListener
    ): Any // Returns HashMap<String, Object>

    private external fun resetNative(ptr: Long)
    private external fun clearHistoryNative(ptr: Long)
    private external fun updateMaxNewTokensNative(ptr: Long, maxTokens: Int)
    private external fun updateSystemPromptNative(ptr: Long, prompt: String)
    private external fun updateAssistantPromptNative(ptr: Long, prompt: String)
    private external fun updateConfigNative(ptr: Long, configJson: String)
    private external fun getSystemPromptNative(ptr: Long): String?
    private external fun getDebugInfoNative(ptr: Long): String
    private external fun dumpConfigNative(ptr: Long): String
    private external fun updateEnableAudioOutputNative(ptr: Long, enable: Boolean)
    private external fun setWaveformCallbackNative(ptr: Long, listener: Any?)

    companion object {
        init {
            System.loadLibrary("phoneassistant_llm")
        }
    }
}

/**
 * Callback for streaming token generation.
 * [onProgress] is called with each generated token fragment.
 * When generation is complete, [onProgress] is called with null.
 * Return true to request early stop.
 */
fun interface GenerateProgressListener {
    fun onProgress(progress: String?): Boolean
}
