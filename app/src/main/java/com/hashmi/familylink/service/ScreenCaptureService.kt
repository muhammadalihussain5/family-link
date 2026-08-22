package com.hashmi.familylink.service

import android.Manifest
import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.hashmi.familylink.data.ProjectionGrant
import com.hashmi.familylink.data.StreamMessage
import com.hashmi.familylink.data.UserPreferencesRepository
import com.hashmi.familylink.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: UserPreferencesRepository

    /** True while frames/audio are being sent to the hub. */
    private val streaming = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system or user")
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
        prefs = UserPreferencesRepository(this)
        val thread = HandlerThread("family-link-capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_PAUSE -> {
                if (mediaProjection == null) stopSelf() else pauseStreaming()
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (mediaProjection == null) stopSelf() else resumeStreaming()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.parcelableIntent(EXTRA_RESULT_DATA)
        if (resultData != null && resultCode == Activity.RESULT_OK) {
            // Remember the consent so the hub can restart sharing later
            // without asking again (until reboot or revocation).
            persistGrant(resultCode, resultData)
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

    private fun persistGrant(resultCode: Int, resultData: Intent) {
        val uri = runCatching { resultData.toUri(Intent.URI_INTENT_SCHEME) }.getOrNull()
        if (uri != null) {
            serviceScope.launch {
                runCatching { prefs.saveProjectionGrant(ProjectionGrant(resultCode, uri)) }
            }
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture()
        val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            mpManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            // The saved consent is no longer valid (reboot / OS revoked it).
            Log.e(TAG, "MediaProjection could not be created", e)
            serviceScope.launch { runCatching { prefs.clearProjectionGrant() } }
            LinkNotifications.notifyCastRequested(this)
            stopSelf()
            return
        } ?: run {
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

        streaming.set(true)
        isStreaming = true
        isProjectionHeld = true

        reader.setOnImageAvailableListener({ imageReader ->
            val now = System.currentTimeMillis()
            val interval = if (NetworkManager.client.isRelay.value) FRAME_INTERVAL_RELAY_MS else FRAME_INTERVAL_MS
            if (!streaming.get() || now - lastFrameAt < interval) {
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

        startAudioCapture(projection)
    }

    /**
     * Pauses the stream but keeps the MediaProjection alive, so the hub can
     * resume sharing instantly without a new consent dialog.
     */
    private fun pauseStreaming() {
        if (!streaming.compareAndSet(true, false)) return
        isStreaming = false
        stopAudioCapture()
        Log.d(TAG, "Screen sharing paused (projection kept alive)")
    }

    private fun resumeStreaming() {
        if (mediaProjection == null) return
        if (!streaming.compareAndSet(false, true)) return
        isStreaming = true
        mediaProjection?.let { startAudioCapture(it) }
        Log.d(TAG, "Screen sharing resumed")
    }

    /**
     * Captures the audio this device is playing (Android 10+) and streams it
     * as PCM chunks to the hub. Apps can individually opt out of capture, and
     * calls/DRM audio are never capturable.
     */
    private fun startAudioCapture(projection: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO not granted; audio will not be shared")
            return
        }
        stopAudioCapture()
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val record = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AUDIO_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_CHUNK_BYTES) * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "AudioRecord failed to initialize; audio will not be shared")
                return
            }

            audioRecord = record
            record.startRecording()
            val thread = Thread {
                val buffer = ByteArray(AUDIO_CHUNK_BYTES)
                while (!Thread.currentThread().isInterrupted && audioRecord === record) {
                    val read = try {
                        record.read(buffer, 0, buffer.size)
                    } catch (_: Exception) {
                        break
                    }
                    if (read > 0 && streaming.get()) {
                        val chunk = buffer.copyOf(read)
                        NetworkManager.client.sendMessage(
                            StreamMessage.AudioChunk(
                                data = chunk,
                                sampleRate = AUDIO_SAMPLE_RATE,
                                encoding = AudioFormat.ENCODING_PCM_16BIT,
                                channelMask = AudioFormat.CHANNEL_IN_MONO
                            )
                        )
                    }
                }
            }.apply { name = "family-link-audio" }
            audioThread = thread
            thread.start()
            Log.d(TAG, "Audio capture started")
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture unavailable: ${e.message}")
            stopAudioCapture()
        }
    }

    private fun stopAudioCapture() {
        try {
            audioThread?.interrupt()
            audioThread = null
            audioRecord?.apply {
                runCatching { stop() }
                runCatching { release() }
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture", e)
        }
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
        streaming.set(false)
        isStreaming = false
        isProjectionHeld = false
        stopAudioCapture()
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
        serviceScope.cancel()
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
        /** Slower pacing when the stream is tunneled through the internet relay. */
        private const val FRAME_INTERVAL_RELAY_MS = 400L
        private const val AUDIO_SAMPLE_RATE = 16_000
        private const val AUDIO_CHUNK_BYTES = 3_200

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        const val ACTION_PAUSE = "com.hashmi.familylink.capture.PAUSE"
        const val ACTION_RESUME = "com.hashmi.familylink.capture.RESUME"
        const val ACTION_STOP = "com.hashmi.familylink.capture.STOP"

        /** Visible to the UI so dashboards can reflect the hub-driven state. */
        @Volatile
        var isStreaming: Boolean = false
            private set

        @Volatile
        var isProjectionHeld: Boolean = false
            private set

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

        /** Hub pressed "stop": pause sending but keep the projection for instant resume. */
        fun pause(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_PAUSE }
            )
        }

        fun resume(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_RESUME }
            )
        }

        /** Local, full stop (releases the projection; hub restart needs consent again on Android 14+). */
        fun stopService(context: Context) {
            context.startService(
                Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
            )
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
