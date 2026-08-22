package com.hashmi.familylink.network

data class DiscoveryBeacon(
    val host: String,
    val port: Int,
    val serverName: String,
    /** Hub's stable device id; null for beacons from older app versions. */
    val deviceId: String? = null
)

object DiscoveryProtocol {
    const val PREFIX = "FLINK"
    const val PORT = 8888

    /**
     * Encodes a hub beacon: `FLINK|host|port|serverName|deviceId`.
     * The deviceId lets clients ignore hubs they are not paired with.
     */
    fun encode(host: String, port: Int, serverName: String, deviceId: String = ""): String =
        "$PREFIX|$host|$port|$serverName|$deviceId"

    fun decode(message: String): DiscoveryBeacon? {
        val parts = message.trim().split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val host = parts[1].trim()
        val port = parts[2].toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        val name = parts.getOrNull(3)?.trim().orEmpty().ifBlank { "Family Hub" }
        val deviceId = parts.getOrNull(4)?.trim().orEmpty().ifBlank { null }
        return DiscoveryBeacon(host, port, name, deviceId)
    }
}
