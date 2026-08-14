package com.hashmi.familylink.network

import android.util.Log
import com.hashmi.familylink.data.StreamMessage
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class SocketClient {
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(Json)
        }
        install(ContentNegotiation) {
            json()
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _messages = MutableSharedFlow<StreamMessage>()
    val messages = _messages.asSharedFlow()

    fun connect(host: String, port: Int) {
        scope.launch {
            while (true) {
                try {
                    Log.d("SocketClient", "Attempting to connect to $host:$port")
                    client.webSocket(host = host, port = port, path = "/link") {
                        session = this
                        _isConnected.value = true
                        Log.d("SocketClient", "Connected to server")
                        
                        // Handle incoming messages from server
                        launch {
                            try {
                                while (true) {
                                    val message = receiveDeserialized<StreamMessage>()
                                    _messages.emit(message)
                                }
                            } catch (e: Exception) {
                                Log.e("SocketClient", "Error receiving message", e)
                            }
                        }

                        // Keep connection alive
                        while (true) {
                            delay(5000)
                            try {
                                sendSerialized(StreamMessage.Heartbeat as StreamMessage)
                            } catch (e: Exception) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SocketClient", "Connection failed", e)
                    _isConnected.value = false
                } finally {
                    session = null
                    _isConnected.value = false
                }
                Log.d("SocketClient", "Retrying in 5 seconds...")
                delay(5000)
            }
        }
    }

    fun sendMessage(message: StreamMessage) {
        scope.launch {
            try {
                session?.sendSerialized(message)
            } catch (e: Exception) {
                Log.e("SocketClient", "Failed to send message", e)
            }
        }
    }

    fun disconnect() {
        _isConnected.value = false
        session = null
    }
}
