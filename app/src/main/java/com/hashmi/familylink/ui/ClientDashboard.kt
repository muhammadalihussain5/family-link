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
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
    val isConnected by client.isConnected.collectAsStateWithLifecycle()
    var isMirroring by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }

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
                        Toast.makeText(context, "Connecting to ${payload.deviceName.ifBlank { payload.host }}", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "That code is not a Family Hub invite.", Toast.LENGTH_SHORT).show()
                    }
                },
                onCancel = { isScanning = false },
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
                    connectedLabel = "Connected to hub",
                    disconnectedLabel = "Waiting for hub"
                )

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
                        Text("Show this to the hub", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "The Family Hub scans this code (or types the key) to authorize this phone.",
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
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            if (isMirroring) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.ScreenShare,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isMirroring) "Stop" else "Share screen")
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
}

@Preview(showBackground = true)
@Composable
fun ClientMainPreview() {
    FamilyLinkTheme {
        ClientMainScreen(onNavigateToPermissions = {})
    }
}
