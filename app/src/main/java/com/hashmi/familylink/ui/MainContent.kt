package com.hashmi.familylink.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
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
import com.hashmi.familylink.data.ConnectionInfo
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.SocketClient
import com.hashmi.familylink.network.SocketServer
import com.hashmi.familylink.network.UDPDiscovery
import com.hashmi.familylink.network.NetworkManager
import com.hashmi.familylink.service.ClientAccessibilityService
import com.hashmi.familylink.service.ScreenCaptureService
import com.hashmi.familylink.ui.theme.FamilyLinkTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ServerMainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = NetworkManager.server
    val discovery = NetworkManager.discovery
    
    val messages = remember { mutableStateListOf<StreamMessage.Notification>() }
    var latestFrame by remember { mutableStateOf<StreamMessage.ScreenFrame?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf<String?>(null) }

    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>()
    val connectedClients by server.connectedClients.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        server.start()
        serverIp = server.getLocalIpAddress()
        serverIp?.let { ip ->
            discovery.startBroadcasting(ip)
        }
        
        server.messages.collect { message ->
            when (message) {
                is StreamMessage.Notification -> messages.add(0, message)
                is StreamMessage.ScreenFrame -> latestFrame = message
                else -> {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            server.stop()
            discovery.stop()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Family Link Server") },
                actions = {
                    IconButton(onClick = { isScanning = true }) {
                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "Scan Client QR")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (isScanning) {
            QRScanner(
                onResult = { result ->
                    scope.launch {
                        isScanning = false
                        android.widget.Toast.makeText(context, "Paired with device: $result", android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
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
                            Text(
                                text = "Server IP: ${serverIp ?: "Unknown"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            StatusIndicator(isConnected = connectedClients > 0)
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
                                Text("View Client Screen", style = MaterialTheme.typography.titleMedium)
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Text(
                            text = "Live Notifications:",
                            style = MaterialTheme.typography.titleLarge
                        )
                        
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages) { notification ->
                                NotificationItem(notification)
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
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No Clients Connected", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
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
                try {
                    BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size)
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Client Screen",
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(frame.width.toFloat() / frame.height.toFloat())
                        .onGloballyPositioned { coordinates ->
                            imageSize = coordinates.size
                        }
                        .pointerInput(frame) {
                            detectTapGestures { offset ->
                                if (imageSize.width > 0 && imageSize.height > 0) {
                                    val normalizedX = offset.x / imageSize.width
                                    val normalizedY = offset.y / imageSize.height
                                    onTap(normalizedX, normalizedY)
                                }
                            }
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("Error decoding frame", color = Color.White)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text("Waiting for Client Screen...", color = Color.White)
            }
        }
    }
}

@Composable
fun NotificationItem(notification: StreamMessage.Notification) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = notification.packageName, style = MaterialTheme.typography.labelSmall)
            Text(text = notification.title, style = MaterialTheme.typography.titleMedium)
            Text(text = notification.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientMainScreen(
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val userPrefs = remember { UserPreferencesRepository(context) }
    val client = NetworkManager.client
    val discovery = NetworkManager.discovery
    
    val deviceId by userPrefs.deviceIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val isConnected by client.isConnected.collectAsState(false)
    var isMirroring by remember { mutableStateOf(false) }
    
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
        if (!areAllPermissionsGranted(context)) {
            onNavigateToPermissions()
        }
        
        if (deviceId == null) {
            userPrefs.updateDeviceId(UUID.randomUUID().toString())
        }
        
        discovery.startListening { serverIp ->
            if (!client.isConnected.value) {
                client.connect(serverIp, 8080)
            }
        }
        
        client.messages.collect { message ->
            if (message is StreamMessage.TapEvent) {
                ClientAccessibilityService.instance?.injectTap(message.x, message.y)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Family Link Client") }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            StatusIndicator(isConnected)
            
            Spacer(modifier = Modifier.height(32.dp))
            
            deviceId?.let { id ->
                Text(text = "Scan this to pair", style = MaterialTheme.typography.titleLarge)
                QRCodeDisplay(
                    content = id,
                    modifier = Modifier.size(250.dp)
                )
                Text(text = "Device ID: ${id.take(8)}...", style = MaterialTheme.typography.bodySmall)
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (isMirroring) {
                        context.stopService(Intent(context, ScreenCaptureService::class.java))
                        isMirroring = false
                    } else {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    }
                }, enabled = isConnected) {
                    Icon(
                        if (isMirroring) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.ScreenShare, 
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isMirroring) "Stop Mirroring" else "Start Mirroring")
                }
                
                OutlinedButton(onClick = {
                    client.sendMessage(StreamMessage.Notification(
                        id = UUID.randomUUID().toString(),
                        packageName = "com.hashmi.familylink",
                        title = "Test Notification",
                        text = "This is a test notification from the client.",
                        timestamp = System.currentTimeMillis()
                    ))
                }, enabled = isConnected) {
                    Text("Test Alert")
                }
            }
        }
    }
}

@Composable
fun StatusIndicator(isConnected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        ) {}
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isConnected) "Connected to Hub" else "Disconnected",
            style = MaterialTheme.typography.titleMedium,
            color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ServerMainPreview() {
    FamilyLinkTheme {
        ServerMainScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun ClientMainPreview() {
    FamilyLinkTheme {
        ClientMainScreen(onNavigateToPermissions = {})
    }
}
