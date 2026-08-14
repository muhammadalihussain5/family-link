package com.hashmi.familylink.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class ClientAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                Log.d(TAG, "Notification received: ${event.parcelableData}")
                // TODO: Send notification data to server
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "View clicked: ${event.className}")
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected")
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Injects a tap event at the specified normalized coordinates.
     * @param normalizedX X coordinate from 0.0 to 1.0
     * @param normalizedY Y coordinate from 0.0 to 1.0
     */
    fun injectTap(normalizedX: Float, normalizedY: Float) {
        val metrics = resources.displayMetrics
        val x = normalizedX * metrics.widthPixels
        val y = normalizedY * metrics.heightPixels
        
        val path = Path()
        path.moveTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 100))
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "Tap gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.d(TAG, "Tap gesture cancelled")
            }
        }, null)
    }

    companion object {
        private const val TAG = "ClientAccessibility"
        var instance: ClientAccessibilityService? = null
    }
}
