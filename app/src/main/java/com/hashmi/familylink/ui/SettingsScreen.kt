package com.hashmi.familylink.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    var nameDraft by remember(deviceName) { mutableStateOf(deviceName) }
    var confirmReset by remember { mutableStateOf(false) }

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
            }

            authorized?.let { client ->
                Text(
                    "Authorized device: ${client.deviceName.ifBlank { client.deviceId }}",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            prefs.clearAuthorizedClient()
                            NetworkManager.server.updateAuthorizedKey(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear pairing")
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
