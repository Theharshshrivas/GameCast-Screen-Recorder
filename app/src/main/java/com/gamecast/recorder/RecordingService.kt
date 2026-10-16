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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.MediaMuxer
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
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
        const val EXTRA_QUALITY_LONG_EDGE = "quality_long_edge"
        const val EXTRA_QUALITY_LABEL = "quality_label"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "gamecast_recording"
        private const val NOTIFICATION_ID = 4101
        private const val DEFAULT_LONG_EDGE = 1920
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BIT_RATE = 128_000
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var recordingStarted = false
    private var paused = false
    private var internalAudioEnabled = false
    private var micEnabled = false

    private var audioThread: Thread? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var audioPtsUs = 0L
    private var audioFailure: String? = null

    private val audioRunning = AtomicBoolean(false)
    private val audioPaused = AtomicBoolean(false)
    private val muxerLock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recordingStarted) {
                stopRecording(
                    save = true,
                    message = "Screen capture ended",
                    stopProjection = false
                )
            }
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

                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                    val resultData: Intent? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                EXTRA_PROJECTION_DATA,
                                Intent::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                        }

                    if (resultData == null) {
                        throw IllegalStateException(
                            "Screen capture permission data is missing."
                        )
                    }

                    micEnabled = intent.getBooleanExtra(
                        EXTRA_MIC_ENABLED,
                        false
                    )

                    startRecording(
                        resultCode = resultCode,
                        resultData = resultData,
                        micEnabled = micEnabled,
                        requestedLongEdge = intent.getIntExtra(
                            EXTRA_QUALITY_LONG_EDGE,
                            DEFAULT_LONG_EDGE
                        ),
                        qualityLabel = intent.getStringExtra(
                            EXTRA_QUALITY_LABEL
                        ) ?: "1080p"
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            recorder?.pause()
                        }
                        paused = true
                        audioPaused.set(true)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            recorder?.resume()
                        }
                        paused = false
                        audioPaused.set(false)
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
        micEnabled: Boolean,
        requestedLongEdge: Int,
        qualityLabel: String
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
        val sourceWidth = metrics.widthPixels
        val sourceHeight = metrics.heightPixels
        val density = metrics.densityDpi

        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw IllegalStateException("Invalid screen dimensions.")
        }

        val sourceLongEdge = max(sourceWidth, sourceHeight).toFloat()
        val requestedEdge = requestedLongEdge.coerceIn(854, 2560)
        val scale = min(1f, requestedEdge / sourceLongEdge)

        val width = evenDimension(sourceWidth * scale)
        val height = evenDimension(sourceHeight * scale)

        if (width <= 0 || height <= 0) {
            throw IllegalStateException("Invalid output dimensions.")
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
            mediaRecorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            mediaRecorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        }

        mediaRecorder.setVideoSize(width, height)
        mediaRecorder.setVideoFrameRate(30)

        val videoBitRate = when (qualityLabel) {
            "480p" -> 2_000_000
            "540p" -> 3_000_000
            "720p" -> 5_000_000
            "1080p" -> 8_000_000
            "2K" -> 12_000_000
            else -> 8_000_000
        }

        mediaRecorder.setVideoEncodingBitRate(videoBitRate)
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

        /*
         * Internal audio is started only when microphone audio is off.
         * MediaRecorder and a separate MediaMuxer cannot safely write
         * their tracks into the same MP4 at the same time.
         */
        if (!micEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startInternalAudioCapture()
                internalAudioEnabled = true
            } catch (e: Exception) {
                internalAudioEnabled = false
                audioFailure = e.message ?: "Internal audio is unavailable"
            }
        }

        val audioDescription = when {
            micEnabled -> "with microphone audio"
            internalAudioEnabled -> "with internal game audio"
            else -> "without audio" +
                (audioFailure?.let { " ($it)" } ?: "")
        }

        sendStatus(
            true,
            false,
            "Recording $qualityLabel ($width × $height), $audioDescription"
        )
        updateNotification("Recording in progress • $qualityLabel")
    }

    private fun startInternalAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException(
                "Internal audio requires Android 10 or newer."
            )
        }

        val currentProjection = projection
            ?: throw IllegalStateException("Screen projection is unavailable.")

        val captureConfig =
            AudioPlaybackCaptureConfiguration.Builder(currentProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

        val format = AudioFormat.Builder()
            .setEncoding(AUDIO_ENCODING)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AUDIO_CHANNEL_MASK)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_MASK,
            AUDIO_ENCODING
        )

        if (minBuffer <= 0) {
            throw IllegalStateException("Could not initialize audio buffer.")
        }

        val bufferSize = max(minBuffer * 2, 16_384)

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(
                "Internal audio capture could not be initialized."
            )
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_SAMPLE_RATE,
            2
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        val codec = MediaCodec.createEncoderByType(
            MediaFormat.MIMETYPE_AUDIO_AAC
        )

        codec.configure(
            audioFormat,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        audioRecord = record
        audioCodec = codec
        audioRunning.set(true)
        audioPaused.set(false)
        audioPtsUs = 0L

        codec.start()
        record.startRecording()

        audioThread = Thread({
            try {
                pumpInternalAudio(record, codec)
            } catch (e: Exception) {
                audioFailure = e.message ?: "Internal audio capture failed"
            }
        }, "GameCastInternalAudio").apply {
            isDaemon = true
            start()
        }
    }

    private fun pumpInternalAudio(
        record: AudioRecord,
        codec: MediaCodec
    ) {
        val info = MediaCodec.BufferInfo()
        val pcmBuffer = ByteArray(8192)

        while (audioRunning.get()) {
            if (audioPaused.get()) {
                Thread.sleep(20)
                continue
            }

            val inputIndex = codec.dequeueInputBuffer(10_000)

            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)

                if (inputBuffer != null) {
                    inputBuffer.clear()

                    val capacity = min(inputBuffer.remaining(), pcmBuffer.size)
                    val read = record.read(
                        pcmBuffer,
                        0,
                        capacity,
                        AudioRecord.READ_BLOCKING
                    )

                    if (read > 0) {
                        inputBuffer.put(pcmBuffer, 0, read)

                        val frameCount = read / 4
                        val pts = audioPtsUs
                        audioPtsUs +=
                            frameCount * 1_000_000L / AUDIO_SAMPLE_RATE

                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            read,
                            pts,
                            0
                        )
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            audioPtsUs,
                            0
                        )
                    }
                }
            }

            drainAudioEncoder(codec, info, false)
        }

        val endIndex = codec.dequeueInputBuffer(10_000)
        if (endIndex >= 0) {
            codec.queueInputBuffer(
                endIndex,
                0,
                0,
                audioPtsUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }

        drainAudioEncoder(codec, info, true)
    }

    private fun drainAudioEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean
    ) {
        var attempts = 0

        while (attempts < if (endOfStream) 20 else 1) {
            val outputIndex = codec.dequeueOutputBuffer(info, 10_000)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    attempts++
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        val currentMuxer = muxer
                            ?: throw IllegalStateException(
                                "Audio muxer is unavailable."
                            )

                        if (audioTrack < 0) {
                            audioTrack = currentMuxer.addTrack(
                                codec.outputFormat
                            )
                        }

                        startMuxerIfReady()
                    }
                }

                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)

                    if (outputBuffer != null &&
                        info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        synchronized(muxerLock) {
                            if (muxerStarted && audioTrack >= 0) {
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                muxer?.writeSampleData(
                                    audioTrack,
                                    outputBuffer,
                                    info
                                )
                            }
                        }
                    }

                    val isEnd =
                        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (isEnd) return
                }
            }
        }
    }

    private fun startMuxerIfReady() {
        if (!muxerStarted && audioTrack >= 0 && videoTrack >= 0) {
            muxer?.start()
            muxerStarted = true
        }
    }

    private fun stopInternalAudioCapture() {
        audioRunning.set(false)

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioThread?.join(700)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioThread = null

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null

        try {
            audioCodec?.stop()
        } catch (_: Exception) {
        }

        try {
            audioCodec?.release()
        } catch (_: Exception) {
        }
        audioCodec = null
        internalAudioEnabled = false
    }

    private fun evenDimension(value: Float): Int {
        val rounded = value.roundToInt().coerceAtLeast(2)
        return if (rounded % 2 == 0) rounded else rounded - 1
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
            ) ?: throw IllegalStateException("Could not create a video file.")

            outputUri = uri

            outputDescriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open the video file.")

            mediaRecorder.setOutputFile(outputDescriptor!!.fileDescriptor)

            val descriptor = outputDescriptor!!
            muxer = MediaMuxer(
                descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        } else {
            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "GameCast"
            )

            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Could not create the video folder.")
            }

            val file = File(directory, name)
            outputFile = file
            mediaRecorder.setOutputFile(file.absolutePath)
        }

        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
    }

    private fun stopRecording(
        save: Boolean,
        message: String? = null,
        stopProjection: Boolean = true
    ) {
        var saved = save && recordingStarted
        val currentRecorder = recorder

        /*
         * The MediaRecorder file and the internal-audio muxer share a
         * descriptor but cannot both finalize a valid MP4. Do not attempt
         * to mux internal audio into the MediaRecorder output.
         */
        stopInternalAudioCapture()

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
            synchronized(muxerLock) {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            }
        } catch (_: Exception) {
            saved = false
        }
        muxer = null
        muxerStarted = false

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

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun createNotification(message: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            4102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(message))
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
