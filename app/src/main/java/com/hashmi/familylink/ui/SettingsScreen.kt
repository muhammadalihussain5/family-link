package com.hashmi.familylink.ui

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashmi.familylink.data.AppMode
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.NetworkManager
import com.hashmi.familylink.network.RelayHubTunnel
import com.hashmi.familylink.service.ClientLinkService
import com.hashmi.familylink.service.ScreenCaptureService
import com.hashmi.familylink.service.ServerLinkService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { UserPreferencesRepository(context) }
    val scope = rememberCoroutineScope()
    val mode by prefs.appModeFlow.collectAsStateWithLifecycle(initialValue = AppMode.UNDEFINED)
    val deviceName by prefs.deviceNameFlow.collectAsStateWithLifecycle(initialValue = "")
    val pairingKey by prefs.pairingKeyFlow.collectAsStateWithLifecycle(initialValue = null)
    val authorized by prefs.authorizedClientFlow.collectAsStateWithLifecycle(initialValue = null)
    val pairedHub by prefs.pairedHubFlow.collectAsStateWithLifecycle(initialValue = null)
    val relayUrl by prefs.relayUrlFlow.collectAsStateWithLifecycle(initialValue = "")
    val relayState by NetworkManager.server.relay.state.collectAsStateWithLifecycle()

    var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
    var relayDraft by remember(relayUrl) { mutableStateOf(relayUrl) }
    var confirmReset by remember { mutableStateOf(false) }
    var showClearPairing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { prefs.ensureIdentity() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (mode == AppMode.SERVER) "This device is the Family Hub" else "This device is a linked client",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = { Text("Device name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { scope.launch { prefs.updateDeviceName(nameDraft) } },
                enabled = nameDraft.isNotBlank() && nameDraft != deviceName,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save name")
            }

            pairingKey?.let { key ->
                Text("Your pairing key", style = MaterialTheme.typography.labelLarge)
                PairingKeyBadge(pairingKey = key)
                if (mode == AppMode.SERVER) {
                    Text(
                        "Clients must re-enter this key (or scan this hub's QR) to disconnect from the hub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (mode == AppMode.SERVER) {
                authorized?.let { client ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Paired device", style = MaterialTheme.typography.titleMedium)
                            Text(
                                client.deviceName.ifBlank { client.deviceId },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { showClearPairing = true }) {
                                Text("Unpair device")
                            }
                        }
                    }
                }
            } else {
                pairedHub?.let { hub ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Paired hub", style = MaterialTheme.typography.titleMedium)
                            Text(
                                hub.serverName,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "To disconnect, use the Disconnect section on the dashboard " +
                                    "(hub PIN or hub QR required).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Internet relay — see relay-server/ and docs/INTERNET.md.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Internet relay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Run the relay server from the relay-server folder (any VPS) and enter " +
                            "its address here on both devices. They then stay linked over the " +
                            "internet, behind any network — no port forwarding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relayDraft,
                        onValueChange = { relayDraft = it.trim() },
                        label = { Text("Relay address (e.g. wss://relay.example.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                scope.launch {
                                    prefs.updateRelayUrl(relayDraft)
                                    Toast.makeText(
                                        context,
                                        if (relayDraft.isBlank()) "Relay cleared" else "Relay saved",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = relayDraft != relayUrl,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save relay")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { relayDraft = "" },
                            enabled = relayUrl.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Clear")
                        }
                    }
                    if (mode == AppMode.SERVER && relayUrl.isNotBlank()) {
                        val label = when (val state = relayState) {
                            is RelayHubTunnel.State.DeviceOnline -> "Relay: device online"
                            is RelayHubTunnel.State.WaitingForDevice -> "Relay: waiting for the paired device"
                            is RelayHubTunnel.State.Connecting -> "Relay: connecting…"
                            is RelayHubTunnel.State.Error -> "Relay error: ${state.message}"
                            is RelayHubTunnel.State.Disabled -> "Relay: connect a paired device first"
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                    if (mode == AppMode.SERVER && authorized == null && relayUrl.isNotBlank()) {
                        Text(
                            "The relay activates once a device is paired (scan its QR).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Switch mode…")
            }
        }
    }

    // Hub-side unpair: requires the client's pairing key so it can't be
    // triggered by accident (or by someone holding the hub unlocked).
    if (showClearPairing) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showClearPairing = false },
            title = { Text("Unpair device?") },
            text = {
                Column {
                    Text("Enter the paired device's pairing key to confirm. It will have to pair again from scratch.")
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.uppercase() },
                        label = { Text("XXXX-XXXX") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val expected = authorized?.pairingKey
                        if (expected != null && draft.trim().equals(expected, ignoreCase = true)) {
                            scope.launch {
                                prefs.clearAuthorizedClient()
                                NetworkManager.server.updateAuthorizedKey(null)
                            }
                            showClearPairing = false
                        } else {
                            Toast.makeText(context, "Wrong pairing key.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Unpair") }
            },
            dismissButton = {
                TextButton(onClick = { showClearPairing = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Switch mode?") },
            text = {
                Text("This device will return to setup. Pairing is cleared and the background connection stops.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        ScreenCaptureService.stopService(context)
                        context.stopService(Intent(context, ClientLinkService::class.java))
                        context.stopService(Intent(context, ServerLinkService::class.java))
                        NetworkManager.client.disconnect()
                        NetworkManager.server.stop()
                        NetworkManager.discovery.stop()
                        prefs.resetMode()
                    }
                }) { Text("Switch mode") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }
}
