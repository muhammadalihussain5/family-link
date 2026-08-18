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

data class LinkedSession(
    val deviceId: String,
    val deviceName: String,
    val connectedAt: Long = System.currentTimeMillis()
)
