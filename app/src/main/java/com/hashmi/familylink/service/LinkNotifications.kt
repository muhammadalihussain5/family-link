package com.hashmi.familylink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hashmi.familylink.MainActivity
import com.hashmi.familylink.R

object LinkNotifications {
    const val CHANNEL_LINK = "family_link_connection"
    const val CHANNEL_CAPTURE = "family_link_capture"
    const val CHANNEL_REQUESTS = "family_link_requests"
    const val ID_CLIENT = 21
    const val ID_SERVER = 22
    const val ID_CAPTURE = 23
    const val ID_CAST_REQUEST = 24

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LINK,
                "Family Link connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Family Link connection alive"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                "Screen mirroring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while this device is sharing its screen"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REQUESTS,
                "Hub requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "The Family Hub asked to start screen sharing"
                setShowBadge(true)
            }
        )
    }

    fun connectionNotification(
        context: Context,
        title: String,
        text: String
    ): Notification {
        ensureChannels(context)
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_LINK)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_family_link)
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun captureNotification(context: Context): Notification {
        ensureChannels(context)
        val launch = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setContentTitle("Screen mirroring")
            .setContentText("Sharing this screen with the Family Hub")
            .setSmallIcon(R.drawable.ic_stat_family_link)
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * High-visibility notification shown when the hub requests screen sharing
     * but this device has no valid saved consent (fresh boot, Android 14+,
     * or the grant was revoked). Tapping opens the one-tap approval dialog.
     */
    fun notifyCastRequested(context: Context) {
        ensureChannels(context)
        val approve = PendingIntent.getActivity(
            context,
            1,
            Intent(context, ScreenCastApprovalActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REQUESTS)
            .setContentTitle("Screen sharing requested")
            .setContentText("The Family Hub asked to view this screen. Tap to approve.")
            .setSmallIcon(R.drawable.ic_stat_family_link)
            .setContentIntent(approve)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(ID_CAST_REQUEST, notification)
        }
    }

    fun cancelCastRequest(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(ID_CAST_REQUEST)
        }
    }
}
