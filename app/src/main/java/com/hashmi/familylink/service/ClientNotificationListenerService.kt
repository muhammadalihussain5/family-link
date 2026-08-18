package com.hashmi.familylink.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.network.NetworkManager
import java.util.UUID

class ClientNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        Log.d(TAG, "Relaying notification from ${sbn.packageName}")
        NetworkManager.client.sendMessage(
            StreamMessage.Notification(
                id = sbn.key ?: UUID.randomUUID().toString(),
                packageName = sbn.packageName,
                title = title.ifBlank { appLabel(sbn.packageName) },
                text = text,
                timestamp = sbn.postTime
            )
        )
    }

    private fun appLabel(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private companion object {
        const val TAG = "NotificationListener"
    }
}
