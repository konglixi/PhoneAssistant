package dev.phoneassistant.online.speech

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import dev.phoneassistant.data.model.AssistantSettings
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Cloud-based speech recognizer using DashScope Paraformer ASR.
 * Shares the same API Key as Qwen LLM.
 *
 * Flow: record audio locally → stop → send PCM to Paraformer → get text
 */
class CloudSpeechRecognizer : SpeechRecognizer {

    private val _state = MutableStateFlow(SpeechState.IDLE)
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    override val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _finalResult = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val finalResult: SharedFlow<String> = _finalResult.asSharedFlow()

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile
    var settings: AssistantSettings = AssistantSettings()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioBuffer = ByteArrayOutputStream()

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun initialize() {
        _state.value = SpeechState.IDLE
    }

    override fun startListening() {
        _partialResult.value = ""
        _amplitude.value = 0f
        _state.value = SpeechState.LISTENING
        audioBuffer.reset()

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

            while (isActive && _state.value == SpeechState.LISTENING) {
                val shortsRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (shortsRead <= 0) continue

                // RMS amplitude for UI visualization
                var sum = 0.0
                for (i in 0 until shortsRead) {
                    sum += buffer[i].toDouble() * buffer[i].toDouble()
                }
                val rms = sqrt(sum / shortsRead).toFloat()
                _amplitude.value = (rms / Short.MAX_VALUE * 3f).coerceIn(0f, 1f)

                // Accumulate PCM audio data
                synchronized(audioBuffer) {
                    for (i in 0 until shortsRead) {
                        audioBuffer.write(buffer[i].toInt() and 0xFF)
                        audioBuffer.write(buffer[i].toInt() shr 8 and 0xFF)
                    }
                }

                _partialResult.value = "正在录音..."
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
            try {
                val audioData = synchronized(audioBuffer) {
                    audioBuffer.toByteArray()
                }
                _partialResult.value = "正在云端识别..."
                val text = transcribeAudio(audioData)
                _amplitude.value = 0f
                _state.value = SpeechState.IDLE
                _finalResult.emit(text)
            } catch (e: Exception) {
                _amplitude.value = 0f
                _state.value = SpeechState.ERROR
                _finalResult.emit("")
            }
        }
    }

    override fun cancel() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioBuffer.reset()
        _amplitude.value = 0f
        _partialResult.value = ""
        _state.value = SpeechState.IDLE
    }

    override fun release() {
        cancel()
        scope.cancel()
    }

    /**
     * Send recorded PCM audio to DashScope Paraformer for transcription.
     *
     * API doc: https://help.aliyun.com/zh/model-studio/developer-reference/paraformer-real-time-speech-recognition-api
     *
     * The audio is sent as base64-encoded WAV (PCM 16-bit, 16kHz, mono).
     */
    private suspend fun transcribeAudio(pcmData: ByteArray): String = withContext(Dispatchers.IO) {
        if (pcmData.isEmpty()) return@withContext ""

        val currentSettings = settings
        if (currentSettings.apiKey.isBlank()) {
            return@withContext ""
        }

        // Wrap raw PCM into a minimal WAV container
        val wavData = pcmToWav(pcmData, SAMPLE_RATE, 1, 16)
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        // Build DashScope Paraformer request
        val requestPayload = ParaformerRequest(
            model = "paraformer-v2",
            input = ParaformerInput(
                audio = base64Audio,
                format = "wav",
                sampleRate = SAMPLE_RATE,
                language = "zh"
            )
        )

        val requestBody = json.encodeToString(ParaformerRequest.serializer(), requestPayload)

        val request = Request.Builder()
            .url(PARAFORMER_ENDPOINT)
            .addHeader("Authorization", "Bearer ${currentSettings.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Paraformer 请求失败: ${response.code} ${response.message}\n$bodyString")
            }

            val result = json.decodeFromString(ParaformerResponse.serializer(), bodyString)
            result.output?.text.orEmpty()
        }
    }

    /**
     * Wrap raw PCM data into a WAV container.
     */
    private fun pcmToWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val dataSize = pcmData.size
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val wav = ByteArrayOutputStream(44 + dataSize)

        // RIFF header
        wav.write("RIFF".toByteArray())
        wav.write(intToLittleEndian(36 + dataSize))
        wav.write("WAVE".toByteArray())

        // fmt sub-chunk
        wav.write("fmt ".toByteArray())
        wav.write(intToLittleEndian(16))           // sub-chunk size
        wav.write(shortToLittleEndian(1))           // PCM format
        wav.write(shortToLittleEndian(channels))
        wav.write(intToLittleEndian(sampleRate))
        wav.write(intToLittleEndian(byteRate))
        wav.write(shortToLittleEndian(blockAlign))
        wav.write(shortToLittleEndian(bitsPerSample))

        // data sub-chunk
        wav.write("data".toByteArray())
        wav.write(intToLittleEndian(dataSize))
        wav.write(pcmData)

        return wav.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE_SAMPLES = 4096

        // DashScope Paraformer file transcription endpoint
        private const val PARAFORMER_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription"
    }
}

// ── DashScope Paraformer API models ──

@Serializable
internal data class ParaformerRequest(
    val model: String,
    val input: ParaformerInput
)

@Serializable
internal data class ParaformerInput(
    val audio: String,         // base64 encoded audio
    val format: String,        // "wav"
    @SerialName("sample_rate") val sampleRate: Int,
    val language: String = "zh"
)

@Serializable
internal data class ParaformerResponse(
    val output: ParaformerOutput? = null
)

@Serializable
internal data class ParaformerOutput(
    val text: String? = null
)
