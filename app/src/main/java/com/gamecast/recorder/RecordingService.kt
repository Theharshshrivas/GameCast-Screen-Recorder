
package com.gamecast.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.gamecast.recorder.START"
        const val ACTION_STOP = "com.gamecast.recorder.STOP"
        const val ACTION_PAUSE = "com.gamecast.recorder.PAUSE"
        const val ACTION_RESUME = "com.gamecast.recorder.RESUME"
        const val ACTION_STATUS = "com.gamecast.recorder.STATUS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_MIC_ENABLED = "mic_enabled"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "gamecast_recording"
        private const val NOTIFICATION_ID = 4101
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var recordingStarted = false
    private var paused = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording(
                save = true,
                message = "Screen capture ended",
                stopProjection = false
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (recorder != null || projection != null) {
                    return START_NOT_STICKY
                }

                try {
                    startAsForeground()

                    val resultCode =
                        intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                    val resultData: Intent? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                EXTRA_PROJECTION_DATA,
                                Intent::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(
                                EXTRA_PROJECTION_DATA
                            )
                        }

                    if (resultData == null) {
                        throw IllegalStateException(
                            "Screen capture permission data is missing."
                        )
                    }

                    val micEnabled = intent.getBooleanExtra(
                        EXTRA_MIC_ENABLED,
                        false
                    )

                    startRecording(
                        resultCode,
                        resultData,
                        micEnabled
                    )
                } catch (e: Exception) {
                    stopRecording(
                        save = false,
                        message = "Could not start recording: " +
                            (e.message ?: "Unknown error")
                    )
                }
            }

            ACTION_STOP -> stopRecording(save = true)

            ACTION_PAUSE -> {
                try {
                    if (recordingStarted && !paused) {
                        recorder?.pause()
                        paused = true
                        sendStatus(true, true, "Recording paused")
                        updateNotification("Recording paused")
                    }
                } catch (e: Exception) {
                    sendStatus(
                        recordingStarted,
                        paused,
                        "Pause failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }

            ACTION_RESUME -> {
                try {
                    if (recordingStarted && paused) {
                        recorder?.resume()
                        paused = false
                        sendStatus(true, false, "Recording resumed")
                        updateNotification("Recording in progress")
                    }
                } catch (e: Exception) {
                    sendStatus(
                        recordingStarted,
                        paused,
                        "Resume failed: ${e.message ?: "Unknown error"}"
                    )
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = createNotification("Preparing recording")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent,
        micEnabled: Boolean
    ) {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException(
                "Android did not provide screen capture."
            )

        projection?.registerCallback(
            projectionCallback,
            Handler(Looper.getMainLooper())
        )

        val metrics = getScreenMetrics()
        val width = (metrics.widthPixels / 2) * 2
        val height = (metrics.heightPixels / 2) * 2
        val density = metrics.densityDpi

        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Invalid screen dimensions.")
        }

        val name = "GameCast_" +
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date()) + ".mp4"

        val mediaRecorder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

        recorder = mediaRecorder

        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)

        if (micEnabled) {
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (micEnabled) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128_000)
            mediaRecorder.setAudioSamplingRate(44_100)
        }

        mediaRecorder.setVideoSize(width, height)
        mediaRecorder.setVideoFrameRate(30)
        mediaRecorder.setVideoEncodingBitRate(8_000_000)

        configureOutput(name, mediaRecorder)
        mediaRecorder.prepare()

        virtualDisplay = projection?.createVirtualDisplay(
            "GameCastScreenCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder.surface,
            null,
            null
        ) ?: throw IllegalStateException(
            "Could not create the screen display."
        )

        mediaRecorder.start()
        recordingStarted = true
        paused = false

        val message = if (micEnabled) {
            "Recording screen with microphone audio"
        } else {
            "Recording screen without microphone audio"
        }

        sendStatus(true, false, message)
        updateNotification("Recording in progress")
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        val windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        return metrics
    }

    private fun configureOutput(
        name: String,
        mediaRecorder: MediaRecorder
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/GameCast"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException(
                "Could not create a video file."
            )

            outputUri = uri

            outputDescriptor = contentResolver.openFileDescriptor(
                uri,
                "w"
            ) ?: throw IllegalStateException(
                "Could not open the video file."
            )

            mediaRecorder.setOutputFile(
                outputDescriptor!!.fileDescriptor
            )
        } else {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "GameCast"
            )

            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException(
                    "Could not create the video folder."
                )
            }

            val file = File(directory, name)
            outputFile = file
            mediaRecorder.setOutputFile(file.absolutePath)
        }
    }

    private fun stopRecording(
        save: Boolean,
        message: String? = null,
        stopProjection: Boolean = true
    ) {
        var saved = save && recordingStarted

        val currentRecorder = recorder
        recorder = null

        if (currentRecorder != null) {
            if (recordingStarted) {
                try {
                    currentRecorder.stop()
                } catch (_: RuntimeException) {
                    saved = false
                }
            }

            try {
                currentRecorder.reset()
            } catch (_: Exception) {
                saved = false
            }

            try {
                currentRecorder.release()
            } catch (_: Exception) {
                saved = false
            }
        }

        recordingStarted = false
        paused = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        val currentProjection = projection
        projection = null

        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }

            if (stopProjection) {
                try {
                    currentProjection.stop()
                } catch (_: Exception) {
                }
            }
        }

        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }
        outputDescriptor = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                try {
                    if (saved) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, values, null, null)
                    } else {
                        contentResolver.delete(uri, null, null)
                    }
                } catch (_: Exception) {
                    saved = false
                    try {
                        contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            }
        } else if (!saved) {
            outputFile?.delete()
        }

        outputUri = null
        outputFile = null

        val finalMessage = message ?: if (saved) {
            "Recording saved to Movies/GameCast"
        } else {
            "Recording stopped. The video could not be saved."
        }

        sendStatus(false, false, finalMessage)

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }

        stopSelf()
    }

    private fun sendStatus(
        recording: Boolean,
        isPaused: Boolean,
        message: String
    ) {
        val statusIntent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RECORDING, recording)
            putExtra(EXTRA_PAUSED, isPaused)
            putExtra(EXTRA_MESSAGE, message)
        }

        sendBroadcast(statusIntent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GameCast Recording",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Screen recording status"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(message: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            4102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("GameCast Screen Recorder")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(message))
    }

    override fun onDestroy() {
        if (recorder != null || projection != null) {
            stopRecording(
                save = false,
                message = "Recording service closed unexpectedly"
            )
        }
        super.onDestroy()
    }
}
