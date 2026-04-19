package dev.phoneassistant.domain

import android.content.Context
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.data.model.AssistantSettings
import dev.phoneassistant.data.model.ModelCatalog
import dev.phoneassistant.data.model.ModelInfo
import dev.phoneassistant.data.model.ModelRepository
import dev.phoneassistant.data.model.isPlanner
import dev.phoneassistant.domain.chat.ChatEngine
import dev.phoneassistant.domain.chat.StreamingState
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

    // ── Mode ──
    private val _mode = MutableStateFlow(AssistantMode.OFFLINE)
    val mode: StateFlow<AssistantMode> = _mode.asStateFlow()

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

    // ── Model switching ──

    suspend fun switchModel(newModel: ModelInfo) {
        chatBridgeMutex.withLock {
            chatBridge?.release()
            chatBridge = null
        }
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

        val bridge = MnnLlmBridge()
        withContext(Dispatchers.IO) {
            bridge.load(
                configPath = modelDir.absolutePath,
                mergedConfig = "{}",
                extraConfig = """{"mmap_dir":""}"""
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

    /**
     * Generate a chat response using the active model with streaming.
     * Used for non-planner models in chat mode.
     */
    suspend fun generateChat(message: String): String {
        val bridge = getChatBridge()
        return chatEngine.generate(bridge, message)
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
