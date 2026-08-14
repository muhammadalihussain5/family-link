package com.hashmi.familylink.network

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class UDPDiscovery {
    private val port = 8888
    private val broadcastAddress = "255.255.255.255"
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun startBroadcasting(message: String) {
        scope.launch {
            try {
                socket = DatagramSocket()
                socket?.broadcast = true
                val buffer = message.toByteArray()
                val packet = DatagramPacket(
                    buffer, buffer.size,
                    InetAddress.getByName(broadcastAddress), port
                )
                
                while (isActive) {
                    socket?.send(packet)
                    delay(2000)
                }
            } catch (e: Exception) {
                Log.e("UDPDiscovery", "Broadcast error", e)
            } finally {
                socket?.close()
            }
        }
    }

    fun startListening(onDiscovery: (String) -> Unit) {
        scope.launch {
            try {
                val listenSocket = DatagramSocket(port)
                listenSocket.soTimeout = 5000
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    try {
                        listenSocket.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        onDiscovery(message)
                    } catch (e: SocketTimeoutException) {
                        // Just timeout, continue listening
                    }
                }
            } catch (e: Exception) {
                Log.e("UDPDiscovery", "Listen error", e)
            }
        }
    }

    fun stop() {
        scope.cancel()
        socket?.close()
    }
}
