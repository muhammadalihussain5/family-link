package com.hashmi.familylink.data

import kotlinx.serialization.Serializable

@Serializable
data class ConnectionInfo(
    val deviceId: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String
)
