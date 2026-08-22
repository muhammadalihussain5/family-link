package com.hashmi.familylink.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.network.NetworkManager
import java.util.UUID

class ClientAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        val notification = event.parcelableData as? Notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        NetworkManager.client.sendMessage(
            StreamMessage.Notification(
                id = UUID.randomUUID().toString(),
                packageName = event.packageName?.toString() ?: packageName,
                title = title.ifBlank { "Notification" },
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Injects a tap at normalized coordinates in the range 0..1.
     */
    fun injectTap(normalizedX: Float, normalizedY: Float) {
        val metrics = resources.displayMetrics
        val x = (normalizedX.coerceIn(0f, 1f) * metrics.widthPixels)
        val y = (normalizedY.coerceIn(0f, 1f) * metrics.heightPixels)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)

        if (!dispatched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture returned false")
        }
    }

    /**
     * Injects a swipe/drag from normalized start to normalized end coordinates
     * (both in the range 0..1).
     */
    fun injectSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        val metrics = resources.displayMetrics
        val sx = (startX.coerceIn(0f, 1f) * metrics.widthPixels)
        val sy = (startY.coerceIn(0f, 1f) * metrics.heightPixels)
        val ex = (endX.coerceIn(0f, 1f) * metrics.widthPixels)
        val ey = (endY.coerceIn(0f, 1f) * metrics.heightPixels)

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Swipe completed from ($sx, $sy) to ($ex, $ey)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe cancelled from ($sx, $sy) to ($ex, $ey)")
            }
        }, null)

        if (!dispatched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture returned false for swipe")
        }
    }

    companion object {
        private const val TAG = "ClientAccessibility"
        private const val TAP_DURATION_MS = 80L
        private const val SWIPE_DURATION_MS = 300L
        @Volatile
        var instance: ClientAccessibilityService? = null
            private set
    }
}
