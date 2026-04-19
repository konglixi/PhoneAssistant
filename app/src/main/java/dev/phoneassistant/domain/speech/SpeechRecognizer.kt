package dev.phoneassistant.domain.speech

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class SpeechState {
    IDLE, INITIALIZING, LISTENING, PROCESSING, ERROR
}

interface SpeechRecognizer {
    val state: StateFlow<SpeechState>
    val partialResult: StateFlow<String>
    val finalResult: SharedFlow<String>
    val amplitude: StateFlow<Float>

    suspend fun initialize()
    fun startListening()
    fun stopListening()
    fun cancel()
    fun release()
}
