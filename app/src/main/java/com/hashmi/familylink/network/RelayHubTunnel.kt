package com.hashmi.familylink.network

import android.util.Log
import com.hashmi.familylink.data.StreamMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Hub side of the internet relay. Dials out to the relay server, registers
 * in the room derived from the authorized client's pairing key, and then
 * bridges the relay connection into the [SocketServer] as a virtual client
 * handle — so handshakes, screen frames, taps and notifications flow exactly
 * as they do over the LAN socket.
 */
class RelayHubTunnel(private val server: SocketServer) {

    sealed interface State {
        data object Disabled : State
        data object Connecting : State
        data object WaitingForDevice : State
        data object DeviceOnline : State
        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(CIO) { install(WebSockets) }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state = _state.asStateFlow()

    private var tunnelJob: Job? = null
    private var activeUrl: String? = null
    private var activeRoom: String? = null

    @Volatile
    private var badConfig = false

    @Synchronized
    fun start(url: String, room: String, token: String, deviceName: String) {
        val normalized = normalizeRelayUrl(url)
        if (tunnelJob?.isActive == true &&
            activeUrl == normalized &&
            activeRoom == room
        ) {
            return
        }
        stopInternal()
        activeUrl = normalized
        activeRoom = room
        badConfig = false
        _state.value = State.Connecting
        tunnelJob = scope.launch { tunnelLoop(normalized, room, token, deviceName) }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = State.Disabled
    }

    private fun stopInternal() {
        tunnelJob?.cancel()
        tunnelJob = null
        activeUrl = null
        activeRoom = null
    }

    private suspend fun tunnelLoop(url: String, room: String, token: String, deviceName: String) {
        val myJob = coroutineContext[Job]
        while (scope.isActive) {
            if (badConfig) break
            var handle: RelayHandle? = null
            try {
                Log.d(TAG, "Connecting hub tunnel to relay $url (room ${room.take(8)}…)")
                client.webSocket(urlString = url) {
                    send(Frame.Text(RelayProtocol.encodeControl(
                        RelayControl(
                            role = RelayProtocol.ROLE_HUB,
                            room = room,
                            token = token,
                            deviceName = deviceName
                        )
                    )))
                    if (_state.value !is State.DeviceOnline) _state.value = State.WaitingForDevice

                    for (frame in incoming) {
                        if (!isActive) break
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        if (RelayProtocol.isControlFrame(text)) {
                            val event = RelayProtocol.decodeEvent(text) ?: continue
                            when (event.event) {
                                RelayProtocol.EVENT_PAIRED -> {
                                    Log.d(TAG, "Relay: device online (${event.deviceName})")
                                    val relayHandle = RelayHandle(this)
                                    handle = relayHandle
                                    server.registerHandle(relayHandle)
                                    _state.value = State.DeviceOnline
                                }
                                RelayProtocol.EVENT_PEER_LEFT -> {
                                    Log.d(TAG, "Relay: device left")
                                    handle?.let { server.unregisterHandle(it) }
                                    handle = null
                                    _state.value = State.WaitingForDevice
                                }
                                RelayProtocol.EVENT_REGISTERED -> Unit
                                RelayProtocol.EVENT_ERROR -> {
                                    Log.w(TAG, "Relay error: ${event.message}")
                                    _state.value = State.Error(event.message)
                                    if (event.message.contains("token", ignoreCase = true) ||
                                        event.message.contains("room", ignoreCase = true) ||
                                        event.message.contains("secret", ignoreCase = true)
                                    ) {
                                        badConfig = true
                                    }
                                }
                            }
                        } else {
                            val message = RelayProtocol.decodeMessage(text)
                            val current = handle
                            if (message != null && current != null) {
                                server.dispatch(current, message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Relay tunnel failed", e)
            } finally {
                handle?.let { server.unregisterHandle(it) }
                // Don't flip to "Connecting" if stop() already disabled us.
                if (!badConfig && myJob?.isActive == true) _state.value = State.Connecting
            }
            if (badConfig) break
            delay(5_000)
        }
    }

    private inner class RelayHandle(
        private val session: DefaultClientWebSocketSession
    ) : SocketServer.HubHandle() {
        override val id = "relay"

        override fun post(message: StreamMessage) {
            scope.launch {
                try {
                    session.send(Frame.Text(RelayProtocol.encodeMessage(message)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send ${message::class.simpleName} over relay", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "RelayHubTunnel"
    }
}