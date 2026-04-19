package dev.phoneassistant.offline.audio

/**
 * Callback interface for receiving audio waveform data.
 * Used for visualizing audio output from omni models.
 */
fun interface AudioDataListener {
    /**
     * Called when audio waveform data is available.
     * @param data PCM audio samples
     * @param sampleRate Sample rate of the audio data
     */
    fun onAudioData(data: FloatArray, sampleRate: Int)
}
