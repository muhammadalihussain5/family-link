package com.hashmi.familylink.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays the PCM chunks streamed from the linked device's audio capture
 * (see [com.hashmi.familylink.data.StreamMessage.AudioChunk]).
 */
object AudioPlayer {
    private const val TAG = "AudioPlayer"

    private var track: AudioTrack? = null

    val isPlaying: Boolean
        get() = track?.playState == AudioTrack.PLAYSTATE_PLAYING

    @Synchronized
    fun start(sampleRate: Int, inChannelMask: Int, encoding: Int) {
        if (isPlaying) return
        val outMask = when (inChannelMask) {
            AudioFormat.CHANNEL_IN_MONO -> AudioFormat.CHANNEL_OUT_MONO
            AudioFormat.CHANNEL_IN_STEREO -> AudioFormat.CHANNEL_OUT_STEREO
            else -> AudioFormat.CHANNEL_OUT_MONO
        }
        val safeRate = if (sampleRate in 8_000..48_000) sampleRate else 16_000
        val safeEncoding = if (encoding == AudioFormat.ENCODING_PCM_16BIT) encoding else AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioTrack.getMinBufferSize(safeRate, outMask, safeEncoding)
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(safeEncoding)
                        .setSampleRate(safeRate)
                        .setChannelMask(outMask)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuffer, 8_192) * 2)
                .build()
                .apply { play() }
            Log.d(TAG, "Playing linked audio ($safeRate Hz)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioTrack", e)
            track = null
        }
    }

    @Synchronized
    fun write(chunk: ByteArray) {
        val active = track ?: return
        try {
            active.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing audio", e)
        }
    }

    @Synchronized
    fun stop() {
        track?.apply {
            runCatching { pause() }
            runCatching { flush() }
            runCatching { release() }
        }
        track = null
        Log.d(TAG, "Linked audio stopped")
    }
}
