
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
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.Surface
import androidx.core.app.NotificationCompat
import java.io.File
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
        private const val FPS = 30
        private const val AUDIO_RATE = 44100
        private const val AUDIO_BITRATE = 128000
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var videoSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var recording = false
    private var paused = false
    private var micEnabled = false
    private var internalAudio = false
    private var width = 0
    private var height = 0
    private var videoPts = 0L
    private var audioPts = 0L
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private val running = AtomicBoolean(false)
    private val audioRunning = AtomicBoolean(false)
    private val muxLock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recording) stopRecording(true, "Screen capture ended")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GameCast Recording",
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
        when (intent?.action) {
            ACTION_START -> {
                if (recording || projection != null) return START_NOT_STICKY
                try {
                    startForegroundCompat()
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val data: Intent? =
                        if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(
                                EXTRA_PROJECTION_DATA, Intent::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                        }

                    requireNotNull(data) { "Screen capture permission is missing." }

                    micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, false)
                    val requested = intent.getIntExtra(
                        EXTRA_QUALITY_LONG_EDGE, 1920
                    )
                    val label = intent.getStringExtra(EXTRA_QUALITY_LABEL) ?: "1080p"

                    startRecording(resultCode, data, requested, label)
                } catch (e: Exception) {
                    stopRecording(false, "Could not start: ${e.message}")
                }
            }

            ACTION_STOP -> stopRecording(true, "Recording saved")
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = notification("Preparing recording")
        if (Build.VERSION.SDK_INT >= 29) {
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
        data: Intent,
        requestedLongEdge: Int,
        qualityLabel: String
    ) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
            ?: error("Screen capture permission was rejected.")

        projection!!.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val sourceW = metrics.widthPixels
        val sourceH = metrics.heightPixels
        require(sourceW > 0 && sourceH > 0) { "Invalid screen dimensions." }

        val scale = min(1f, requestedLongEdge.coerceIn(854, 2560) /
                max(sourceW, sourceH).toFloat())
        width = even(sourceW * scale)
        height = even(sourceH * scale)

        val name = "GameCast_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                ".mp4"

        createOutput(name)

        internalAudio = !micEnabled && Build.VERSION.SDK_INT >= 29

        if (micEnabled) {
            startMicRecorder(name, qualityLongEdgeBitrate(qualityLabel))
        } else {
            startEncodedRecording(
                qualityLongEdgeBitrate(qualityLabel),
                captureInternalAudio = internalAudio
            )
        }

        recording = true
        paused = false
        sendStatus(
            true, false,
            "Recording $qualityLabel ($width × $height)" +
                    when {
                        micEnabled -> " with microphone"
                        internalAudio -> " with internal audio"
                        else -> " without audio (internal capture requires Android 10+)"
                    }
        )
        updateNotification("Recording in progress • $qualityLabel")
    }

    /*
     * Microphone mode uses MediaRecorder for compatibility.
     * Internal audio mode uses MediaCodec + MediaMuxer; the two modes
     * deliberately do not write to the same MP4 concurrently.
     */
    private fun startMicRecorder(name: String, bitrate: Int) {
        val recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(AUDIO_BITRATE)
        recorder.setAudioSamplingRate(AUDIO_RATE)
        recorder.setVideoSize(width, height)
        recorder.setVideoFrameRate(FPS)
        recorder.setVideoEncodingBitRate(bitrate)

        if (Build.VERSION.SDK_INT >= 29) {
            val uri = outputUri ?: error("Output file is unavailable.")
            val pfd = contentResolver.openFileDescriptor(uri, "w")
                ?: error("Could not open output file.")
            try {
                recorder.setOutputFile(pfd.fileDescriptor)
                recorder.prepare()
                display = projection!!.createVirtualDisplay(
                    "GameCastScreenCapture", width, height, getDensity(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.surface, null, null
                ) ?: error("Could not create screen display.")
                recorder.start()
            } finally {
                pfd.close()
            }
        } else {
            val file = outputFile ?: error("Output file is unavailable.")
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            display = projection!!.createVirtualDisplay(
                "GameCastScreenCapture", width, height, getDensity(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface, null, null
            ) ?: error("Could not create screen display.")
            recorder.start()
        }

        // Keep the recorder reference for pause/resume/stop.
        micRecorder = recorder
    }

    private var micRecorder: MediaRecorder? = null

    private fun startEncodedRecording(
        bitrate: Int,
        captureInternalAudio: Boolean
    ) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoSurface = encoder.createInputSurface()
        encoder.start()
        videoCodec = encoder

        display = projection!!.createVirtualDisplay(
            "GameCastScreenCapture", width, height, getDensity(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoSurface, null, null
        ) ?: error("Could not create screen display.")

        if (captureInternalAudio) startAudioEncoder()

        running.set(true)
        videoThread = Thread({ drainVideoEncoder() }, "GameCastVideoEncoder")
            .apply { isDaemon = true; start() }
    }

    private fun startAudioEncoder() {
        check(Build.VERSION.SDK_INT >= 29) {
            "Internal audio requires Android 10 or newer."
        }

        val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val bufferSize = max(
            AudioRecord.getMinBufferSize(
                AUDIO_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ), 16384
        )

        val record = AudioRecord.Builder()
            .setAudioFormat(pcmFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(capture)
            .build()

        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "Internal audio capture could not be initialized."
        }

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, 2
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        record.startRecording()

        audioRecord = record
        audioCodec = encoder
        audioRunning.set(true)

        audioThread = Thread({ pumpAudioEncoder() }, "GameCastAudioEncoder")
            .apply { isDaemon = true; start() }
    }

    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                if (paused) {
                    Thread.sleep(20)
                    continue
                }

                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                        videoTrack = muxer!!.addTrack(codec.outputFormat)
                        startMuxerIfReady()
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            synchronized(muxLock) {
                                if (muxerStarted && videoTrack >= 0) {
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    muxer!!.writeSampleData(videoTrack, buffer, info)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        } catch (e: Exception) {
            if (recording) sendStatus(true, paused, "Video encoder: ${e.message}")
        }
    }

    private fun pumpAudioEncoder() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(8192)

        try {
            while (audioRunning.get()) {
                if (paused) {
                    Thread.sleep(20)
                    continue
                }

                val inputIndex = codec.dequeueInputBuffer(10000)
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)
                    if (input != null) {
                        input.clear()
                        val count = record.read(
                            pcm, 0, min(pcm.size, input.remaining()),
                            AudioRecord.READ_BLOCKING
                        )
                        if (count > 0) {
                            input.put(pcm, 0, count)
                            val frames = count / 4
                            val pts = audioPts
                            audioPts += frames * 1_000_000L / AUDIO_RATE
                            codec.queueInputBuffer(inputIndex, 0, count, pts, 0)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, 0, audioPts, 0)
                        }
                    }
                }

                drainAudioOutput(codec, info)
            }
        } catch (e: Exception) {
            if (recording) sendStatus(true, paused, "Audio capture: ${e.message}")
        }
    }

    private fun drainAudioOutput(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        when (val index = codec.dequeueOutputBuffer(info, 10000)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(muxLock) {
                audioTrack = muxer!!.addTrack(codec.outputFormat)
                startMuxerIfReady()
            }
            else -> if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    synchronized(muxLock) {
                        if (muxerStarted && audioTrack >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer!!.writeSampleData(audioTrack, buffer, info)
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            }
        }
    }

    private fun startMuxerIfReady() {
        if (!muxerStarted && videoTrack >= 0 &&
            (!internalAudio || audioTrack >= 0)
        ) {
            muxer!!.start()
            muxerStarted = true
        }
    }

    private fun pauseRecording() {
        if (!recording || paused) return
        try {
            if (micEnabled) {
                if (Build.VERSION.SDK_INT >= 24) micRecorder?.pause()
            } else {
                display?.setSurface(null)
            }
            paused = true
            sendStatus(true, true, "Recording paused")
            updateNotification("Recording paused")
        } catch (e: Exception) {
            sendStatus(recording, paused, "Pause failed: ${e.message}")
        }
    }

    private fun resumeRecording() {
        if (!recording || !paused) return
        try {
            if (micEnabled) {
                if (Build.VERSION.SDK_INT >= 24) micRecorder?.resume()
            } else {
                display?.setSurface(videoSurface)
            }
            paused = false
            sendStatus(true, false, "Recording resumed")
            updateNotification("Recording in progress")
        } catch (e: Exception) {
            sendStatus(recording, paused, "Resume failed: ${e.message}")
        }
    }

    private fun createOutput(name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/GameCast")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("Could not create video file.")
            val pfd = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: error("Could not open video file.")
            try {
                muxer = MediaMuxer(
                    pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } finally {
                pfd.close()
            }
        } else {
            val folder = File(
                getExternalFilesDir(Environment.DIRECTORY_MOVIES), "GameCast"
            )
            if (!folder.exists()) check(folder.mkdirs()) {
                "Could not create output directory."
            }
            outputFile = File(folder, name)
            muxer = MediaMuxer(
                outputFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
        }
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false
    }

    private fun stopRecording(save: Boolean, message: String? = null) {
        if (projection == null && !recording && micRecorder == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recording = false
        running.set(false)
        audioRunning.set(false)

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioThread?.join(500) } catch (_: Exception) {}
        try { videoThread?.join(500) } catch (_: Exception) {}
        audioThread = null
        videoThread = null

        var saved = save

        try { display?.release() } catch (_: Exception) {}
        display = null

        try { micRecorder?.stop() } catch (_: Exception) {
            if (micEnabled) saved = false
        }
        try { micRecorder?.release() } catch (_: Exception) {}
        micRecorder = null

        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null

        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { videoSurface?.release() } catch (_: Exception) {}
        videoSurface = null

        try {
            synchronized(muxLock) {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
        } catch (_: Exception) {
            saved = false
        }
        muxer = null
        muxerStarted = false

        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        projection = null

        if (Build.VERSION.SDK_INT >= 29) {
            outputUri?.let { uri ->
                try {
                    if (saved && (!micEnabled && !muxerStarted || micEnabled)) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, values, null, null)
                    } else {
                        contentResolver.delete(uri, null, null)
                        saved = false
                    }
                } catch (_: Exception) {
                    saved = false
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        } else if (!saved) {
            outputFile?.delete()
        }

        outputUri = null
        outputFile = null
        paused = false
        internalAudio = false
        micEnabled = false

        sendStatus(
            false, false,
            message ?: if (saved) "Recording saved to Movies/GameCast"
            else "Recording could not be saved."
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun qualityLongEdgeBitrate(label: String): Int = when (label) {
        "480p" -> 2_000_000
        "540p" -> 3_000_000
        "720p" -> 5_000_000
        "1080p" -> 8_000_000
        "2K" -> 12_000_000
        else -> 8_000_000
    }

    private fun even(value: Float): Int {
        val rounded = value.roundToInt().coerceAtLeast(2)
        return if (rounded % 2 == 0) rounded else rounded - 1
    }

    private fun getDensity(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.densityDpi
    }

    private fun sendStatus(isRecording: Boolean, isPaused: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_RECORDING, isRecording)
            putExtra(EXTRA_PAUSED, isPaused)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun notification(message: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }
        val pending = PendingIntent.getService(
            this, 4102, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("GameCast Screen Recorder")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "Stop", pending)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        if (projection != null || micRecorder != null) {
            stopRecording(false, "Recording service closed")
        }
        super.onDestroy()
    }
}
