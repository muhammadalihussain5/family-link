package com.hashmi.familylink.data

import kotlinx.serialization.Serializable

@Serializable
sealed class StreamMessage {
    @Serializable
    data class Handshake(
        val deviceId: String,
        val pairingKey: String,
        val deviceName: String
    ) : StreamMessage()

    @Serializable
    data class HandshakeAck(
        val accepted: Boolean,
        val reason: String = "",
        val serverName: String = "",
        /** Stable id of the hub so the client knows who it paired with. */
        val serverDeviceId: String = "",
        /** The hub's own pairing key; the client stores it as the disconnect PIN. */
        val serverPairingKey: String = ""
    ) : StreamMessage()

    @Serializable
    data class Connected(
        val deviceId: String,
        val deviceName: String
    ) : StreamMessage()

    @Serializable
    data class Notification(
        val id: String,
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long
    ) : StreamMessage()

    @Serializable
    data class ScreenFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int
    ) : StreamMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ScreenFrame) return false
            return width == other.width &&
                height == other.height &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            return result
        }

        override fun toString(): String =
            "ScreenFrame(width=$width, height=$height, bytes=${data.size})"
    }

    /**
     * A chunk of PCM audio captured from the client device's playback
     * (Android 10+ audio playback capture). Raw bytes; [encoding] and
     * [channelMask] use the android.media.AudioFormat constants so the
     * hub can feed them straight into an AudioTrack.
     */
    @Serializable
    data class AudioChunk(
        val data: ByteArray,
        val sampleRate: Int,
        val encoding: Int,
        val channelMask: Int
    ) : StreamMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AudioChunk) return false
            return sampleRate == other.sampleRate &&
                encoding == other.encoding &&
                channelMask == other.channelMask &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + sampleRate
            result = 31 * result + encoding
            result = 31 * result + channelMask
            return result
        }

        override fun toString(): String =
            "AudioChunk(rate=$sampleRate, bytes=${data.size})"
    }

    /** Hub → client: start (or resume) screen sharing. */
    @Serializable
    data object StartScreenCapture : StreamMessage()

    /** Hub → client: pause screen sharing (the projection is kept alive). */
    @Serializable
    data object StopScreenCapture : StreamMessage()

    /** Client → hub: forget the pairing on the hub side. */
    @Serializable
    data class Unpair(
        val deviceId: String
    ) : StreamMessage()

    @Serializable
    data class TapEvent(
        val x: Float,
        val y: Float
    ) : StreamMessage()

    /**
     * A swipe/drag gesture from (startX, startY) to (endX, endY),
     * both in normalized 0..1 coordinates of the client screen.
     */
    @Serializable
    data class SwipeEvent(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 300L
    ) : StreamMessage()

    @Serializable
    data object Heartbeat : StreamMessage()
}
