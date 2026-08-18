package com.hashmi.familylink.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.network.NetworkManager
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopSelf()
        }
    }

    private var screenWidth = 720
    private var screenHeight = 1280
    private var screenDensity = 320
    private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("family-link-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent == null) return START_STICKY

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultData != null && resultCode == Activity.RESULT_OK) {
            startCapture(resultCode, resultData)
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = LinkNotifications.captureNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                LinkNotifications.ID_CAPTURE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(LinkNotifications.ID_CAPTURE, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture()
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpManager.getMediaProjection(resultCode, resultData) ?: run {
            Log.e(TAG, "MediaProjection was null")
            stopSelf()
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, captureHandler)

        resolveScreenSize()

        val reader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader = reader
        virtualDisplay = projection.createVirtualDisplay(
            "FamilyLinkCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler
        )

        reader.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS) {
                imageReader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val paddedWidth = screenWidth + rowPadding / pixelStride

                val padded = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val cropped = if (paddedWidth != screenWidth) {
                    Bitmap.createBitmap(padded, 0, 0, screenWidth, screenHeight).also {
                        padded.recycle()
                    }
                } else {
                    padded
                }

                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
                cropped.recycle()

                NetworkManager.client.sendMessage(
                    StreamMessage.ScreenFrame(out.toByteArray(), screenWidth, screenHeight)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing frame", e)
            } finally {
                image.close()
            }
        }, captureHandler)
    }

    private fun resolveScreenSize() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            screenWidth = (bounds.width() * SCALE).toInt().coerceAtLeast(360)
            screenHeight = (bounds.height() * SCALE).toInt().coerceAtLeast(640)
            screenDensity = density
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            screenWidth = (metrics.widthPixels * SCALE).toInt().coerceAtLeast(360)
            screenHeight = (metrics.heightPixels * SCALE).toInt().coerceAtLeast(640)
            screenDensity = metrics.densityDpi
        }
        if (screenWidth % 2 != 0) screenWidth -= 1
        if (screenHeight % 2 != 0) screenHeight -= 1
    }

    private fun stopCapture() {
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping capture", e)
        } finally {
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
        }
    }

    override fun onDestroy() {
        stopCapture()
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val SCALE = 0.4f
        private const val FRAME_INTERVAL_MS = 160L
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}

private fun Intent.parcelableIntent(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
