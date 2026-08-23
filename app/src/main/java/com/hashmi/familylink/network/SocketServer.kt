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
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
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

    /** Tunnel to the optional internet relay server (see [RelayHubTunnel]). */
    val relay = RelayHubTunnel(this)

    private val handles = ConcurrentHashMap<String, HubHandle>()
    private val authorizedKey = AtomicReference<String?>(null)
    private val serverName = AtomicReference("Family Hub")
    private val hubDeviceId = AtomicReference("")
    private val hubPairingKey = AtomicReference("")

    /**
     * One connected client, either a LAN WebSocket session or a virtual
     * session piped through the internet relay.
     */
    abstract class HubHandle {
        abstract val id: String
        abstract fun post(message: StreamMessage)
        open fun shutdown() {}

        /**
         * True only after a handshake carrying the authorized pairing key was
         * accepted. Until then the handle may not feed anything into the hub.
         */
        @Volatile
        var authorized = false
    }

    fun updateAuthorizedKey(pairingKey: String?) {
        val key = pairingKey?.takeIf { it.isNotBlank() }
        val changed = authorizedKey.get() != key
        authorizedKey.set(key)
        if (key == null) {
            _linkedClient.value = null
            handles.values.forEach { it.shutdown() }
            handles.clear()
            _connectedClients.value = 0
        } else if (changed) {
            // A new device was paired: everybody currently connected has to
            // re-authenticate with the new key.
            handles.values.forEach { it.authorized = false }
        }
    }

    fun updateServerName(name: String) {
        serverName.set(name.ifBlank { "Family Hub" })
    }

    /** The hub's own identity, echoed in handshake acks so clients know who they paired with. */
    fun updateIdentity(deviceId: String, pairingKey: String) {
        hubDeviceId.set(deviceId)
        hubPairingKey.set(pairingKey)
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
                    val session = this
                    val handle = object : HubHandle() {
                        override val id = "lan-${session.hashCode()}"
                        override fun post(message: StreamMessage) {
                            session.launch {
                                try {
                                    session.sendSerialized<StreamMessage>(message)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to send ${message::class.simpleName}", e)
                                }
                            }
                        }
                        override fun shutdown() {
                            session.launch {
                                // Extension in io.ktor.websocket; it swallows
                                // failures itself, no try/catch needed.
                                session.close(
                                    CloseReason(CloseReason.Codes.GOING_AWAY, "Unpaired")
                                )
                            }
                        }
                    }
                    registerHandle(handle)

                    try {
                        while (true) {
                            val message = receiveDeserialized<StreamMessage>()
                            dispatch(handle, message)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket closed: ${e.message}")
                    } finally {
                        unregisterHandle(handle)
                        Log.d(TAG, "Client disconnected")
                    }
                }
            }
        }.start(wait = false)
        Log.d(TAG, "Server started on ${getLocalIpAddress()}:$port")
    }

    internal fun registerHandle(handle: HubHandle) {
        handles[handle.id] = handle
        _connectedClients.value = handles.size
    }

    internal fun unregisterHandle(handle: HubHandle) {
        if (handles.remove(handle.id) != null) {
            _connectedClients.value = handles.size
            if (handles.isEmpty()) {
                _linkedClient.value = null
            }
        }
    }

    /**
     * Handles a message from any transport (LAN socket or relay tunnel).
     * A handshake is only accepted when the hub has explicitly paired the
     * device — the first arriving device is never adopted automatically —
     * and every other message is dropped unless that handshake succeeded,
     * so an unpaired device can never feed data into the hub.
     */
    internal suspend fun dispatch(handle: HubHandle, message: StreamMessage) {
        when (message) {
            is StreamMessage.Heartbeat -> Unit
            is StreamMessage.Handshake -> handleHandshake(handle, message)
            else -> if (handle.authorized) _messages.emit(message)
        }
    }

    private suspend fun handleHandshake(
        handle: HubHandle,
        handshake: StreamMessage.Handshake
    ) {
        val expected = authorizedKey.get()
        if (expected.isNullOrBlank() || !expected.equals(handshake.pairingKey, ignoreCase = true)) {
            handle.authorized = false
            handle.post(
                StreamMessage.HandshakeAck(
                    accepted = false,
                    reason = "This hub has not paired this device yet. " +
                        "Open Family Link on the hub and scan the device QR (or enter its key) first."
                )
            )
            Log.w(TAG, "Handshake rejected for ${handshake.deviceName}")
            return
        }

        handle.authorized = true
        _linkedClient.value = LinkedSession(
            deviceId = handshake.deviceId,
            deviceName = handshake.deviceName.ifBlank { "Family Device" }
        )
        handle.post(
            StreamMessage.HandshakeAck(
                accepted = true,
                serverName = serverName.get(),
                serverDeviceId = hubDeviceId.get(),
                serverPairingKey = hubPairingKey.get()
            )
        )
        _messages.emit(handshake)
        Log.d(TAG, "Handshake accepted for ${handshake.deviceName}")
    }

    fun broadcast(message: StreamMessage) {
        handles.values.forEach { handle -> handle.post(message) }
    }

    @Synchronized
    fun stop() {
        relay.stop()
        server?.stop(500, 1_000)
        server = null
        handles.values.forEach { it.shutdown() }
        handles.clear()
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
