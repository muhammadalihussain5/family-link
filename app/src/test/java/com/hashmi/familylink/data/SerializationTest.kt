package com.hashmi.familylink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializationTest {

    private val json = JsonConfig.json

    @Test
    fun notificationRoundTrip() {
        val notification = StreamMessage.Notification(
            id = "123",
            packageName = "com.test",
            title = "Title",
            text = "Text",
            timestamp = 1000L
        )

        val encoded = json.encodeToString(StreamMessage.serializer(), notification)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)

        assertEquals(notification, decoded)
        assertTrue(encoded.contains("\"type\""))
    }

    @Test
    fun connectedRoundTrip() {
        val message = StreamMessage.Connected("id", "name")
        val encoded = json.encodeToString(StreamMessage.serializer(), message)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun handshakeRoundTrip() {
        val message = StreamMessage.Handshake("dev-1", "AB12-CD34", "Pixel")
        val encoded = json.encodeToString(StreamMessage.serializer(), message)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun tapEventRoundTrip() {
        val message = StreamMessage.TapEvent(0.25f, 0.75f)
        val encoded = json.encodeToString(StreamMessage.serializer(), message)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)
        assertEquals(message, decoded)
    }

    @Test
    fun screenFrameUsesContentEquality() {
        val a = StreamMessage.ScreenFrame(byteArrayOf(1, 2, 3), 10, 20)
        val b = StreamMessage.ScreenFrame(byteArrayOf(1, 2, 3), 10, 20)
        val c = StreamMessage.ScreenFrame(byteArrayOf(9), 10, 20)
        assertEquals(a, b)
        assertNotEquals(a, c)

        val encoded = json.encodeToString(StreamMessage.serializer(), a)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)
        assertEquals(a, decoded)
    }

    @Test
    fun heartbeatRoundTrip() {
        val encoded = json.encodeToString(StreamMessage.serializer(), StreamMessage.Heartbeat)
        val decoded = json.decodeFromString(StreamMessage.serializer(), encoded)
        assertEquals(StreamMessage.Heartbeat, decoded)
    }

    @Test
    fun connectionInfoRoundTrip() {
        val info = ConnectionInfo("id", "192.168.1.8", 8080, "Hub")
        val encoded = json.encodeToString(ConnectionInfo.serializer(), info)
        val decoded = json.decodeFromString(ConnectionInfo.serializer(), encoded)
        assertEquals(info, decoded)
    }
}
