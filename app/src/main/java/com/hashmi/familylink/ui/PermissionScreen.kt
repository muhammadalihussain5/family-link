package com.hashmi.familylink.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onAllPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permissionsState by remember { mutableStateOf(getPermissionsState(context)) }
    val criticalGranted = permissionsState.filter { it.required }.all { it.isGranted }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        permissionsState = getPermissionsState(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsState = getPermissionsState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            LargeTopAppBar(
                title = { Text("Required permissions") },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = onAllPermissionsGranted,
                    enabled = criticalGranted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (criticalGranted) "Continue" else "Grant required permissions")
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Client mode needs these so the hub can receive notifications, stay connected, and tap on this device when you ask it to.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(permissionsState, key = { it.id }) { item ->
                PermissionItem(
                    item = item,
                    onGrantClick = {
                        when {
                            item.id == "post_notifications" &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                runtimePermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            item.id == "record_audio" ->
                                runtimePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else -> handlePermissionGrant(context, item)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun PermissionItem(
    item: PermissionStatus,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isGranted) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (item.isGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (item.required) {
                        Text(
                            text = "  required",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (item.isGranted) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(onClick = onGrantClick) {
                    Text("Grant")
                }
            }
        }
    }
}

data class PermissionStatus(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val isGranted: Boolean,
    val required: Boolean = true
)

fun getPermissionsState(context: Context): List<PermissionStatus> {
    val state = mutableListOf<PermissionStatus>()

    state.add(
        PermissionStatus(
            id = "accessibility",
            title = "Accessibility",
            description = "Lets the hub tap this screen when you request remote help.",
            icon = Icons.Default.Accessibility,
            isGranted = isAccessibilityServiceEnabled(context)
        )
    )

    state.add(
        PermissionStatus(
            id = "notification_access",
            title = "Notification access",
            description = "Relays incoming messages and alerts to the Family Hub.",
            icon = Icons.Default.NotificationsActive,
            isGranted = isNotificationServiceEnabled(context)
        )
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        state.add(
            PermissionStatus(
                id = "post_notifications",
                title = "Show status",
                description = "Used for the always-on connection notification.",
                icon = Icons.Default.Notifications,
                isGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED,
                required = false
            )
        )
    }

    state.add(
        PermissionStatus(
            id = "record_audio",
            title = "Microphone (audio sharing)",
            description = "Lets the hub listen to this device's audio while the screen is shared (Android 10+).",
            icon = Icons.Default.Mic,
            isGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED,
            required = false
        )
    )

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    state.add(
        PermissionStatus(
            id = "battery",
            title = "Ignore battery optimizations",
            description = "Keeps the client connected after the screen turns off.",
            icon = Icons.Default.BatteryChargingFull,
            isGranted = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            required = false
        )
    )

    return state
}

fun areAllPermissionsGranted(context: Context): Boolean {
    return getPermissionsState(context).filter { it.required }.all { it.isGranted }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(context.packageName) == true
}

fun handlePermissionGrant(context: Context, item: PermissionStatus) {
    val intent = when (item.id) {
        "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "post_notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        else -> return
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
