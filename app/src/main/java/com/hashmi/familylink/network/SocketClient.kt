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

class SocketClient {
    private enum class Mode { NONE, LAN, RELAY }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(JsonConfig.json)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private var connectJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private var mode = Mode.NONE
    private var currentHost: String? = null
    private var currentPort: Int = 8080
    private var currentRelayUrl: String? = null
    private var relayRoom: String = ""
    private var relayToken: String = ""
    private var handshake: StreamMessage.Handshake? = null

    @Volatile
    private var authRejected = false

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

    fun connect(host: String, port: Int, handshake: StreamMessage.Handshake) {
        scope.launch {
            connectMutex.withLock {
                if (connectJob?.isActive == true &&
                    mode == Mode.LAN &&
                    currentHost == host &&
                    currentPort == port
                ) {
                    this@SocketClient.handshake = handshake
                    return@launch
                }
                connectJob?.cancel()
                authRejected = false
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
        scope.launch {
            connectMutex.withLock {
                if (connectJob?.isActive == true &&
                    mode == Mode.RELAY &&
                    currentRelayUrl == url &&
                    relayRoom == room
                ) {
                    this@SocketClient.handshake = handshake
                    return@launch
                }
                connectJob?.cancel()
                authRejected = false
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
            if (authRejected) {
                Log.w(TAG, "Pairing key was rejected by the hub; not reconnecting.")
                break
            }
            try {
                Log.d(TAG, "Connecting to $host:$port")
                client.webSocket(host = host, port = port, path = "/link") {
                    session = this
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
            delay(5_000)
        }
    }

    private suspend fun relayConnectionLoop(url: String, room: String, token: String) {
        while (true) {
            if (authRejected) {
                Log.w(TAG, "Relay rejected this device; not reconnecting.")
                break
            }
            try {
                Log.d(TAG, "Connecting to relay $url (room ${room.take(8)}…)")
                client.webSocket(urlString = url) {
                    session = this
                    runSession {
                        send(Frame.Text(RelayProtocol.encodeControl(
                            RelayControl(
                                role = RelayProtocol.ROLE_CLIENT,
                                room = room,
                                token = token,
                                deviceName = handshake?.deviceName.orEmpty()
                            )
                        )))
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            if (RelayProtocol.isControlFrame(text)) {
                                val event = RelayProtocol.decodeEvent(text) ?: continue
                                when (event.event) {
                                    RelayProtocol.EVENT_REGISTERED,
                                    RelayProtocol.EVENT_PAIRED -> {
                                        Log.d(TAG, "Relay event: ${event.event}")
                                        // (Re)send the handshake whenever the peer
                                        // comes online so the hub can authorize us.
                                        handshake?.let { sendSerialized<StreamMessage>(it) }
                                    }
                                    RelayProtocol.EVENT_PEER_LEFT -> {
                                        Log.d(TAG, "Relay peer left")
                                    }
                                    RelayProtocol.EVENT_ERROR -> {
                                        Log.w(TAG, "Relay error: ${event.message}")
                                        _lastError.value = event.message.ifBlank { "Relay rejected the connection." }
                                        if (event.message.contains("token", ignoreCase = true) ||
                                            event.message.contains("room", ignoreCase = true)
                                        ) {
                                            authRejected = true
                                        }
                                    }
                                }
                            } else {
                                val message = RelayProtocol.decodeMessage(text) ?: continue
                                handleMessage(message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay connection failed: $url", e)
            } finally {
                session = null
                _isConnected.value = false
            }
            delay(5_000)
        }
    }

    private suspend fun DefaultClientWebSocketSession.runSession(block: suspend DefaultClientWebSocketSession.() -> Unit) {
        _isConnected.value = true
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
        if (message is StreamMessage.HandshakeAck && !message.accepted) {
            Log.w(TAG, "Handshake rejected: ${message.reason}")
            _lastError.value = message.reason.ifBlank { "The hub rejected this device." }
            authRejected = true
        }
        if (message is StreamMessage.HandshakeAck && message.accepted) {
            _lastError.value = null
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
        scope.launch {
            connectMutex.withLock {
                connectJob?.cancel()
                connectJob = null
                session = null
                mode = Mode.NONE
                currentHost = null
                currentRelayUrl = null
                _isConnected.value = false
                _activeTarget.value = null
                _isRelay.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SocketClient"
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
