package com.hashmi.familylink.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationTest {

    @Test
    fun testNotificationSerialization() {
        val notification = StreamMessage.Notification(
            id = "123",
            packageName = "com.test",
            title = "Title",
            text = "Text",
            timestamp = 1000L
        )
        
        val json = Json.encodeToString<StreamMessage>(notification)
        val decoded = Json.decodeFromString<StreamMessage>(json)
        
        assertEquals(notification, decoded)
    }
    
    @Test
    fun testConnectedSerialization() {
        val message = StreamMessage.Connected("id", "name")
        val json = Json.encodeToString<StreamMessage>(message)
        val decoded = Json.decodeFromString<StreamMessage>(json)
        
        assertEquals(message, decoded)
    }
}
