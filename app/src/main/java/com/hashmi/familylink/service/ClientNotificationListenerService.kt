package com.hashmi.familylink.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.network.NetworkManager
import java.util.UUID

class ClientNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        val notification = sbn.notification
        val title = notification.extras.getCharSequence("android.title")?.toString() ?: ""
        val text = notification.extras.getCharSequence("android.text")?.toString() ?: ""
        
        Log.d("NotificationListener", "Notification posted: ${sbn.packageName} - $title")
        
        NetworkManager.client.sendMessage(
            StreamMessage.Notification(
                id = UUID.randomUUID().toString(),
                packageName = sbn.packageName,
                title = title,
                text = text,
                timestamp = sbn.postTime
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        Log.d("NotificationListener", "Notification removed: ${sbn.packageName}")
    }
}
