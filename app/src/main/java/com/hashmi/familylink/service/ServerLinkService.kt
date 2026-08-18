package com.hashmi.familylink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the hub WebSocket server and UDP presence broadcast running
 * independently of the dashboard UI.
 */
class ServerLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: UserPreferencesRepository

    override fun onCreate() {
        super.onCreate()
        prefs = UserPreferencesRepository(this)
        startInForeground("Starting Family Hub…")
        scope.launch { startHub() }
        observeClients()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(statusText())
        return START_STICKY
    }

    private suspend fun startHub() {
        val identity = prefs.ensureIdentity()
        val authorized = prefs.authorizedClientFlow.first()
        NetworkManager.server.updateServerName(identity.deviceName)
        NetworkManager.server.updateAuthorizedKey(authorized?.pairingKey)
        NetworkManager.server.start()
        val ip = NetworkManager.server.getLocalIpAddress()
        if (ip != null) {
            NetworkManager.discovery.startBroadcasting(
                host = ip,
                port = NetworkManager.server.port,
                serverName = identity.deviceName
            )
        }
        startInForeground(statusText())
    }

    private fun observeClients() {
        scope.launch {
            NetworkManager.server.connectedClients.collectLatest {
                startInForeground(statusText())
            }
        }
    }

    private fun statusText(): String {
        val count = NetworkManager.server.connectedClients.value
        val ip = NetworkManager.server.getLocalIpAddress() ?: "waiting for Wi‑Fi"
        return if (count > 0) {
            val name = NetworkManager.server.linkedClient.value?.deviceName ?: "a device"
            "Linked to $name · $ip"
        } else {
            "Waiting for a device · $ip"
        }
    }

    private fun startInForeground(text: String) {
        val notification = LinkNotifications.connectionNotification(
            this,
            "Family Link Hub",
            text
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                LinkNotifications.ID_SERVER,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(LinkNotifications.ID_SERVER, notification)
        }
    }

    override fun onDestroy() {
        NetworkManager.discovery.stopBroadcasting()
        NetworkManager.server.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerLinkService::class.java)
            )
        }
    }
}
