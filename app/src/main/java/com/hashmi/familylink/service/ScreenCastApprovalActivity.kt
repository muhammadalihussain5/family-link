package com.hashmi.familylink.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hashmi.familylink.ui.theme.FamilyLinkTheme

/**
 * Borderless dialog shown when the hub requests screen sharing and this
 * device needs fresh consent (first time, after reboot, or on Android 14+
 * where consent cannot be reused). One tap on "Start now" opens the system
 * dialog and sharing starts; the grant is remembered for next time.
 */
class ScreenCastApprovalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkNotifications.cancelCastRequest(this)
        setContent {
            FamilyLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
                ) {
                    ApprovalContent(
                        onDone = { finish() }
                    )
                }
            }
        }
    }

    @Composable
    private fun ApprovalContent(onDone: () -> Unit) {
        val context = LocalContext.current
        val projectionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ScreenCaptureService.startService(context, result.resultCode, result.data!!)
                Toast.makeText(context, "Screen sharing enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Screen sharing was not approved", Toast.LENGTH_SHORT).show()
            }
            onDone()
        }
        val audioLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {
            // Continue with the projection dialog either way; audio is optional.
            launchProjection(context, projectionLauncher)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Screen sharing requested",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "The Family Hub asked to view this screen. Approving once lets the hub start " +
                    "and stop sharing by itself until this phone restarts.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    val audioGranted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (audioGranted) {
                        launchProjection(context, projectionLauncher)
                    } else {
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start sharing")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Not now")
            }
        }
    }

    private fun launchProjection(
        context: Context,
        launcher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    ) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(manager.createScreenCaptureIntent())
    }
}
