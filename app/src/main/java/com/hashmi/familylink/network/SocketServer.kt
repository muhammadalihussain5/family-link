package com.hashmi.familylink.network

import android.util.Log
import com.hashmi.familylink.data.JsonConfig
import com.hashmi.familylink.data.LinkedSession
import com.hashmi.familylink.data.StreamMessage
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SocketServer(val port: Int = 8080) {
    private var server: EmbeddedServer<*, *>? = null

    private val _messages = MutableSharedFlow<StreamMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients = _connectedClients.asStateFlow()

    private val _linkedClient = MutableStateFlow<LinkedSession?>(null)
    val linkedClient = _linkedClient.asStateFlow()

    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()
    private val authorizedKey = AtomicReference<String?>(null)
    private val serverName = AtomicReference("Family Hub")

    fun updateAuthorizedKey(pairingKey: String?) {
        authorizedKey.set(pairingKey?.takeIf { it.isNotBlank() })
    }

    fun updateServerName(name: String) {
        serverName.set(name.ifBlank { "Family Hub" })
    }

    @Synchronized
    fun start() {
        if (server != null) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(JsonConfig.json)
                pingPeriodMillis = 15_000L
                timeoutMillis = 30_000L
            }
            routing {
                webSocket("/link") {
                    Log.d(TAG, "Client socket opened")
                    sessions.add(this)
                    _connectedClients.value = sessions.size
                    try {
                        while (true) {
                            val message = receiveDeserialized<StreamMessage>()
                            when (message) {
                                is StreamMessage.Handshake -> handleHandshake(this, message)
                                is StreamMessage.Heartbeat -> Unit
                                is StreamMessage.ScreenFrame -> {
                                    _messages.emit(message)
                                }
                                else -> {
                                    Log.d(TAG, "Received ${message::class.simpleName}")
                                    _messages.emit(message)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket closed: ${e.message}")
                    } finally {
                        sessions.remove(this)
                        _connectedClients.value = sessions.size
                        if (sessions.isEmpty()) {
                            _linkedClient.value = null
                        }
                        Log.d(TAG, "Client disconnected")
                    }
                }
            }
        }.start(wait = false)
        Log.d(TAG, "Server started on ${getLocalIpAddress()}:$port")
    }

    private suspend fun handleHandshake(
        session: DefaultWebSocketServerSession,
        handshake: StreamMessage.Handshake
    ) {
        val expected = authorizedKey.get()
        val accepted = expected.isNullOrBlank() ||
            expected.equals(handshake.pairingKey, ignoreCase = true)

        if (accepted) {
            if (expected.isNullOrBlank()) {
                authorizedKey.set(handshake.pairingKey)
            }
            _linkedClient.value = LinkedSession(
                deviceId = handshake.deviceId,
                deviceName = handshake.deviceName.ifBlank { "Family Device" }
            )
            session.sendSerialized<StreamMessage>(
                StreamMessage.HandshakeAck(
                    accepted = true,
                    serverName = serverName.get()
                )
            )
            _messages.emit(handshake)
            Log.d(TAG, "Handshake accepted for ${handshake.deviceName}")
        } else {
            session.sendSerialized<StreamMessage>(
                StreamMessage.HandshakeAck(
                    accepted = false,
                    reason = "Pairing key does not match this hub."
                )
            )
            Log.w(TAG, "Handshake rejected for ${handshake.deviceName}")
        }
    }

    fun broadcast(message: StreamMessage) {
        sessions.forEach { session ->
            session.launch {
                try {
                    session.sendSerialized<StreamMessage>(message)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to broadcast ${message::class.simpleName}", e)
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        server?.stop(500, 1_000)
        server = null
        sessions.clear()
        _connectedClients.value = 0
        _linkedClient.value = null
    }

    /**
     * Returns the IPv4 address that a client should connect to.
     *
     * Prefers Wi‑Fi / Ethernet interfaces and skips cellular (rmnet/ccmni/radio),
     * VPN (tun/ppp) and other virtual interfaces, because a phone with mobile
     * data enabled otherwise often reports its cellular address first — which a
     * client on the same Wi‑Fi cannot reach.
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var fallback: String? = null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue

                val name = intf.name.lowercase()
                val preferred = name.contains("wlan") || name.contains("wifi") ||
                    name.contains("eth") || name.contains("lan") ||
                    name.contains("ap") || name.startsWith("en")
                val excluded = name.contains("rmnet") || name.contains("ccmni") ||
                    name.contains("radio") || name.contains("p2p") ||
                    name.contains("tun") || name.contains("ppp") ||
                    name.contains("dummy") || name.contains("sit") ||
                    name.contains("veth") || name.contains("br-") ||
                    name.contains("docker")
                if (excluded) continue

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    if (addr is Inet4Address) {
                        if (preferred) return addr.hostAddress
                        if (fallback == null) fallback = addr.hostAddress
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
            null
        }
    }

    private companion object {
        const val TAG = "SocketServer"
    }
}
