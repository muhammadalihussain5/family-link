package com.hashmi.familylink.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hashmi.familylink.service.ClientAccessibilityService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(
    onAllPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permissionsState by remember { mutableStateOf(getPermissionsState(context)) }
    var mediaProjectionGranted by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            mediaProjectionGranted = true
            // In a real app, we'd pass this result to a service
        }
    }

    // Refresh state when component is recomposed
    LaunchedEffect(Unit) {
        while(true) {
            permissionsState = getPermissionsState(context)
            if (permissionsState.all { it.isGranted } && mediaProjectionGranted) {
                onAllPermissionsGranted()
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("Required Permissions") }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "To function as a Client, Family Link needs the following permissions. These allow remote assistance and monitoring.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            items(permissionsState) { item ->
                PermissionItem(
                    item = item,
                    onGrantClick = {
                        handlePermissionGrant(context, item)
                    }
                )
            }

            // Media Projection is special
            item {
                PermissionItem(
                    item = PermissionStatus(
                        id = "media_projection",
                        title = "Screen Recording",
                        description = "Required for screen mirroring features.",
                        icon = Icons.AutoMirrored.Filled.ScreenShare,
                        isGranted = mediaProjectionGranted
                    ),
                    onGrantClick = {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
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
            containerColor = if (item.isGranted) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surface
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
                tint = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall
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
                Button(onClick = onGrantClick) {
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
    val isGranted: Boolean
)

fun getPermissionsState(context: Context): List<PermissionStatus> {
    val state = mutableListOf<PermissionStatus>()

    // Accessibility Service
    state.add(
        PermissionStatus(
            id = "accessibility",
            title = "Accessibility Service",
            description = "Required to intercept notifications and simulate input.",
            icon = Icons.Default.Accessibility,
            isGranted = isAccessibilityServiceEnabled(context)
        )
    )

    // Notification Access (Listener Service)
    state.add(
        PermissionStatus(
            id = "notification_access",
            title = "Notification Access",
            description = "Allows reading all notifications more reliably.",
            icon = Icons.Default.NotificationsActive,
            isGranted = isNotificationServiceEnabled(context)
        )
    )

    // Post Notifications (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        state.add(
            PermissionStatus(
                id = "post_notifications",
                title = "Post Notifications",
                description = "Required to show service status and alerts.",
                icon = Icons.Default.Notifications,
                isGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        )
    }

    // Ignore Battery Optimizations
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    state.add(
        PermissionStatus(
            id = "battery",
            title = "Ignore Battery Optimizations",
            description = "Ensures the service stays alive in the background.",
            icon = Icons.Default.BatteryChargingFull,
            isGranted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        )
    )

    // Media Projection (Screen Capture)
    // Note: This is usually requested at runtime when starting capture, 
    // but we can check if we have a saved token or just show it as a requirement.
    // For now, we'll mark it as "needed" but we can't easily check "isGranted" without starting it.
    // Let's assume it's granted for UI purposes if we have a way to track it, or just leave it for now.
    
    return state
}

fun areAllPermissionsGranted(context: Context): Boolean {
    return getPermissionsState(context).all { it.isGranted }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

fun isNotificationServiceEnabled(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(pkgName) == true
}

fun handlePermissionGrant(context: Context, item: PermissionStatus) {
    when (item.id) {
        "accessibility" -> {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        }
        "post_notifications" -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                context.startActivity(intent)
            }
        }
        "notification_access" -> {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            context.startActivity(intent)
        }
        "battery" -> {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
