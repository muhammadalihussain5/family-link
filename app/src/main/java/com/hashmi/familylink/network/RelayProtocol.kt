package com.hashmi.familylink.network

import com.hashmi.familylink.data.JsonConfig
import com.hashmi.familylink.data.StreamMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

/**
 * Control protocol spoken between the Android app and the online relay
 * server (see `relay-server/` in the repository root).
 *
 * Both devices dial OUT to the relay, so no port forwarding is needed and it
 * works behind any NAT. The room id and the access token are both derived
 * from the pairing key, so hub and client land in the same private room
 * without ever sending the raw pairing key to the relay.
 *
 * After both sides register, the relay pipes every further WebSocket frame
 * between the two devices verbatim; those frames are ordinary
 * [StreamMessage] JSON, identical to the LAN protocol.
 */
object RelayProtocol {
    /** All relay control frames are JSON that starts with this marker. */
    const val CONTROL_MARKER = "\"flink\":true"

    const val ROLE_HUB = "hub"
    const val ROLE_CLIENT = "client"

    const val EVENT_REGISTERED = "registered"
    const val EVENT_PAIRED = "paired"
    const val EVENT_PEER_LEFT = "peer-left"
    const val EVENT_ERROR = "error"

    fun isControlFrame(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        // Control frames are short and always carry the marker; StreamMessage
        // frames use "type" as the class discriminator and never contain it.
        return trimmed.length <= 512 && trimmed.contains(CONTROL_MARKER)
    }

    /** Room id derived from the client's pairing key (known to both sides after pairing). */
    fun roomFor(pairingKey: String): String =
        sha256Hex("family-link-room-v1:${pairingKey.trim().uppercase()}").take(24)

    /** Access token derived from the same key, but with a different salt. */
    fun tokenFor(pairingKey: String): String =
        sha256Hex("family-link-token-v1:${pairingKey.trim().uppercase()}")

    fun encodeControl(control: RelayControl): String =
        JsonConfig.json.encodeToString(control)

    fun decodeControl(text: String): RelayControl? = try {
        JsonConfig.json.decodeFromString(RelayControl.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun decodeEvent(text: String): RelayEvent? = try {
        JsonConfig.json.decodeFromString(RelayEvent.serializer(), text)
    } catch (_: Exception) {
        null
    }

    fun encodeMessage(message: StreamMessage): String =
        JsonConfig.json.encodeToString(StreamMessage.serializer(), message)

    fun decodeMessage(text: String): StreamMessage? = try {
        JsonConfig.json.decodeFromString(StreamMessage.serializer(), text)
    } catch (_: Exception) {
        null
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** App → relay: the first frame on a fresh connection. */
@Serializable
data class RelayControl(
    val flink: Boolean = true,
    val cmd: String = "register",
    val role: String = "",
    val room: String = "",
    val token: String = "",
    val deviceName: String = "",
    val secret: String = ""
)

/** Relay → app: status events. */
@Serializable
data class RelayEvent(
    val flink: Boolean = true,
    val event: String = "",
    val role: String = "",
    val deviceName: String = "",
    val message: String = ""
)
