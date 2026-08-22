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
import com.hashmi.familylink.network.NetworkManager
import com.hashmi.familylink.network.RelayHubTunnel
import com.hashmi.familylink.network.RelayProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the hub WebSocket server, UDP presence broadcast and (when
 * configured) the internet relay tunnel running independently of the
 * dashboard UI.
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
        observeRelayConfig()
        listenForUnpair()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(statusText())
        return START_STICKY
    }

    private suspend fun startHub() {
        val identity = prefs.ensureIdentity()
        val authorized = prefs.authorizedClientFlow.first()
        NetworkManager.server.updateServerName(identity.deviceName)
        NetworkManager.server.updateIdentity(identity.deviceId, identity.pairingKey)
        NetworkManager.server.updateAuthorizedKey(authorized?.pairingKey)
        NetworkManager.server.start()
        val ip = NetworkManager.server.getLocalIpAddress()
        if (ip != null) {
            NetworkManager.discovery.startBroadcasting(
                host = ip,
                port = NetworkManager.server.port,
                serverName = identity.deviceName,
                deviceId = identity.deviceId
            )
        }
        startInForeground(statusText())
    }

    /** Starts/stops the internet relay tunnel as pairing and settings change. */
    private fun observeRelayConfig() {
        scope.launch {
            combine(
                prefs.authorizedClientFlow,
                prefs.relayUrlFlow
            ) { authorized, relayUrl -> authorized to relayUrl }
                .collectLatest { (authorized, relayUrl) ->
                    NetworkManager.server.updateAuthorizedKey(authorized?.pairingKey)
                    if (authorized != null && relayUrl.isNotBlank()) {
                        val identity = prefs.ensureIdentity()
                        NetworkManager.server.relay.start(
                            url = relayUrl,
                            room = RelayProtocol.roomFor(authorized.pairingKey),
                            token = RelayProtocol.tokenFor(authorized.pairingKey),
                            deviceName = identity.deviceName
                        )
                    } else {
                        NetworkManager.server.relay.stop()
                    }
                }
        }
    }

    /** A client that unpairs from its disconnect section is forgotten here too. */
    private fun listenForUnpair() {
        scope.launch {
            NetworkManager.server.messages.collect { message ->
                if (message is StreamMessage.Unpair) {
                    val authorized = prefs.authorizedClientFlow.first()
                    if (authorized != null && message.deviceId == authorized.deviceId) {
                        Log.w(TAG, "Client ${message.deviceId} requested unpair")
                        prefs.clearAuthorizedClient()
                    }
                }
            }
        }
    }

    private fun observeClients() {
        scope.launch {
            combine(
                NetworkManager.server.connectedClients,
                NetworkManager.server.relay.state
            ) { _, _ -> Unit }.collectLatest {
                startInForeground(statusText())
            }
        }
    }

    private fun statusText(): String {
        val count = NetworkManager.server.connectedClients.value
        val ip = NetworkManager.server.getLocalIpAddress() ?: "waiting for Wi‑Fi"
        val relaySuffix = when (val state = NetworkManager.server.relay.state.value) {
            is RelayHubTunnel.State.DeviceOnline -> " · relay: device online"
            is RelayHubTunnel.State.WaitingForDevice -> " · relay: waiting for device"
            is RelayHubTunnel.State.Connecting -> " · relay: connecting"
            is RelayHubTunnel.State.Error -> " · relay error"
            is RelayHubTunnel.State.Disabled -> ""
        }
        return if (count > 0) {
            val name = NetworkManager.server.linkedClient.value?.deviceName ?: "a device"
            "Linked to $name · $ip$relaySuffix"
        } else {
            "Waiting for a device · $ip$relaySuffix"
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
        private const val TAG = "ServerLinkService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ServerLinkService::class.java)
            )
        }
    }
}
