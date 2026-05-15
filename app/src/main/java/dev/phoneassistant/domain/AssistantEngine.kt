package dev.phoneassistant.domain

import android.content.Context
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.data.model.AssistantSettings
import dev.phoneassistant.data.model.ChatCompletionChunk
import dev.phoneassistant.data.model.ChatCompletionMessage
import dev.phoneassistant.data.model.ChatCompletionRequest
import dev.phoneassistant.data.model.DEFAULT_QWEN_MODEL
import dev.phoneassistant.data.model.ModelCatalog
import dev.phoneassistant.data.model.ModelInfo
import dev.phoneassistant.data.model.ModelRepository
import dev.phoneassistant.data.model.TaskMode
import dev.phoneassistant.data.model.isPlanner
import dev.phoneassistant.data.model.isThinking
import dev.phoneassistant.domain.chat.ChatEngine
import dev.phoneassistant.domain.planner.CommandPlanner
import dev.phoneassistant.domain.speech.SpeechRecognizer
import dev.phoneassistant.offline.planner.MnnCommandPlanner
import dev.phoneassistant.offline.planner.MnnLlmBridge
import dev.phoneassistant.offline.speech.VoskModelManager
import dev.phoneassistant.offline.speech.VoskSpeechRecognizer
import dev.phoneassistant.online.planner.QwenCommandPlanner
import dev.phoneassistant.online.speech.CloudSpeechRecognizer
import dev.phoneassistant.service.AssistantActionExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

class AssistantEngine(private val context: Context) {

    // ── Offline components ──
    val voskModelManager = VoskModelManager()
    val modelRepository = ModelRepository()
    private val offlineSpeech = VoskSpeechRecognizer(voskModelManager)
    private val offlinePlanner = MnnCommandPlanner(context.applicationContext, modelRepository)

    // ── Online components ──
    private val onlineSpeech = CloudSpeechRecognizer()
    private val onlinePlanner = QwenCommandPlanner()

    // ── Shared ──
    private val actionExecutor = AssistantActionExecutor(context)
    @Volatile private var settings = AssistantSettings()

    // ── Mode ──
    private val _mode = MutableStateFlow(AssistantMode.OFFLINE)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

    // ── Task mode ──
    private val _taskMode = MutableStateFlow(TaskMode.CHAT)
    val taskMode: StateFlow<TaskMode> = _taskMode.asStateFlow()

    // ── Active model ──
    private val _activeModel = MutableStateFlow(
        ModelCatalog.findById("qwen2.5-0.5b") ?: ModelCatalog.models.first()
    )
    val activeModel: StateFlow<ModelInfo> = _activeModel.asStateFlow()

    /** The bridge used for chat-mode generation (non-planner). */
    private var chatBridge: MnnLlmBridge? = null
    private val chatBridgeMutex = Mutex()

    /** Chat engine for multi-turn conversation with streaming. */
    val chatEngine = ChatEngine()

    // ── Current active components ──
    val currentSpeech: SpeechRecognizer
        get() = when (_mode.value) {
            AssistantMode.OFFLINE -> offlineSpeech
            AssistantMode.ONLINE -> onlineSpeech
        }

    private val currentPlanner: CommandPlanner
        get() = when (_mode.value) {
            AssistantMode.OFFLINE -> offlinePlanner
            AssistantMode.ONLINE -> onlinePlanner
        }

    // ── Initialization ──

    suspend fun initializeOffline() {
        android.util.Log.d("AssistantEngine", "initializeOffline() start")
        modelRepository.refreshStatuses(context)
        try {
            offlineSpeech.initializeWithContext(context)
        } catch (e: Exception) {
            android.util.Log.d("AssistantEngine", "Vosk init failed: ${e.message}", e)
        }
        try {
            offlinePlanner.initialize(context)
            android.util.Log.d("AssistantEngine", "Planner init done, isReady=${offlinePlanner.isReady}")
        } catch (e: Exception) {
            android.util.Log.d("AssistantEngine", "Planner init failed: ${e.message}", e)
        }
    }

    fun configureOnline(settings: AssistantSettings) {
        this.settings = settings
        onlinePlanner.configure(settings)
        onlineSpeech.settings = settings
    }

    // ── Mode switching ──

    suspend fun switchMode(newMode: AssistantMode) {
        if (_mode.value == newMode) return
        currentSpeech.cancel()
        _mode.value = newMode
        when (newMode) {
            AssistantMode.OFFLINE -> {
                if (voskModelManager.state.value != VoskModelManager.ModelState.READY) {
                    offlineSpeech.initializeWithContext(context)
                }
                if (!offlinePlanner.isReady) {
                    offlinePlanner.initialize(context)
                }
            }
            AssistantMode.ONLINE -> {
                onlineSpeech.initialize()
            }
        }
    }

    fun switchTaskMode(newTaskMode: TaskMode) {
        _taskMode.value = newTaskMode
    }

    /**
     * Returns true if the current configuration supports device operation execution.
     * Requires either online mode, or an offline model with the Planner capability tag.
     */
    fun canExecuteDeviceOps(): Boolean =
        _mode.value == AssistantMode.ONLINE
            || (_mode.value == AssistantMode.OFFLINE && _activeModel.value.isPlanner())

    // ── Model switching ──

    suspend fun switchModel(newModel: ModelInfo) {
        chatBridgeMutex.withLock {
            chatBridge?.release()
            chatBridge = null
        }
        chatEngine.clearHistory()
        _activeModel.value = newModel

        // If the new model is a planner, reinitialize the planner with it
        if (newModel.isPlanner()) {
            offlinePlanner.release()
            offlinePlanner.initialize(context, newModel)
        }
    }

