package com.hashmi.familylink.network

import android.util.Log
import com.hashmi.familylink.data.StreamMessage
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class SocketServer(val port: Int = 8080) {
    private var server: EmbeddedServer<*, *>? = null
    
    private val _messages = MutableSharedFlow<StreamMessage>()
    val messages = _messages.asSharedFlow()

    private val _connectedClients = MutableStateFlow(0)
    val connectedClients = _connectedClients.asStateFlow()

    private val sessions = ConcurrentHashMap.newKeySet<DefaultWebSocketServerSession>()

    fun start() {
        if (server != null) return

        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(Json)
            }
            install(ContentNegotiation) {
                json()
            }
            routing {
                webSocket("/link") {
                    Log.d("SocketServer", "Client connected")
                    sessions.add(this)
                    _connectedClients.value = sessions.size
                    try {
                        while (true) {
                            val message = receiveDeserialized<StreamMessage>()
                            Log.d("SocketServer", "Received: $message")
                            _messages.emit(message)
                        }
                    } catch (e: Exception) {
                        Log.e("SocketServer", "Error in websocket", e)
                    } finally {
                        sessions.remove(this)
                        _connectedClients.value = sessions.size
                        Log.d("SocketServer", "Client disconnected")
                    }
                }
            }
        }.start(wait = false)
        Log.d("SocketServer", "Server started on IP: ${getLocalIpAddress()} port: $port")
    }

    fun broadcast(message: StreamMessage) {
        sessions.forEach { session ->
            session.launch {
                try {
                    session.sendSerialized(message)
                } catch (e: Exception) {
                    Log.e("SocketServer", "Failed to broadcast message", e)
                }
            }
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SocketServer", "Error getting IP", e)
        }
        return null
    }
}
