package com.hashmi.familylink.ui

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashmi.familylink.data.QrPayload
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.data.decodeQrPayload
import com.hashmi.familylink.data.encodeQrPayload
import com.hashmi.familylink.network.NetworkManager
import com.hashmi.familylink.service.ClientLinkService
import com.hashmi.familylink.service.ScreenCaptureService
import com.hashmi.familylink.ui.theme.FamilyLinkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientMainScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferencesRepository(context) }
    val client = NetworkManager.client

    val deviceId by prefs.deviceIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val pairingKey by prefs.pairingKeyFlow.collectAsStateWithLifecycle(initialValue = null)
    val deviceName by prefs.deviceNameFlow.collectAsStateWithLifecycle(initialValue = "")
    val pairedHub by prefs.pairedHubFlow.collectAsStateWithLifecycle(initialValue = null)
    val isConnected by client.isConnected.collectAsStateWithLifecycle()
    val lastError by client.lastError.collectAsStateWithLifecycle()

    var isMirroring by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var isScanningDisconnect by remember { mutableStateOf(false) }
    var showAddressDialog by remember { mutableStateOf(false) }
    var showDisconnectDialog by remember { mutableStateOf(false) }

    // Reflect hub-driven capture state (started/stopped from the hub side).
    LaunchedEffect(Unit) {
        while (true) {
            isMirroring = ScreenCaptureService.isStreaming
            delay(1_000)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureService.startService(context, result.resultCode, result.data!!)
            isMirroring = true
        } else {
            isMirroring = false
        }
    }

    LaunchedEffect(Unit) {
        prefs.ensureIdentity()
        if (!areAllPermissionsGranted(context)) {
            onNavigateToPermissions()
        }
        ClientLinkService.start(context)
    }

    val qrContent = remember(deviceId, pairingKey, deviceName) {
        val id = deviceId
        val key = pairingKey
        if (id != null && key != null) {
            encodeQrPayload(QrPayload.client(id, key, deviceName))
        } else {
            null
        }
    }

    fun performDisconnect() {
        scope.launch {
            val identity = prefs.ensureIdentity()
            NetworkManager.client.sendMessage(StreamMessage.Unpair(deviceId = identity.deviceId))
            prefs.clearPairedHub()
            ScreenCaptureService.stopService(context)
            Toast.makeText(context, "Disconnected from the hub", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Linked device") },
                actions = {
                    IconButton(onClick = { isScanning = true }) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan hub QR")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (isScanning) {
            QRScanner(
                onResult = { raw ->
                    isScanning = false
                    val payload = decodeQrPayload(raw)
                    if (payload?.isServerInvite() == true) {
                        ClientLinkService.start(context, payload.host, payload.port)
                        Toast.makeText(
                            context,
                            "Connecting to ${payload.deviceName.ifBlank { payload.host }}",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(context, "That code is not a Family Hub invite.", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = { isScanning = false },
                modifier = Modifier.padding(innerPadding)
            )
        } else if (isScanningDisconnect) {
            val hub = pairedHub
            QRScanner(
                onResult = { raw ->
                    isScanningDisconnect = false
                    val payload = decodeQrPayload(raw)
                    if (hub != null && payload?.isServerInvite() == true &&
                        payload.deviceId.isNotBlank() && payload.deviceId == hub.hubDeviceId
                    ) {
                        performDisconnect()
                    } else {
                        Toast.makeText(
                            context,
                            "Scan the QR code shown on your paired hub.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onCancel = { isScanningDisconnect = false },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StatusIndicator(
                    isConnected = isConnected,
                    connectedLabel = pairedHub?.let { "Connected to ${it.serverName}" } ?: "Connected to hub",
                    disconnectedLabel = pairedHub?.let { "Looking for ${it.serverName}…" } ?: "Not paired yet"
                )

                lastError?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (pairedHub == null) "Show this to the hub" else "Paired with ${pairedHub!!.serverName}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            if (pairedHub == null) {
                                "The Family Hub scans this code (or types the key) to authorize this phone."
                            } else {
                                "The hub can start and stop screen sharing by itself. This pairing stays " +
                                    "active even after restarts, until you disconnect below."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        qrContent?.let { content ->
                            QRCodeDisplay(
                                content = content,
                                modifier = Modifier.fillMaxWidth(0.78f)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        pairingKey?.let { PairingKeyBadge(it) }
                        deviceName.takeIf { it.isNotBlank() }?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (isMirroring) {
                                ScreenCaptureService.stopService(context)
                                isMirroring = false
                            } else {
                                val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (isMirroring) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.ScreenShare,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isMirroring) "Stop sharing" else "Share screen")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                client.sendMessage(
                                    StreamMessage.Notification(
                                        id = UUID.randomUUID().toString(),
                                        packageName = context.packageName,
                                        title = "Test alert",
                                        text = "This is a test from $deviceName.",
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                            }
                        },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test alert")
                    }
                }
                Text(
                    "Approve screen sharing once — after that the hub can start and stop it " +
                        "remotely (until this phone restarts).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (pairedHub == null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showAddressDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect to a hub")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Disconnect section: requires the hub's PIN or its QR code,
                // so the pairing cannot be quietly removed from this phone.
                if (pairedHub != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.LinkOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Disconnect", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Unpairs this phone from the hub. You'll need the hub's PIN " +
                                    "(its pairing key, shown on the hub) or its QR code to confirm.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { showDisconnectDialog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Disconnect from hub")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Manage permissions")
                }
            }
        }
    }

    if (showAddressDialog) {
        var hostDraft by remember { mutableStateOf("") }
        var portDraft by remember { mutableStateOf("8080") }
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = { Text("Connect to a hub") },
            text = {
                Column {
                    Text(
                        "First time: pair on the hub first (it scans your QR or types your key), " +
                            "then enter the hub's address. On the same Wi-Fi use its local IP; " +
                            "over the internet use its public address or set up a relay in Settings."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hostDraft,
                        onValueChange = { hostDraft = it.trim() },
                        label = { Text("Host (IP or domain)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portDraft,
                        onValueChange = { portDraft = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val host = hostDraft.trim()
                    val port = portDraft.toIntOrNull()
                    if (host.isNotBlank() && port != null && port in 1..65535) {
                        ClientLinkService.start(context, host, port)
                        showAddressDialog = false
                    } else {
                        Toast.makeText(context, "Enter a valid host and port.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Connect") }
            },
            dismissButton = {
                TextButton(onClick = { showAddressDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDisconnectDialog) {
        var pinDraft by remember { mutableStateOf("") }
        val hub = pairedHub
        AlertDialog(
            onDismissRequest = { showDisconnectDialog = false },
            title = { Text("Disconnect from hub?") },
            text = {
                Column {
                    Text(
                        "Enter the hub's PIN — its pairing key, shown in Family Link on the hub " +
                            "device — or scan the hub's QR code to confirm."
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinDraft,
                        onValueChange = { pinDraft = it.uppercase() },
                        label = { Text("Hub PIN (XXXX-XXXX)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val serverKey = hub?.serverKey.orEmpty()
                        if (serverKey.isBlank()) {
                            Toast.makeText(
                                context,
                                "Use the hub's QR code to disconnect.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (pinDraft.trim().equals(serverKey, ignoreCase = true)) {
                            showDisconnectDialog = false
                            performDisconnect()
                        } else {
                            Toast.makeText(context, "Wrong hub PIN.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDisconnectDialog = false
                        isScanningDisconnect = true
                    }) { Text("Scan hub QR") }
                    TextButton(onClick = { showDisconnectDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ClientMainPreview() {
    FamilyLinkTheme {
        ClientMainScreen(onNavigateToPermissions = {})
    }
}
