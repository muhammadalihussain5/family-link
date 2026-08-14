package com.hashmi.familylink.network

object NetworkManager {
    val client = SocketClient()
    val server = SocketServer()
    val discovery = UDPDiscovery()
}
