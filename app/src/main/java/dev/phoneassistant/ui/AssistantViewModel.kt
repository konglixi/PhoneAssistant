package dev.phoneassistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.phoneassistant.data.model.AssistantMode
import dev.phoneassistant.data.model.AssistantSettings
import dev.phoneassistant.data.model.ChatMessage
import dev.phoneassistant.data.model.ChatRole
import dev.phoneassistant.data.model.DEFAULT_QWEN_MODEL
import dev.phoneassistant.data.model.ModelCatalog
import dev.phoneassistant.data.model.ModelInfo
import dev.phoneassistant.data.model.ModelRepository
import dev.phoneassistant.data.model.isPlanner
import dev.phoneassistant.data.preference.PreferenceStore
import dev.phoneassistant.domain.AssistantEngine
import dev.phoneassistant.domain.speech.SpeechState
import dev.phoneassistant.offline.speech.VoskModelManager
import dev.phoneassistant.service.AssistantAccessibilityService
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoiceUiState {
    IDLE, LISTENING, PROCESSING, ERROR
}

data class AssistantUiState(
    val apiKey: String = "",
    val model: String = DEFAULT_QWEN_MODEL,
    val mode: AssistantMode = AssistantMode.OFFLINE,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            id = 1,
            role = ChatRole.SYSTEM,
            content = "先在设置页保存 Qwen API Key（在线模式需要），再开启无障碍服务。之后你可以说：打开微信、返回桌面、下滑通知栏、点击发送、输入你好。"
        )
    ),
    val isLoading: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val draft: String = "",
    // Voice
    val voiceUiState: VoiceUiState = VoiceUiState.IDLE,
    val isRecording: Boolean = false,
    val voiceAutoSend: Boolean = false,
    val partialTranscription: String = "",
    val voiceAmplitude: Float = 0f,
    val voiceError: String? = null,
    // Model states
    val isVoskModelReady: Boolean = false,
    val voskDownloadProgress: Float = 0f,
    val isMnnModelReady: Boolean = false,
    val mnnDownloadProgress: Float = 0f,
    // Active model
    val activeModelId: String? = "qwen2.5-0.5b",
    // Chat input bar state (Phase 2)
    val isThinkingEnabled: Boolean = false,
    val attachedImages: List<Uri> = emptyList(),
    val attachedAudio: Uri? = null,
    val attachedVideo: Uri? = null,
    val isAttachmentMenuOpen: Boolean = false,
    val isGenerating: Boolean = false,
    // Streaming (Phase 3)
    val streamingText: String = "",
    val streamingThinkingText: String = "",
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val preferenceStore = PreferenceStore(application)
    private val engine = AssistantEngine(application)

    private val nextMessageId = AtomicLong(2)

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    /** Model download/status map exposed for ModelListScreen */
    val modelStatusMap: StateFlow<Map<String, ModelRepository.ModelDownloadStatus>> =
        engine.modelRepository.statusMap

    // Jobs for observing the current speech recognizer's flows
    private var speechObserverJobs = mutableListOf<Job>()

    init {
        viewModelScope.launch {
            preferenceStore.settingsFlow.collect { settings ->
                _uiState.update {
                    it.copy(
                        apiKey = settings.apiKey,
                        model = settings.model,
                        mode = settings.mode
                    )
                }
                engine.configureOnline(settings)
                if (engine.mode.value != settings.mode) {
                    engine.switchMode(settings.mode)
                    rebindSpeechObservers()
                }
            }
        }
        refreshAccessibilityStatus()
        observeModelManagers()
        observeEngineMode()
        observeActiveModel()
        bindSpeechObservers()
        initializeCurrentMode()
    }

    // ── Initialization ──

    private fun initializeCurrentMode() {
        viewModelScope.launch {
            try {
                when (engine.mode.value) {
                    AssistantMode.OFFLINE -> engine.initializeOffline()
                    AssistantMode.ONLINE -> engine.currentSpeech.initialize()
                }
            } catch (e: Exception) {
                android.util.Log.w("AssistantViewModel", "Init failed: ${e.message}", e)
            }
        }
    }

    // ── Model manager observers ──

    private fun observeModelManagers() {
        viewModelScope.launch {
            engine.voskModelManager.state.collect { state ->
                _uiState.update {
                    it.copy(isVoskModelReady = state == VoskModelManager.ModelState.READY)
                }
            }
        }
        viewModelScope.launch {
            engine.voskModelManager.downloadProgress.collect { progress ->
                _uiState.update { it.copy(voskDownloadProgress = progress) }
            }
        }
        // Observe model repository for the planner model status
        viewModelScope.launch {
            engine.modelRepository.statusMap.collect { statusMap ->
                val plannerStatus = statusMap["qwen2.5-0.5b"]
                _uiState.update {
                    it.copy(
                        isMnnModelReady = plannerStatus?.state == ModelRepository.DownloadState.READY,
                        mnnDownloadProgress = plannerStatus?.progress ?: 0f
                    )
                }
            }
        }
    }

    // ── Engine mode observer ──

    private fun observeEngineMode() {
        viewModelScope.launch {
            engine.mode.collect { mode ->
                _uiState.update { it.copy(mode = mode) }
            }
        }
    }

    // ── Active model observer ──

    private fun observeActiveModel() {
        viewModelScope.launch {
            engine.activeModel.collect { model ->
                _uiState.update { it.copy(activeModelId = model.id) }
            }
        }
    }

    // ── Speech recognizer observers ──

    private fun bindSpeechObservers() {
        val speech = engine.currentSpeech

        speechObserverJobs += viewModelScope.launch {
            speech.state.collect { state ->
                _uiState.update {
                    it.copy(
                        voiceUiState = when (state) {
                            SpeechState.IDLE -> VoiceUiState.IDLE
                            SpeechState.INITIALIZING -> VoiceUiState.LISTENING
                            SpeechState.LISTENING -> VoiceUiState.LISTENING
                            SpeechState.PROCESSING -> VoiceUiState.PROCESSING
                            SpeechState.ERROR -> VoiceUiState.ERROR
                        },
                        isRecording = state == SpeechState.LISTENING || state == SpeechState.INITIALIZING
                    )
                }
            }
        }
        speechObserverJobs += viewModelScope.launch {
            speech.partialResult.collect { text ->
                _uiState.update { it.copy(partialTranscription = text) }
            }
        }
        speechObserverJobs += viewModelScope.launch {
            speech.amplitude.collect { amp ->
                _uiState.update { it.copy(voiceAmplitude = amp) }
            }
        }
        speechObserverJobs += viewModelScope.launch {
            speech.finalResult.collect { text ->
                val autoSend = _uiState.value.voiceAutoSend
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        partialTranscription = "",
                        voiceAutoSend = false
                    )
                }
                if (text.isNotBlank()) {
                    if (autoSend) {
                        sendCommand(text)
                    } else {
                        _uiState.update { it.copy(draft = text) }
                    }
                }
            }
        }
    }

    private fun rebindSpeechObservers() {
        speechObserverJobs.forEach { it.cancel() }
        speechObserverJobs.clear()
        bindSpeechObservers()
    }

    // ── Public actions ──

    fun refreshAccessibilityStatus() {
        _uiState.update {
            it.copy(isAccessibilityEnabled = AssistantAccessibilityService.isConnected())
        }
    }

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun saveSettings(apiKey: String, model: String) {
        viewModelScope.launch {
            preferenceStore.saveSettings(apiKey, model)
            appendAssistantMessage("配置已保存，当前模型：${model.ifBlank { DEFAULT_QWEN_MODEL }}")
        }
    }

    fun switchMode(mode: AssistantMode) {
        viewModelScope.launch {
            try {
                preferenceStore.saveMode(mode)
                engine.switchMode(mode)
                rebindSpeechObservers()
                val modeName = when (mode) {
                    AssistantMode.OFFLINE -> "离线模式"
                    AssistantMode.ONLINE -> "在线模式"
                }
                appendAssistantMessage("已切换到$modeName")
            } catch (e: Exception) {
                appendAssistantMessage("切换模式失败：${e.message}")
            }
        }
    }

    fun startRecording(autoSend: Boolean) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRecording = true,
                    voiceAutoSend = autoSend,
                    voiceUiState = VoiceUiState.LISTENING,
                    partialTranscription = "",
                    voiceError = null
                )
            }
            try {
                val speech = engine.currentSpeech
                if (_uiState.value.mode == AssistantMode.OFFLINE) {
                    if (!_uiState.value.isVoskModelReady) {
                        val voskRecognizer = speech as? dev.phoneassistant.offline.speech.VoskSpeechRecognizer
                        voskRecognizer?.initializeWithContext(getApplication())
                    }
                } else {
                    speech.initialize()
                }
                speech.startListening()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        voiceUiState = VoiceUiState.ERROR,
                        voiceError = e.message ?: "语音识别初始化失败"
                    )
                }
            }
        }
    }

    fun stopRecording() {
        engine.currentSpeech.stopListening()
    }

    fun cancelRecording() {
        engine.currentSpeech.cancel()
        _uiState.update {
            it.copy(
                isRecording = false,
                voiceUiState = VoiceUiState.IDLE,
                partialTranscription = "",
                voiceError = null,
                voiceAutoSend = false
            )
        }
    }

    // ── Model management ──

    fun downloadModel(model: ModelInfo) {
        viewModelScope.launch {
            try {
                engine.modelRepository.ensureModel(getApplication(), model)
                // After successful download, try to initialize planner if this is the active planner model
                if (model.isPlanner() && model.id == _uiState.value.activeModelId) {
                    engine.initializeOffline()
                }
            } catch (e: Exception) {
                appendAssistantMessage("模型 ${model.name} 下载失败：${e.message}")
            }
        }
    }

    fun deleteModel(model: ModelInfo) {
        viewModelScope.launch {
            try {
                // If this is the active model, don't allow deletion
                if (model.id == _uiState.value.activeModelId) {
                    appendAssistantMessage("无法删除当前正在使用的模型")
                    return@launch
                }
                engine.modelRepository.deleteModel(getApplication(), model)
                appendAssistantMessage("已删除模型 ${model.name}")
            } catch (e: Exception) {
                appendAssistantMessage("删除失败：${e.message}")
            }
        }
    }

    fun activateModel(model: ModelInfo) {
        viewModelScope.launch {
            try {
                engine.switchModel(model)
                appendAssistantMessage("已切换到模型 ${model.name}")
            } catch (e: Exception) {
                appendAssistantMessage("切换模型失败：${e.message}")
            }
        }
    }

    // ── Attachment management (Phase 2) ──

    fun toggleAttachmentMenu() {
        _uiState.update { it.copy(isAttachmentMenuOpen = !it.isAttachmentMenuOpen) }
    }

    fun addImageAttachment(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages + uri) }
    }

    fun removeImageAttachment(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages - uri) }
    }

    fun setAudioAttachment(uri: Uri?) {
        _uiState.update { it.copy(attachedAudio = uri) }
    }

    fun setVideoAttachment(uri: Uri?) {
        _uiState.update { it.copy(attachedVideo = uri) }
    }

    fun clearAttachments() {
        _uiState.update {
            it.copy(attachedImages = emptyList(), attachedAudio = null, attachedVideo = null)
        }
    }

    fun toggleThinking() {
        _uiState.update { it.copy(isThinkingEnabled = !it.isThinkingEnabled) }
    }

    fun stopGeneration() {
        engine.stopChatGeneration()
        _uiState.update { it.copy(isGenerating = false) }
    }

    // ── Command sending ──

    fun sendCommand(rawCommand: String = uiState.value.draft) {
        val command = rawCommand.trim()
        if (command.isBlank() && _uiState.value.attachedImages.isEmpty()
            && _uiState.value.attachedAudio == null && _uiState.value.attachedVideo == null
        ) return

        appendMessage(ChatRole.USER, command)
        _uiState.update {
            it.copy(
                isLoading = true,
                isGenerating = true,
                draft = "",
                isAttachmentMenuOpen = false
            )
        }

        // Check API key only for online mode
        if (_uiState.value.mode == AssistantMode.ONLINE && _uiState.value.apiKey.isBlank()) {
            appendAssistantMessage("在线模式需要在设置页填写并保存 Qwen API Key。")
            _uiState.update { it.copy(isLoading = false, isGenerating = false) }
            return
        }

        val activeModel = _uiState.value.activeModelId?.let { ModelCatalog.findById(it) }
        val isCommandMode = activeModel?.isPlanner() == true
            || _uiState.value.mode == AssistantMode.ONLINE

        viewModelScope.launch {
            if (isCommandMode) {
                // Command/planner mode — single turn, JSON parse, action execution
                runCatching {
                    engine.processCommand(command)
                }.onSuccess { message ->
                    appendAssistantMessage(message)
                }.onFailure { throwable ->
                    appendAssistantMessage(throwable.message ?: "执行失败")
                }
            } else {
                // Chat mode — streaming multi-turn with ChatEngine
                val streamingMsgId = nextMessageId.getAndIncrement()
                // Add a placeholder streaming message
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatMessage(
                            id = streamingMsgId,
                            role = ChatRole.ASSISTANT,
                            content = "",
                            isStreaming = true
                        )
                    )
                }

                // Observe streaming state
                val streamJob = viewModelScope.launch {
                    engine.chatEngine.streamingState.collect { streaming ->
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == streamingMsgId) {
                                        msg.copy(
                                            content = streaming.normalText,
                                            thinkingText = streaming.thinkingText.ifBlank { null },
                                            isStreaming = streaming.isStreaming,
                                            perfMetrics = streaming.perfMetrics
                                        )
                                    } else msg
                                }
                            )
                        }
                    }
                }

                runCatching {
                    engine.generateChat(command)
                }.onFailure { throwable ->
                    // Update the streaming message with error
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map { msg ->
                                if (msg.id == streamingMsgId) {
                                    msg.copy(
                                        content = throwable.message ?: "生成失败",
                                        isStreaming = false
                                    )
                                } else msg
                            }
                        )
                    }
                }

                streamJob.cancel()
            }

            clearAttachments()
            refreshAccessibilityStatus()
            _uiState.update { it.copy(isLoading = false, isGenerating = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.release()
    }

    // ── Internal helpers ──

    private fun appendAssistantMessage(content: String) {
        appendMessage(ChatRole.ASSISTANT, content)
    }

    private fun appendMessage(role: ChatRole, content: String) {
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    id = nextMessageId.getAndIncrement(),
                    role = role,
                    content = content
                )
            )
        }
    }
}
