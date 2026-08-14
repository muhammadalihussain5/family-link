package com.hashmi.familylink.data

import kotlinx.serialization.Serializable

@Serializable
sealed class StreamMessage {
    @Serializable
    data class Connected(val deviceId: String, val deviceName: String) : StreamMessage()
    
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
    ) : StreamMessage()

    @Serializable
    data class TapEvent(
        val x: Float,
        val y: Float
    ) : StreamMessage()
    
    @Serializable
    data object Heartbeat : StreamMessage()
}
