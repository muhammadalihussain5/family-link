package com.hashmi.familylink.data

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionInfo(
    val deviceId: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String
)

data class DeviceIdentity(
    val deviceId: String,
    val pairingKey: String,
    val deviceName: String
)

data class AuthorizedClient(
    val deviceId: String,
    val pairingKey: String,
    val deviceName: String
)

/**
 * The hub a client has successfully paired with. Persisted so the client
 * keeps reconnecting to this hub (and only this hub) across reboots until
 * the pairing is explicitly removed in the disconnect section.
 */
data class PairedHub(
    val hubDeviceId: String,
    val host: String?,
    val port: Int,
    val serverName: String,
    /** The hub's own pairing key — re-entering it (or scanning its QR) is required to disconnect. */
    val serverKey: String
)

data class LinkedSession(
    val deviceId: String,
    val deviceName: String,
    val connectedAt: Long = System.currentTimeMillis()
)

/** A saved MediaProjection consent grant (valid until reboot / revocation). */
data class ProjectionGrant(
    val resultCode: Int,
    val resultDataUri: String
)
