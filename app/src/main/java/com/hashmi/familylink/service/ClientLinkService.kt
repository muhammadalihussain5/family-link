package com.hashmi.familylink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.DiscoveryBeacon
import com.hashmi.familylink.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Always-on client service. Discovers the hub, keeps the WebSocket alive,
 * and applies remote tap events even when the UI is not visible.
 */
class ClientLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: UserPreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = UserPreferencesRepository(this)
        startInForeground("Looking for Family Hub…")
        observeConnection()
        listenForCommands()
        beginDiscovery()
        reconnectToLastServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(
            if (NetworkManager.client.isConnected.value) {
                "Connected to Family Hub"
            } else {
                "Looking for Family Hub…"
            }
        )
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        if (!host.isNullOrBlank() && port > 0) {
            scope.launch { connectTo(host, port) }
        }
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val notification = LinkNotifications.connectionNotification(
            this,
            "Family Link Client",
            text
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                LinkNotifications.ID_CLIENT,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(LinkNotifications.ID_CLIENT, notification)
        }
    }

    private fun observeConnection() {
        scope.launch {
            NetworkManager.client.isConnected.collectLatest { connected ->
                startInForeground(
                    if (connected) "Connected to Family Hub" else "Looking for Family Hub…"
                )
            }
        }
    }

    private fun listenForCommands() {
        scope.launch {
            NetworkManager.client.messages.collect { message ->
                when (message) {
                    is StreamMessage.TapEvent -> {
                        ClientAccessibilityService.instance?.injectTap(message.x, message.y)
                    }
                    is StreamMessage.SwipeEvent -> {
                        ClientAccessibilityService.instance?.injectSwipe(
                            startX = message.startX,
                            startY = message.startY,
                            endX = message.endX,
                            endY = message.endY,
                            durationMs = message.durationMs
                        )
                    }
                    is StreamMessage.HandshakeAck -> {
                        if (message.accepted && message.serverName.isNotBlank()) {
                            Log.d(TAG, "Paired with ${message.serverName}")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun beginDiscovery() {
        NetworkManager.discovery.startListening { beacon ->
            scope.launch { connectTo(beacon.host, beacon.port, beacon.serverName) }
        }
    }

    private fun reconnectToLastServer() {
        scope.launch {
            prefs.lastServer()?.let { (host, port) ->
                connectTo(host, port)
            }
        }
    }

    private suspend fun connectTo(host: String, port: Int, serverName: String = "") {
        val identity = prefs.ensureIdentity()
        if (serverName.isNotBlank()) {
            prefs.setLastServer(host, port, serverName)
        } else {
            prefs.setLastServer(host, port, "Family Hub")
        }
        NetworkManager.client.connect(
            host = host,
            port = port,
            handshake = StreamMessage.Handshake(
                deviceId = identity.deviceId,
                pairingKey = identity.pairingKey,
                deviceName = identity.deviceName
            )
        )
    }

    override fun onDestroy() {
        NetworkManager.discovery.stopListening()
        NetworkManager.client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ClientLinkService"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"

        fun start(context: Context, beacon: DiscoveryBeacon? = null) {
            val intent = Intent(context, ClientLinkService::class.java)
            if (beacon != null) {
                intent.putExtra(EXTRA_HOST, beacon.host)
                intent.putExtra(EXTRA_PORT, beacon.port)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context, host: String, port: Int) {
            val intent = Intent(context, ClientLinkService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
