package dev.phoneassistant.domain.voice

import dev.phoneassistant.domain.chat.ChatEngine
import dev.phoneassistant.domain.speech.SpeechRecognizer
import dev.phoneassistant.offline.audio.AudioChunksPlayer
import dev.phoneassistant.offline.planner.MnnLlmBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the ASR → LLM → TTS voice chat pipeline.
 *
 * Flow:
 * 1. User speaks → SpeechRecognizer produces text
 * 2. Text → ChatEngine → LLM generates response
 * 3. If model supports audio output, TTS audio is played via AudioChunksPlayer
 */
class VoiceChatEngine(
    private val chatEngine: ChatEngine
) {
    enum class PipelineState {
        IDLE, LISTENING, PROCESSING_ASR, GENERATING_LLM, PLAYING_TTS
    }

    private val _state = MutableStateFlow(PipelineState.IDLE)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private var audioPlayer: AudioChunksPlayer? = null

    /**
     * Run the full voice chat pipeline.
     * @param transcribedText The ASR output text
     * @param bridge The MNN LLM bridge to use
     * @param enableAudioOutput Whether to play TTS audio output
     */
    suspend fun processVoiceInput(
        transcribedText: String,
        bridge: MnnLlmBridge,
        enableAudioOutput: Boolean = false
    ): String {
        _state.value = PipelineState.GENERATING_LLM

        try {
            val response = chatEngine.generate(bridge, transcribedText)

            if (enableAudioOutput) {
                _state.value = PipelineState.PLAYING_TTS
                // Audio output is handled by the native layer through waveform callbacks
                // The AudioChunksPlayer receives PCM data via the callback
            }

            return response
        } finally {
            _state.value = PipelineState.IDLE
        }
    }

    /**
     * Initialize audio player for TTS output.
     */
    fun initAudioPlayer(sampleRate: Int = 24000): AudioChunksPlayer {
        audioPlayer?.destroy()
        val player = AudioChunksPlayer().apply {
            this.sampleRate = sampleRate
        }
        audioPlayer = player
        return player
    }

    fun stopAudioPlayback() {
        audioPlayer?.stop()
    }

    fun release() {
        audioPlayer?.destroy()
        audioPlayer = null
        _state.value = PipelineState.IDLE
    }
}
