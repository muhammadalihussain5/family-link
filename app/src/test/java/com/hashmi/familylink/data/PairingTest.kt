package com.hashmi.familylink.data

import com.hashmi.familylink.network.DiscoveryProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {

    @Test
    fun generatedKeysMatchExpectedFormat() {
        repeat(20) {
            val key = PairingKeys.generate()
            assertTrue(key, PairingKeys.isValid(key))
        }
    }

    @Test
    fun clientQrRoundTrip() {
        val payload = QrPayload.client("device-1", "AB12-CD34", "Pixel 8")
        val raw = encodeQrPayload(payload)
        val decoded = decodeQrPayload(raw)
        assertNotNull(decoded)
        assertTrue(decoded!!.isClientPairing())
        assertFalse(decoded.isServerInvite())
        assertEquals("device-1", decoded.deviceId)
        assertEquals("AB12-CD34", decoded.pairingKey)
        assertEquals("Pixel 8", decoded.deviceName)
    }

    @Test
    fun serverInviteRoundTrip() {
        val payload = QrPayload.server("192.168.0.12", 8080, "Family Hub")
        val decoded = decodeQrPayload(encodeQrPayload(payload))
        assertNotNull(decoded)
        assertTrue(decoded!!.isServerInvite())
        assertEquals("192.168.0.12", decoded.host)
        assertEquals(8080, decoded.port)
    }

    @Test
    fun barePairingKeyIsAccepted() {
        val decoded = decodeQrPayload("ab12-cd34")
        assertNotNull(decoded)
        assertEquals("AB12-CD34", decoded!!.pairingKey)
        assertTrue(decoded.isClientPairing())
    }

    @Test
    fun garbageQrIsRejected() {
        assertNull(decodeQrPayload("hello world"))
        assertNull(decodeQrPayload(""))
    }

    @Test
    fun discoveryBeaconRoundTrip() {
        val encoded = DiscoveryProtocol.encode("10.0.0.4", 8080, "Kitchen Hub")
        val beacon = DiscoveryProtocol.decode(encoded)
        assertNotNull(beacon)
        assertEquals("10.0.0.4", beacon!!.host)
        assertEquals(8080, beacon.port)
        assertEquals("Kitchen Hub", beacon.serverName)
    }

    @Test
    fun discoveryIgnoresForeignPackets() {
        assertNull(DiscoveryProtocol.decode("HELLO|10.0.0.1|80"))
        assertNull(DiscoveryProtocol.decode("FLINK|bad-port|nope"))
        assertNull(DiscoveryProtocol.decode("FLINK|10.0.0.1|99999"))
    }
}
