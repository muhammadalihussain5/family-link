package com.hashmi.familylink.network

import android.util.Log
import com.hashmi.familylink.data.JsonConfig
import com.hashmi.familylink.data.StreamMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class SocketClient {
    enum class Mode { NONE, LAN, RELAY }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(JsonConfig.json)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    /**
     * Tickets make connect/disconnect requests order-independent: both are
     * fire-and-forget coroutines on a thread pool, so a `disconnect()`
     * queued BEFORE a `connect()` could otherwise execute AFTER it and kill
     * the fresh link. Only the most recent request is ever applied.
     */
    private val requestTicket = AtomicLong(0L)

    private var connectJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    @Volatile
    private var mode = Mode.NONE

    private var currentHost: String? = null
    private var currentPort: Int = 8080
    private var currentRelayUrl: String? = null
    private var relayRoom: String = ""
    private var relayToken: String = ""
    private var handshake: StreamMessage.Handshake? = null

    /** True once the hub accepted our handshake on the current session. */
    @Volatile
    private var sessionAuthorized = false

    /**
     * True while the hub (or relay) refused us. We NEVER stop retrying — the
     * hub may scan our QR / enter our key at any moment — we just slow down so
     * an unauthorized device can't hammer the hub.
     */
    @Volatile
    private var sessionRejected = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _messages = MutableSharedFlow<StreamMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    /** "host:port" for LAN connections, or the relay URL in relay mode. */
    private val _activeTarget = MutableStateFlow<String?>(null)
    val activeTarget = _activeTarget.asStateFlow()

    /** True while the active connection is tunneled through the internet relay. */
    private val _isRelay = MutableStateFlow(false)
    val isRelay = _isRelay.asStateFlow()

    /** Last rejection / connection error worth showing to the user. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()

    /** Transport currently in use (LAN / RELAY / NONE). Advisory only. */
    fun currentMode(): Mode = mode

    fun connect(host: String, port: Int, handshake: StreamMessage.Handshake) {
        val ticket = requestTicket.incrementAndGet()
        scope.launch {
            connectMutex.withLock {
                if (ticket != requestTicket.get()) return@launch // superseded
                if (connectJob?.isActive == true &&
                    mode == Mode.LAN &&
                    currentHost == host &&
                    currentPort == port
                ) {
                    this@SocketClient.handshake = handshake
                    sessionRejected = false
                    return@launch
                }
                connectJob?.cancel()
                sessionAuthorized = false
                sessionRejected = false
                mode = Mode.LAN
                currentHost = host
                currentPort = port
                currentRelayUrl = null
                this@SocketClient.handshake = handshake
                _activeTarget.value = "$host:$port"
                _isRelay.value = false
                connectJob = scope.launch { lanConnectionLoop(host, port) }
            }
        }
    }

    /**
     * Connects to the internet relay server. Both devices dial out to the
     * relay, which pipes frames between them once both have joined the room
     * derived from the pairing key. Works behind any NAT.
     */
    fun connectRelay(
        relayUrl: String,
        room: String,
        token: String,
        handshake: StreamMessage.Handshake
    ) {
        val url = normalizeRelayUrl(relayUrl)
        val ticket = requestTicket.incrementAndGet()
        scope.launch {
            connectMutex.withLock {
                if (ticket != requestTicket.get()) return@launch // superseded
                if (connectJob?.isActive == true &&
                    mode == Mode.RELAY &&
                    currentRelayUrl == url &&
                    relayRoom == room
                ) {
                    this@SocketClient.handshake = handshake
                    sessionRejected = false
                    return@launch
                }
                connectJob?.cancel()
                sessionAuthorized = false
                sessionRejected = false
                mode = Mode.RELAY
                currentRelayUrl = url
                relayRoom = room
                relayToken = token
                currentHost = null
                this@SocketClient.handshake = handshake
                _activeTarget.value = url
                _isRelay.value = true
                connectJob = scope.launch { relayConnectionLoop(url, room, token) }
            }
        }
    }

    private suspend fun lanConnectionLoop(host: String, port: Int) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                client.webSocket(host = host, port = port, path = "/link") {
                    session = this
                    sessionAuthorized = false
                    runSession {
                        handshake?.let { sendSerialized<StreamMessage>(it) }
                        while (isActive) {
                            val message = receiveDeserialized<StreamMessage>()
                            handleMessage(message)
                            if (message is StreamMessage.HandshakeAck && !message.accepted) {
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed to $host:$port", e)
            } finally {
                session = null
                _isConnected.value = false
            }
            // Never give up permanently: the hub may pair this device at any
            // time (QR scan / key entry on the hub), so keep retrying — just
            // slower after a rejection so we don't hammer the hub.
            delay(if (sessionRejected) RETRY_AFTER_REJECTION_MS else RETRY_MS)
        }
    }

    private suspend fun relayConnectionLoop(url: String, room: String, token: String) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to relay $url (room ${room.take(8)}…)")
                client.webSocket(urlString = url) {
                    session = this
                    sessionAuthorized = false
                    runSession {
                        send(Frame.Text(RelayProtocol.encodeControl(
                            RelayControl(
                                role = RelayProtocol.ROLE_CLIENT,
                                room = room,
                                token = token,
                                deviceName = handshake?.deviceName.orEmpty()
                            )
                        )))
                        // Keep (re)sending the handshake until the hub accepts
                        // it. The hub only joins the room once it has paired
                        // this device — which can happen at any time — and the
                        // hub may also rejoin later (e.g. after it disconnected
                        // us and paired us again).
                        val handshaker = launch {
                            while (isActive) {
                                if (!sessionAuthorized) {
                                    handshake?.let { sendSerialized<StreamMessage>(it) }
                                }
                                delay(if (sessionRejected) RETRY_AFTER_REJECTION_MS else RETRY_MS)
                            }
                        }
                        try {
                            for (frame in incoming) {
                                if (!isActive) break
                                if (frame !is Frame.Text) continue
                                val text = frame.readText()
                                if (RelayProtocol.isControlFrame(text)) {
                                    val event = RelayProtocol.decodeEvent(text) ?: continue
                                    when (event.event) {
                                        RelayProtocol.EVENT_REGISTERED -> {
                                            Log.d(TAG, "Relay: registered, waiting for the hub")
                                        }
                                        RelayProtocol.EVENT_PAIRED -> {
                                            Log.d(TAG, "Relay: hub online")
                                            // Hub just joined — greet it right
                                            // away instead of waiting for the
                                            // periodic resend above.
                                            if (!sessionAuthorized) {
                                                handshake?.let { sendSerialized<StreamMessage>(it) }
                                            }
                                        }
                                        RelayProtocol.EVENT_PEER_LEFT -> {
                                            Log.d(TAG, "Relay: hub left")
                                            sessionAuthorized = false
                                            _isConnected.value = false
                                        }
                                        RelayProtocol.EVENT_ERROR -> {
                                            Log.w(TAG, "Relay error: ${event.message}")
                                            _lastError.value = event.message.ifBlank { "Relay rejected the connection." }
                                            if (event.message.contains("token", ignoreCase = true) ||
                                                event.message.contains("room", ignoreCase = true)
                                            ) {
                                                sessionRejected = true
                                            }
                                        }
                                    }
                                } else {
                                    val message = RelayProtocol.decodeMessage(text) ?: continue
                                    handleMessage(message)
                                }
                            }
                        } finally {
                            handshaker.cancel()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay connection failed: $url", e)
            } finally {
                session = null
                _isConnected.value = false
            }
            delay(if (sessionRejected) RETRY_AFTER_REJECTION_MS else RETRY_MS)
        }
    }

    private suspend fun DefaultClientWebSocketSession.runSession(block: suspend DefaultClientWebSocketSession.() -> Unit) {
        Log.d(TAG, "Session open")
        val heartbeat = launch {
            while (isActive) {
                delay(5_000)
                try {
                    sendSerialized<StreamMessage>(StreamMessage.Heartbeat)
                } catch (_: Exception) {
                    break
                }
            }
        }
        try {
            block()
        } finally {
            heartbeat.cancel()
        }
    }

    private suspend fun handleMessage(message: StreamMessage) {
        if (message is StreamMessage.Heartbeat) return
        if (message is StreamMessage.HandshakeAck) {
            if (message.accepted) {
                sessionAuthorized = true
                sessionRejected = false
                _lastError.value = null
                // "Connected" means the HUB accepted us — not merely that a
                // socket is open.
                _isConnected.value = true
            } else {
                sessionAuthorized = false
                sessionRejected = true
                _isConnected.value = false
                Log.w(TAG, "Handshake rejected: ${message.reason}")
                _lastError.value = message.reason.ifBlank { "The hub rejected this device." }
            }
        }
        _messages.emit(message)
    }

    fun sendMessage(message: StreamMessage) {
        val active = session ?: return
        scope.launch {
            try {
                active.sendSerialized<StreamMessage>(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ${message::class.simpleName}", e)
            }
        }
    }

    fun disconnect() {
        val ticket = requestTicket.incrementAndGet()
        scope.launch {
            connectMutex.withLock {
                if (ticket != requestTicket.get()) return@launch // superseded
                connectJob?.cancel()
                connectJob = null
                session = null
                mode = Mode.NONE
                currentHost = null
                currentRelayUrl = null
                sessionAuthorized = false
                sessionRejected = false
                _isConnected.value = false
                _activeTarget.value = null
                _isRelay.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SocketClient"
        const val RETRY_MS = 5_000L
        const val RETRY_AFTER_REJECTION_MS = 15_000L
    }
}

/** Accepts bare hosts, host:port, ws://, wss://, http(s):// and normalizes to a ws(s) URL. */
fun normalizeRelayUrl(raw: String): String {
    val value = raw.trim().removeSuffix("/")
    return when {
        value.startsWith("wss://", ignoreCase = true) || value.startsWith("ws://", ignoreCase = true) -> value
        value.startsWith("https://", ignoreCase = true) -> "wss://" + value.removePrefix("https://").removePrefix("HTTPS://")
        value.startsWith("http://", ignoreCase = true) -> "ws://" + value.removePrefix("http://").removePrefix("HTTP://")
        value.isBlank() -> value
        else -> "ws://$value"
    }
}
