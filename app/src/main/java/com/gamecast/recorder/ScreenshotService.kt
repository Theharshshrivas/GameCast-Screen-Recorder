
package com.gamecast.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : Service() {

    companion object {
        const val ACTION_CAPTURE = "com.gamecast.recorder.CAPTURE_SCREENSHOT"
        const val ACTION_RESULT = "com.gamecast.recorder.SCREENSHOT_RESULT"
        const val EXTRA_RESULT_CODE = "screenshot_result_code"
        const val EXTRA_PROJECTION_DATA = "screenshot_projection_data"
        const val EXTRA_MESSAGE = "screenshot_message"

        private const val CHANNEL_ID = "gamecast_screenshot"
        private const val NOTIFICATION_ID = 4201
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GameCast Screenshot Capture",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action != ACTION_CAPTURE || projection != null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("GameCast")
                .setContentText("Capturing screenshot")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
            } ?: throw IllegalStateException("Screen permission data missing")

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Screen capture unavailable")

            captureOneFrame()
        } catch (e: Exception) {
            finishCapture(false, "Screenshot failed: ${e.message ?: "Unknown error"}")
        }

        return START_NOT_STICKY
    }

    private fun captureOneFrame() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(2)
        val height = metrics.heightPixels.coerceAtLeast(2)
        val density = metrics.densityDpi

        reader = ImageReader.newInstance(
            width,
            height,
            android.graphics.PixelFormat.RGBA_8888,
            2
        )

        val currentReader = reader ?: throw IllegalStateException("ImageReader unavailable")
        val currentProjection = projection
            ?: throw IllegalStateException("Screen projection unavailable")

        currentProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!finished) {
                        finishCapture(false, "Screen capture was stopped")
                    }
                }
            },
            Handler(Looper.getMainLooper())
        )

        display = currentProjection.createVirtualDisplay(
            "GameCastScreenshot",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            currentReader.surface,
            null,
            Handler(Looper.getMainLooper())
        ) ?: throw IllegalStateException("Could not create screen display")

        currentReader.setOnImageAvailableListener({ imageReader ->
            if (finished) return@setOnImageAvailableListener

            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                val paddedBitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

                paddedBitmap.copyPixelsFromBuffer(plane.buffer)

                val bitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    image.width,
                    image.height
                )
                paddedBitmap.recycle()

                val saved = saveBitmap(bitmap)
                bitmap.recycle()

                finishCapture(
                    saved,
                    if (saved) "Screenshot saved to Pictures/GameCast"
                    else "Could not save screenshot"
                )
            } catch (e: Exception) {
                finishCapture(false, "Screenshot failed: ${e.message ?: "Unknown error"}")
            } finally {
                image.close()
            }
        }, Handler(Looper.getMainLooper()))

        Handler(Looper.getMainLooper()).postDelayed({
            if (!finished) {
                finishCapture(false, "Screen image was not received. Try again.")
            }
        }, 5000)
    }

    private fun saveBitmap(bitmap: Bitmap): Boolean {
        val name = "GameCast_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".png"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/GameCast"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: return false

            outputUri = uri

            try {
                contentResolver.openOutputStream(uri, "w")?.use { stream ->
                    if (stream == null) throw IllegalStateException("File stream unavailable")
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IllegalStateException("PNG encoding failed")
                    }
                } ?: throw IllegalStateException("Could not open screenshot file")

                contentResolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    },
                    null,
                    null
                )
                true
            } catch (_: Exception) {
                contentResolver.delete(uri, null, null)
                false
            }
        } else {
            val directory = File(
                getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                "GameCast"
            )

            if (!directory.exists() && !directory.mkdirs()) return false

            val file = File(directory, name)
            outputFile = file

            try {
                file.outputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
            } catch (_: Exception) {
                file.delete()
                false
            }
        }
    }

    private fun finishCapture(success: Boolean, message: String) {
        if (finished) return
        finished = true

        try {
            display?.release()
        } catch (_: Exception) {
        }
        display = null

        try {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            descriptor?.close()
        } catch (_: Exception) {
        }
        descriptor = null

        if (!success) {
            outputUri?.let {
                try {
                    contentResolver.delete(it, null, null)
                } catch (_: Exception) {
                }
            }
            outputFile?.delete()
        }

        sendBroadcast(
            Intent(ACTION_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_MESSAGE, message)
            }
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finished) {
            finishCapture(false, "Screenshot service stopped")
        }
        super.onDestroy()
    }
}
