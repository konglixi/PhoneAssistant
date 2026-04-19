package dev.phoneassistant.offline.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.phoneassistant.domain.speech.SpeechRecognizer
import dev.phoneassistant.domain.speech.SpeechState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import kotlin.math.sqrt

class VoskSpeechRecognizer(
    private val modelManager: VoskModelManager
) : SpeechRecognizer {

    private val _state = MutableStateFlow(SpeechState.IDLE)
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    override val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val finalResult: SharedFlow<String> = _finalResult.asSharedFlow()

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var modelPath: File? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        if (model != null) return@withContext
        _state.value = SpeechState.INITIALIZING
        try {
            val path = modelPath ?: throw IllegalStateException("Vosk model path not set, call initializeWithPath first")
            model = Model(path.absolutePath)
            _state.value = SpeechState.IDLE
        } catch (e: Exception) {
            _state.value = SpeechState.ERROR
            throw e
        }
    }

    suspend fun initializeWithContext(context: android.content.Context) {
        val path = modelManager.ensureModel(context)
        modelPath = path
        initialize()
    }

    override fun startListening() {
        val currentModel = model ?: run {
            _state.value = SpeechState.ERROR
            return
        }

        recordingJob?.cancel()
        recognizer?.close()

        recognizer = Recognizer(currentModel, SAMPLE_RATE.toFloat())
        _partialResult.value = ""
        _amplitude.value = 0f
        _state.value = SpeechState.LISTENING

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ),
            BUFFER_SIZE_SAMPLES * 2
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).also { it.startRecording() }

        recordingJob = scope.launch {
            val buffer = ShortArray(BUFFER_SIZE_SAMPLES)
            val byteBuffer = ByteArray(BUFFER_SIZE_SAMPLES * 2)

            while (isActive && _state.value == SpeechState.LISTENING) {
                val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (shortsRead <= 0) continue

                var sum = 0.0
                for (i in 0 until shortsRead) {
                    sum += buffer[i].toDouble() * buffer[i].toDouble()
                }
                val rms = sqrt(sum / shortsRead).toFloat()
                _amplitude.value = (rms / Short.MAX_VALUE * 3f).coerceIn(0f, 1f)

                for (i in 0 until shortsRead) {
                    byteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                    byteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                }

                val rec = recognizer ?: break
                if (rec.acceptWaveForm(byteBuffer, shortsRead * 2)) {
                    val result = rec.result
                    val text = parseVoskText(result)
                    if (text.isNotBlank()) {
                        _partialResult.value = text
                    }
                } else {
                    val partial = rec.partialResult
                    val text = parseVoskPartial(partial)
                    if (text.isNotBlank()) {
                        _partialResult.value = text
                    }
                }
            }
        }
    }

    override fun stopListening() {
        _state.value = SpeechState.PROCESSING
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        scope.launch {
            val rec = recognizer
            if (rec != null) {
                val finalText = parseVoskText(rec.finalResult)
                _amplitude.value = 0f
                _state.value = SpeechState.IDLE
                _finalResult.emit(finalText)
            } else {
                _amplitude.value = 0f
                _state.value = SpeechState.IDLE
                _finalResult.emit("")
            }
            recognizer?.close()
            recognizer = null
        }
    }

    override fun cancel() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recognizer?.close()
        recognizer = null
        _amplitude.value = 0f
        _partialResult.value = ""
        _state.value = SpeechState.IDLE
    }

    override fun release() {
        cancel()
        model?.close()
        model = null
        scope.cancel()
    }

    private fun parseVoskText(json: String): String {
        return try {
            JSONObject(json).optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            JSONObject(json).optString("partial", "")
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_SAMPLES = 4096
    }
}
