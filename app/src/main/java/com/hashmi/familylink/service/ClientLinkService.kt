package com.hashmi.familylink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hashmi.familylink.data.PairedHub
import com.hashmi.familylink.data.QrPayload
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.DiscoveryBeacon
import com.hashmi.familylink.network.NetworkManager
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
 * Always-on client service.
 *
 * Pairing rules:
 *  - Nothing connects automatically until this device has paired with a hub
 *    (the hub scanned this device's QR / entered its key, and this device
 *    connected to the hub once by scanning its invite QR or entering its
 *    address, and the hub accepted the handshake).
 *  - Once paired, the pairing survives reboots on both sides. The client only
 *    ever auto-connects to the specific hub it paired with (matched by the
 *    hub's device id in the discovery beacon), over LAN or via the internet
 *    relay when one is configured.
 *  - The pairing is only removed from the disconnect section, which requires
 *    re-entering the hub's PIN or scanning the hub's QR code.
 */
class ClientLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: UserPreferencesRepository

    /** Last LAN address this service asked the client to dial. */
    @Volatile
    private var lastLanTarget: Pair<String, Int>? = null

    /**
     * Signature of the last transport configuration we applied, e.g.
     * "hubId|host|relayUrl". Prevents reconnect loops when DataStore emits
     * unchanged values (e.g. after the hub acks a handshake we already have).
     */
    @Volatile
    private var lastConfigKey: String? = null

    /** Hub id of the last applied configuration; null while unpaired. */
    @Volatile
    private var lastHubId: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = UserPreferencesRepository(this)
        startInForeground("Waiting to be paired with a hub…")
        observeConnection()
        listenForCommands()
        observeConfiguration()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra(EXTRA_HOST)
        val port = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        if (!host.isNullOrBlank() && port > 0) {
            // Manual, first-time connect (invite QR scan or "Connect by address").
            scope.launch { connectLan(host, port) }
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
                if (connected) {
                    startInForeground("Connected to Family Hub")
                } else {
                    val paired = prefs.pairedHubFlow.first()
                    val relay = prefs.relayUrlFlow.first()
                    startInForeground(
                        when {
                            paired == null -> "Waiting to be paired with a hub…"
                            relay.isNotBlank() -> "Connecting to ${paired.serverName} via relay…"
                            else -> "Looking for ${paired.serverName}…"
                        }
                    )
                }
            }
        }
    }

    /** Reacts to pairing changes and relay settings without restarting the service. */
    private fun observeConfiguration() {
        scope.launch {
            combine(
                prefs.pairedHubFlow,
                prefs.relayUrlFlow
            ) { hub, relayUrl -> hub to relayUrl }
                .collectLatest { (hub, relayUrl) -> reconfigure(hub, relayUrl) }
        }
    }

    private suspend fun reconfigure(hub: PairedHub?, relayUrl: String) {
        val configKey = "${hub?.hubDeviceId}|${hub?.host}|$relayUrl"
        if (hub != null && configKey == lastConfigKey) return // nothing changed
        val wasPairedBefore = lastHubId != null
        lastConfigKey = configKey
        lastHubId = hub?.hubDeviceId

        if (hub == null) {
            // Unpaired. If we WERE paired, tear the link down. If this is the
            // fresh never-paired state, leave any manual first-time connect
            // (invite QR / connect-by-address) alone to let pairing finish.
            if (wasPairedBefore) {
                NetworkManager.client.disconnect()
                NetworkManager.discovery.stopListening()
                lastLanTarget = null
                startInForeground("Waiting to be paired with a hub…")
            }
            return
        }

        NetworkManager.client.disconnect()
        NetworkManager.discovery.stopListening()
        lastLanTarget = null

        val identity = prefs.ensureIdentity()
        val handshake = StreamMessage.Handshake(
            deviceId = identity.deviceId,
            pairingKey = identity.pairingKey,
            deviceName = identity.deviceName
        )

        if (relayUrl.isNotBlank()) {
            Log.d(TAG, "Relay mode: $relayUrl")
            NetworkManager.client.connectRelay(
                relayUrl = relayUrl,
                room = RelayProtocol.roomFor(identity.pairingKey),
                token = RelayProtocol.tokenFor(identity.pairingKey),
                handshake = handshake
            )
        } else {
            // LAN mode: only follow beacons of the exact hub we paired with.
            NetworkManager.discovery.startListening { beacon ->
                if (beacon.deviceId != null && beacon.deviceId == hub.hubDeviceId) {
                    scope.launch { connectLan(beacon.host, beacon.port, handshake) }
                }
            }
            hub.host?.let { host ->
                if (host.isNotBlank()) connectLan(host, hub.port, handshake)
            }
        }
    }

    private suspend fun connectLan(
        host: String,
        port: Int,
        handshake: StreamMessage.Handshake? = null
    ) {
        val identity = prefs.ensureIdentity()
        val effectiveHandshake = handshake ?: StreamMessage.Handshake(
            deviceId = identity.deviceId,
            pairingKey = identity.pairingKey,
            deviceName = identity.deviceName
        )
        lastLanTarget = host to port
        Log.d(TAG, "Connecting to hub at $host:$port")
        NetworkManager.client.connect(host, port, effectiveHandshake)
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
                    is StreamMessage.StartScreenCapture -> handleStartScreenCapture()
                    is StreamMessage.StopScreenCapture -> ScreenCaptureService.pause(this@ClientLinkService)
                    is StreamMessage.HandshakeAck -> {
                        if (message.accepted) {
                            rememberPairedHub(message)
                        } else {
                            Log.w(TAG, "Hub rejected pairing: ${message.reason}")
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Hub-initiated screen share. If the projection is alive we resume it;
     * otherwise we try the saved consent grant (works until reboot); if that
     * is not possible we surface a one-tap approval notification.
     */
    private fun handleStartScreenCapture() {
        if (ScreenCaptureService.isProjectionHeld) {
            if (!ScreenCaptureService.isStreaming) {
                ScreenCaptureService.resume(this)
            }
            return
        }
        scope.launch {
            val grant = prefs.projectionGrant()
            val data = grant?.let {
                runCatching {
                    Intent.parseUri(it.resultDataUri, Intent.URI_INTENT_SCHEME)
                }.getOrNull()
            }
            if (grant != null && data != null) {
                try {
                    ScreenCaptureService.startService(this@ClientLinkService, grant.resultCode, data)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Saved projection grant unusable: ${e.message}")
                    prefs.clearProjectionGrant()
                }
            }
            LinkNotifications.notifyCastRequested(this@ClientLinkService)
        }
    }

    /** Once a hub accepts us, remember it so we auto-reconnect from now on. */
    private suspend fun rememberPairedHub(ack: StreamMessage.HandshakeAck) {
        if (ack.serverDeviceId.isBlank()) return
        if (NetworkManager.client.isRelay.value) {
            val existing = prefs.pairedHubFlow.first()
            prefs.setPairedHub(
                hubDeviceId = ack.serverDeviceId,
                host = existing?.host,
                port = existing?.port ?: QrPayload.DEFAULT_PORT,
                serverName = ack.serverName.ifBlank { "Family Hub" },
                serverKey = ack.serverPairingKey
            )
        } else {
            val target = lastLanTarget
            prefs.setPairedHub(
                hubDeviceId = ack.serverDeviceId,
                host = target?.first,
                port = target?.second ?: QrPayload.DEFAULT_PORT,
                serverName = ack.serverName.ifBlank { "Family Hub" },
                serverKey = ack.serverPairingKey
            )
        }
        Log.d(TAG, "Paired with hub ${ack.serverDeviceId} (${ack.serverName})")
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
