package com.hashmi.familylink.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class UDPDiscovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null

    fun startBroadcasting(host: String, port: Int, serverName: String) {
        val payload = DiscoveryProtocol.encode(host, port, serverName)
        if (broadcastJob?.isActive == true) return

        broadcastJob = scope.launch {
            try {
                val socket = DatagramSocket().apply { broadcast = true }
                broadcastSocket = socket
                val bytes = payload.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(
                    bytes,
                    bytes.size,
                    InetAddress.getByName("255.255.255.255"),
                    DiscoveryProtocol.PORT
                )
                while (isActive) {
                    socket.send(packet)
                    delay(2_000)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast error", e)
            } finally {
                broadcastSocket?.close()
                broadcastSocket = null
            }
        }
    }

    fun startListening(onDiscovery: (DiscoveryBeacon) -> Unit) {
        if (listenJob?.isActive == true) return

        listenJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 5_000
                    bind(InetSocketAddress(DiscoveryProtocol.PORT))
                }
                listenSocket = socket
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val beacon = DiscoveryProtocol.decode(message)
                        if (beacon != null) {
                            onDiscovery(beacon)
                        }
                    } catch (_: SocketTimeoutException) {
                        // keep listening
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Listen error", e)
            } finally {
                listenSocket?.close()
                listenSocket = null
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
        broadcastSocket?.close()
        broadcastSocket = null
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        listenSocket?.close()
        listenSocket = null
    }

    fun stop() {
        stopBroadcasting()
        stopListening()
    }

    private companion object {
        const val TAG = "UDPDiscovery"
    }
}
