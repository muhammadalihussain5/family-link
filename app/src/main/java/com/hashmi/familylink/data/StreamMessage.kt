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
        val serverName: String = ""
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

    @Serializable
    data class TapEvent(
        val x: Float,
        val y: Float
    ) : StreamMessage()

    @Serializable
    data object Heartbeat : StreamMessage()
}
