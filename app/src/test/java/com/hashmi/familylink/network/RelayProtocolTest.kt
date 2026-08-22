package com.hashmi.familylink.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProtocolTest {

    @Test
    fun roomIsDeterministicAndCaseInsensitive() {
        assertEquals(RelayProtocol.roomFor("AB12-CD34"), RelayProtocol.roomFor("AB12-CD34"))
        assertEquals(RelayProtocol.roomFor("AB12-CD34"), RelayProtocol.roomFor("ab12-cd34"))
    }

    @Test
    fun differentKeysGetDifferentRoomsAndTokens() {
        assertNotEquals(RelayProtocol.roomFor("AB12-CD34"), RelayProtocol.roomFor("AB12-CD35"))
        assertNotEquals(RelayProtocol.tokenFor("AB12-CD34"), RelayProtocol.roomFor("AB12-CD34"))
    }

    @Test
    fun roomDoesNotLeakThePairingKey() {
        val room = RelayProtocol.roomFor("AB12-CD34")
        val token = RelayProtocol.tokenFor("AB12-CD34")
        assertFalse(room.contains("AB12"))
        assertFalse(token.contains("AB12"))
        // 24 hex characters for the room, 64 for the token.
        assertEquals(24, room.length)
        assertEquals(64, token.length)
    }

    @Test
    fun controlFramesAreDetectedButStreamMessagesAreNot() {
        val control = RelayProtocol.encodeControl(
            RelayControl(role = RelayProtocol.ROLE_HUB, room = "r", token = "t")
        )
        assertTrue(RelayProtocol.isControlFrame(control))

        val stream = RelayProtocol.encodeMessage(
            com.hashmi.familylink.data.StreamMessage.Handshake("dev", "AB12-CD34", "Pixel")
        )
        assertFalse(RelayProtocol.isControlFrame(stream))
    }

    @Test
    fun controlRoundTrip() {
        val original = RelayControl(
            role = RelayProtocol.ROLE_CLIENT,
            room = "room1",
            token = "token1",
            deviceName = "Kid Phone"
        )
        val decoded = RelayProtocol.decodeControl(RelayProtocol.encodeControl(original))
        assertNotNull(decoded)
        assertEquals(RelayProtocol.ROLE_CLIENT, decoded!!.role)
        assertEquals("room1", decoded.room)
        assertEquals("token1", decoded.token)
        assertEquals("Kid Phone", decoded.deviceName)
    }

    @Test
    fun streamMessageRoundTripThroughRelayCodec() {
        val message = com.hashmi.familylink.data.StreamMessage.StartScreenCapture
        assertEquals(message, RelayProtocol.decodeMessage(RelayProtocol.encodeMessage(message)))
    }

    @Test
    fun relayUrlNormalization() {
        assertEquals("wss://relay.example.com", normalizeRelayUrl("wss://relay.example.com"))
        assertEquals("wss://relay.example.com", normalizeRelayUrl("https://relay.example.com"))
        assertEquals("wss://relay.example.com", normalizeRelayUrl("relay.example.com"))
        assertEquals("ws://relay.example.com", normalizeRelayUrl("http://relay.example.com"))
        assertEquals("ws://relay.example.com", normalizeRelayUrl("relay.example.com/"))
        assertEquals("ws://1.2.3.4:9000", normalizeRelayUrl("1.2.3.4:9000"))
    }

    @Test
    fun garbageControlReturnsNull() {
        assertNull(RelayProtocol.decodeControl("not json"))
        assertNull(RelayProtocol.decodeEvent("{}"))
    }
}
