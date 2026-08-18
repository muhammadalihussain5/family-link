package com.hashmi.familylink.network

data class DiscoveryBeacon(
    val host: String,
    val port: Int,
    val serverName: String
)

object DiscoveryProtocol {
    const val PREFIX = "FLINK"
    const val PORT = 8888

    fun encode(host: String, port: Int, serverName: String): String =
        "$PREFIX|$host|$port|$serverName"

    fun decode(message: String): DiscoveryBeacon? {
        val parts = message.trim().split('|')
        if (parts.size < 3 || parts[0] != PREFIX) return null
        val host = parts[1].trim()
        val port = parts[2].toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        val name = parts.getOrNull(3)?.trim().orEmpty().ifBlank { "Family Hub" }
        return DiscoveryBeacon(host, port, name)
    }
}