    /**
     * Get or create the chat bridge for the active model.
     * Used by ChatEngine for non-planner chat generation.
     */
    suspend fun getChatBridge(): MnnLlmBridge = chatBridgeMutex.withLock {
        chatBridge?.let { if (it.isLoaded) return@withLock it }

        val model = _activeModel.value
        val modelDir = modelRepository.getModelDir(context, model)
        if (!modelRepository.isModelReady(context, model)) {
            throw IllegalStateException("模型 ${model.name} 未下载")
        }

        val isR1 = model.isThinking()
        val extraConfig = buildString {
            append("""{"mmap_dir":"","keep_history":true,"is_r1":""")
            append(isR1)
            append("}")
        }
        val mergedConfig = buildChatMergedConfig(modelDir)

        val bridge = MnnLlmBridge()
        withContext(Dispatchers.IO) {
            bridge.load(
                configPath = modelDir.absolutePath,
                mergedConfig = mergedConfig,
                extraConfig = extraConfig
            )
        }
        chatBridge = bridge
        bridge
    }

    // ── Command processing ──

    suspend fun processCommand(command: String): String {
        val plan = currentPlanner.plan(command)
        val results = if (plan.actions.isNotEmpty()) {
            actionExecutor.executeAll(plan.actions)
        } else {
            emptyList()
        }

        return buildString {
            append(plan.reply)
            if (results.isNotEmpty()) {
                append("\n\n执行结果：")
                results.forEach { append("\n- ").append(it) }
            }
        }
    }

    private fun buildChatMergedConfig(modelDir: File): String {
        val merged = runCatching {
            val configFile = File(modelDir, "config.json")
            if (configFile.exists()) {
                JSONObject(configFile.readText())
            } else {
                JSONObject()
            }
        }.getOrDefault(JSONObject())

        if (!merged.has("system_prompt")) {
            merged.put("system_prompt", "You are a helpful assistant.")
        }
        if (!merged.has("sampler_type")) {
            merged.put("sampler_type", "")
        }
        if (!merged.has("mixed_samplers")) {
            merged.put("mixed_samplers", JSONArray(listOf("topK", "topP", "minP", "temperature")))
        }
        if (!merged.has("temperature")) {
            merged.put("temperature", 0.6)
        }
        if (!merged.has("topP")) {
            merged.put("topP", 0.95)
        }
        if (!merged.has("topK")) {
            merged.put("topK", 20)
        }
        if (!merged.has("minP")) {
            merged.put("minP", 0.05)
        }
        if (!merged.has("penalty")) {
            merged.put("penalty", 1.02)
        }
        if (!merged.has("n_gram")) {
            merged.put("n_gram", 8)
        }
        if (!merged.has("ngram_factor")) {
            merged.put("ngram_factor", 1.02)
        }
        if (!merged.has("max_new_tokens")) {
            merged.put("max_new_tokens", 2048)
        }
        if (!merged.has("penalty_sampler")) {
            merged.put("penalty_sampler", "greedy")
        }
        return merged.toString()
    }

    /**
     * Generate a chat response with streaming.
     * Uses MNN bridge for offline mode, Qwen API SSE for online mode.
     */
    suspend fun generateChat(message: String): String {
        return if (_mode.value == AssistantMode.ONLINE) {
            generateOnlineChat(message)
        } else {
            val bridge = getChatBridge()
            chatEngine.generate(bridge, message)
        }
    }

    private val onlineJson = Json { ignoreUnknownKeys = true; isLenient = true }
    private val onlineClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * Online chat using Qwen API with SSE streaming.
     */
    private suspend fun generateOnlineChat(message: String): String {
        val currentSettings = settings
        if (currentSettings.apiKey.isBlank()) {
            throw IllegalStateException("请先在设置页填写并保存 Qwen API Key。")
        }

        return chatEngine.generateStreaming(message) { history, onToken ->
            val messages = buildList {
                add(ChatCompletionMessage(role = "system", content = "你是一个有用的AI助手。请用简洁准确的中文回答用户的问题。"))
                addAll(history.map { (role, content) ->
                    ChatCompletionMessage(role = role, content = content)
                })
            }

            val requestPayload = ChatCompletionRequest(
                model = currentSettings.model.ifBlank { DEFAULT_QWEN_MODEL },
                messages = messages,
                stream = true
            )

            val request = Request.Builder()
                .url(QWEN_ENDPOINT)
                .addHeader("Authorization", "Bearer ${currentSettings.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(
                    onlineJson.encodeToString(ChatCompletionRequest.serializer(), requestPayload)
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                )
                .build()

            withContext(Dispatchers.IO) {
                onlineClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        throw IllegalStateException("Qwen 请求失败: ${response.code} ${response.message}\n$body")
                    }

                    val reader: BufferedReader = response.body!!.charStream().buffered()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line!!.trim()
                        if (!trimmed.startsWith("data:")) continue
                        val data = trimmed.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        runCatching {
                            val chunk = onlineJson.decodeFromString(
                                ChatCompletionChunk.serializer(), data
                            )
                            val content = chunk.choices.firstOrNull()?.delta?.content
                            if (!content.isNullOrEmpty()) {
                                val stopRequested = onToken(content)
                                if (stopRequested) return@use
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val QWEN_ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
    }

    fun stopChatGeneration() {
        chatEngine.stopGeneration()
    }

    // ── Cleanup ──

    fun release() {
        offlineSpeech.release()
        offlinePlanner.release()
        chatBridge?.release()
        chatBridge = null
        onlineSpeech.release()
    }
}
