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
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(JsonConfig.json)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private var connectJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null
    private var currentHost: String? = null
    private var currentPort: Int = 8080
    private var handshake: StreamMessage.Handshake? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _messages = MutableSharedFlow<StreamMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    fun connect(host: String, port: Int, handshake: StreamMessage.Handshake) {
        scope.launch {
            connectMutex.withLock {
                if (connectJob?.isActive == true &&
                    currentHost == host &&
                    currentPort == port
                ) {
                    this@SocketClient.handshake = handshake
                    return@launch
                }
                connectJob?.cancel()
                currentHost = host
                currentPort = port
                this@SocketClient.handshake = handshake
                connectJob = scope.launch { connectionLoop(host, port) }
            }
        }
    }

    private suspend fun connectionLoop(host: String, port: Int) {
        while (true) {
            try {
                Log.d(TAG, "Connecting to $host:$port")
                client.webSocket(host = host, port = port, path = "/link") {
                    session = this
                    handshake?.let { sendSerialized<StreamMessage>(it) }
                    _isConnected.value = true
                    Log.d(TAG, "Connected to $host:$port")

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
                        while (isActive) {
                            val message = receiveDeserialized<StreamMessage>()
                            if (message !is StreamMessage.Heartbeat) {
                                _messages.emit(message)
                            }
                            if (message is StreamMessage.HandshakeAck && !message.accepted) {
                                Log.w(TAG, "Handshake rejected: ${message.reason}")
                            }
                        }
                    } finally {
                        heartbeat.cancel()
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
                currentHost = null
                _isConnected.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SocketClient"
    }
}
