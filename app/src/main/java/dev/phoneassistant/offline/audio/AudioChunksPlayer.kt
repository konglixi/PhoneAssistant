package dev.phoneassistant.offline.audio

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Streaming audio player using Android's AudioTrack API.
 * Designed for real-time TTS/omni model audio output.
 *
 * Ported from MnnLlmChat AudioChunksPlayer.
 */
class AudioChunksPlayer {
    private var audioTrack: AudioTrack? = null
    private var _audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var _sampleRate = 44100
    private var channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private var totalSize = 0
    private var onCompletionListener: (() -> Unit)? = null
    private var isEndChunkCalled = false
    private var finalTotalSize = 0
    private val executor: ThreadPoolExecutor =
        ThreadPoolExecutor(1, 5, 2L, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            this.allowCoreThreadTimeOut(true)
        }
    private var playerScope = CoroutineScope(executor.asCoroutineDispatcher() + SupervisorJob())

    var sampleRate: Int
        get() = _sampleRate
        set(value) {
            if (value > 0) _sampleRate = value
        }

    var audioFormat: Int
        get() = _audioFormat
        set(value) { _audioFormat = value }

    fun start() {
        Log.d(TAG, "start play audio sampleRate: $_sampleRate audioFormat: $_audioFormat")
        totalSize = 0
        isEndChunkCalled = false
        finalTotalSize = 0
        if (isPlaying) return

        val minBufferSize = AudioTrack.getMinBufferSize(_sampleRate, channelConfig, audioFormat)
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(_sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .build()

        if (state != AudioTrack.STATE_UNINITIALIZED) {
            play()
        } else {
            throw RuntimeException("Failed to initialize AudioTrack")
        }
    }

    fun play() {
        if (audioTrack != null && isInited && !isPlaying) {
            audioTrack!!.play()
        }
    }

    suspend fun waitStop() {
        if (audioTrack == null) return
        if (audioTrack!!.playbackHeadPosition == totalSize) return

        suspendCoroutine { continuation ->
            audioTrack!!.setNotificationMarkerPosition(totalSize)
            audioTrack!!.setPlaybackPositionUpdateListener(object :
                AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onPeriodicNotification(track: AudioTrack?) {}
                override fun onMarkerReached(track: AudioTrack?) {
                    continuation.resume(true)
                }
            })
        }
    }

    fun stop() {
        if (audioTrack != null && isInited && !isStopped) {
            audioTrack!!.stop()
            audioTrack!!.release()
            audioTrack = null
        }
    }

    suspend fun playChunk(pcmData: FloatArray) {
        totalSize += pcmData.size
        try {
            playerScope.async {
                audioTrack?.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "playChunk: ", e)
        }
    }

    suspend fun playChunk(pcmData: ShortArray) {
        totalSize += pcmData.size
        try {
            playerScope.async {
                audioTrack?.write(pcmData, 0, pcmData.size, AudioTrack.WRITE_BLOCKING)
            }.await()
        } catch (e: Exception) {
            Log.e(TAG, "playChunk: ", e)
        }
    }

    fun reset() {
        val savedListener = onCompletionListener
        stop()
        totalSize = 0
        isEndChunkCalled = false
        finalTotalSize = 0
        start()
        onCompletionListener = savedListener
    }

    fun destroy() {
        totalSize = 0
        isEndChunkCalled = false
        finalTotalSize = 0
        if (audioTrack != null && isInited && !isStopped) {
            audioTrack!!.pause()
            audioTrack!!.flush()
            audioTrack!!.stop()
            audioTrack!!.release()
            audioTrack = null
        }
        executor.shutdown()
    }

    val isPlaying: Boolean
        get() = (playState == AudioTrack.PLAYSTATE_PLAYING)

    val isStopped: Boolean
        get() = (playState == AudioTrack.PLAYSTATE_STOPPED)

    val isPaused: Boolean
        get() = (playState == AudioTrack.PLAYSTATE_PAUSED)

    private val isInited: Boolean
        get() = (state == AudioTrack.STATE_INITIALIZED)

    val playState: Int
        get() = if (audioTrack != null && isInited) audioTrack!!.playState else AudioTrack.ERROR

    val state: Int
        get() = audioTrack?.state ?: AudioTrack.STATE_UNINITIALIZED

    fun currentTime(): Long {
        return if (audioTrack != null && isInited && isPlaying) {
            (audioTrack!!.playbackHeadPosition * 1000.0 / _sampleRate).toLong()
        } else 0
    }

    fun totalTime(): Long {
        return if (isPlaying) ((totalSize * 1000L) / _sampleRate) else 0
    }

    fun convertToShortArray(samples: FloatArray): ShortArray {
        return ShortArray(samples.size) { (samples[it] * 32767).toInt().toShort() }
    }

    fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    fun endChunk() {
        if (!isEndChunkCalled) {
            isEndChunkCalled = true
            finalTotalSize = totalSize
            if (finalTotalSize > 0 && audioTrack != null && isInited) {
                audioTrack!!.setNotificationMarkerPosition(finalTotalSize)
                audioTrack!!.setPlaybackPositionUpdateListener(object :
                    AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                    override fun onMarkerReached(track: AudioTrack?) {
                        if (track?.state != AudioTrack.STATE_UNINITIALIZED) {
                            onCompletionListener?.invoke()
                        }
                    }
                })
            } else if (finalTotalSize == 0) {
                onCompletionListener?.invoke()
            }
        }
    }

    companion object {
        private const val TAG = "AudioChunksPlayer"
    }
}
