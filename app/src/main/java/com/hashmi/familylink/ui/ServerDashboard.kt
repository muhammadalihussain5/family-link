package com.hashmi.familylink.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashmi.familylink.data.QrPayload
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.data.decodeQrPayload
import com.hashmi.familylink.data.encodeQrPayload
import com.hashmi.familylink.network.NetworkManager
import com.hashmi.familylink.service.ServerLinkService
import com.hashmi.familylink.ui.theme.FamilyLinkTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ServerMainScreen(
    onNavigateToSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { UserPreferencesRepository(context) }
    val server = NetworkManager.server

    val messages = remember { mutableStateListOf<StreamMessage.Notification>() }
    var latestFrame by remember { mutableStateOf<StreamMessage.ScreenFrame?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showManualKey by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf<String?>(null) }
    val deviceName by prefs.deviceNameFlow.collectAsStateWithLifecycle(initialValue = "")

    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val connectedClients by server.connectedClients.collectAsStateWithLifecycle()
    val linked by server.linkedClient.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ServerLinkService.start(context)
        serverIp = server.getLocalIpAddress()
        server.messages.collect { message ->
            when (message) {
                is StreamMessage.Notification -> {
                    messages.removeAll { it.id == message.id }
                    messages.add(0, message)
                    while (messages.size > 80) {
                        messages.removeAt(messages.lastIndex)
                    }
                }
                is StreamMessage.ScreenFrame -> latestFrame = message
                else -> Unit
            }
        }
    }

    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch { navigator.navigateBack() }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Family Hub") },
                actions = {
                    IconButton(onClick = { showInvite = true }) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = "Show hub QR")
                    }
                    IconButton(onClick = { isScanning = true }) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan client QR")
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
                    if (payload?.isClientPairing() == true) {
                        scope.launch {
                            prefs.setAuthorizedClient(
                                payload.deviceId,
                                payload.pairingKey,
                                payload.deviceName
                            )
                            server.updateAuthorizedKey(payload.pairingKey)
                            Toast.makeText(
                                context,
                                "Paired with ${payload.deviceName.ifBlank { "device" }}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "That code is not a client pairing key.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onCancel = { isScanning = false },
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            ListDetailPaneScaffold(
                directive = navigator.scaffoldDirective,
                value = navigator.scaffoldValue,
                listPane = {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Hub IP: ${serverIp ?: "waiting for Wi‑Fi"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                linked?.let {
                                    Text(
                                        text = it.deviceName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            StatusIndicator(
                                isConnected = connectedClients > 0,
                                connectedLabel = "Linked",
                                disconnectedLabel = "Waiting"
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Card(
                            onClick = {
                                scope.launch {
                                    navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text("View linked screen", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (latestFrame != null) "Live — tap to interact" else "Waiting for frames",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { showManualKey = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enter pairing key")
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Live notifications", style = MaterialTheme.typography.titleLarge)

                        if (messages.isEmpty()) {
                            EmptyState(
                                title = "Nothing yet",
                                body = "Alerts from the linked phone will appear here.",
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(messages, key = { it.id }) { notification ->
                                    NotificationItem(notification)
                                }
                            }
                        }
                    }
                },
                detailPane = {
                    if (connectedClients > 0) {
                        MirroredScreen(
                            frame = latestFrame,
                            onTap = { x, y ->
                                server.broadcast(StreamMessage.TapEvent(x, y))
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            EmptyState(
                                title = "No device linked",
                                body = "Scan the client QR code or wait for it to join this Wi‑Fi."
                            )
                        }
                    }
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    if (showInvite) {
        val invite = serverIp?.let { ip ->
            encodeQrPayload(QrPayload.server(ip, server.port, deviceName.ifBlank { "Family Hub" }))
        }
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Invite a device") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("If discovery fails, scan this from the client.")
                    Spacer(modifier = Modifier.height(12.dp))
                    if (invite != null) {
                        QRCodeDisplay(content = invite, modifier = Modifier.fillMaxWidth())
                        Text(
                            "$serverIp:${server.port}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("Connect this hub to Wi‑Fi first.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInvite = false }) { Text("Done") }
            }
        )
    }

    if (showManualKey) {
        var draft by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualKey = false },
            title = { Text("Enter pairing key") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.uppercase() },
                    label = { Text("XXXX-XXXX") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val payload = decodeQrPayload(draft)
                    if (payload?.pairingKey?.isNotBlank() == true) {
                        scope.launch {
                            prefs.setAuthorizedClient(
                                payload.deviceId.ifBlank { payload.pairingKey },
                                payload.pairingKey,
                                payload.deviceName
                            )
                            server.updateAuthorizedKey(payload.pairingKey)
                        }
                        showManualKey = false
                    }
                }) { Text("Pair") }
            },
            dismissButton = {
                TextButton(onClick = { showManualKey = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun MirroredScreen(
    frame: StreamMessage.ScreenFrame?,
    onTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            val bitmap = remember(frame) {
                runCatching {
                    BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                }.getOrNull()
            }
            if (bitmap != null && frame.height > 0) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Linked screen",
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
                        .onGloballyPositioned { imageSize = it.size }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                if (imageSize.width > 0 && imageSize.height > 0) {
                                    onTap(
                                        offset.x / imageSize.width,
                                        offset.y / imageSize.height
                                    )
                                }
                            }
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("Could not decode the latest frame", color = Color.White)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Waiting for the linked screen…", color = Color.White)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ServerMainPreview() {
    FamilyLinkTheme {
        ServerMainScreen()
    }
}
