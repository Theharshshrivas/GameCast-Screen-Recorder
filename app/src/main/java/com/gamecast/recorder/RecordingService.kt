
package com.gamecast.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
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
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileDescriptor
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

    private data class PendingSample(
        val track: Int,
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var micRecorder: MediaRecorder? = null
    private var videoSurface: Surface? = null

    private var muxer: MediaMuxer? = null
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private var audioExpected = false
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
    private var startFailed = false

    private val running = AtomicBoolean(false)
    private val audioRunning = AtomicBoolean(false)
    private val muxLock = Any()
    private val pendingSamples = ArrayList<PendingSample>()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (recording) {
                Handler(Looper.getMainLooper()).post {
                    stopRecording(true, "Screen capture ended")
                }
            }
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
                    micEnabled = intent.getBooleanExtra(EXTRA_MIC_ENABLED, false)
                    startForegroundCompat(micEnabled)

                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_PROJECTION_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                    }

                    requireNotNull(data) {
                        "Screen capture permission is missing."
                    }

                    val requested = intent.getIntExtra(
                        EXTRA_QUALITY_LONG_EDGE, 1920
                    )
                    val label = intent.getStringExtra(EXTRA_QUALITY_LABEL)
                        ?: "1080p"

                    startRecording(resultCode, data, requested, label)
                } catch (e: Exception) {
                    startFailed = true
                    stopRecording(
                        false,
                        "Could not start recording: ${e.message ?: "unknown error"}"
                    )
                }
            }

            ACTION_STOP -> stopRecording(true, "Recording stopped")
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat(withMic: Boolean) {
        val notification = notification("Preparing recording")

        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (withMic) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, types)
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

        projection!!.registerCallback(
            projectionCallback,
            Handler(Looper.getMainLooper())
        )

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val sourceW = metrics.widthPixels
        val sourceH = metrics.heightPixels
        require(sourceW > 0 && sourceH > 0) {
            "Invalid screen dimensions."
        }

        val scale = min(
            1f,
            requestedLongEdge.coerceIn(854, 2560) /
                max(sourceW, sourceH).toFloat()
        )

        width = even(sourceW * scale)
        height = even(sourceH * scale)

        val name = "GameCast_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
            ".mp4"

        internalAudio = !micEnabled && Build.VERSION.SDK_INT >= 29
        audioExpected = internalAudio

        if (micEnabled) {
            createOutput(name, useMuxer = false)
            startMicRecording(name, qualityLongEdgeBitrate(qualityLabel))
        } else {
            createOutput(name, useMuxer = true)
            startEncodedRecording(
                qualityLongEdgeBitrate(qualityLabel),
                captureInternalAudio = internalAudio
            )
        }

        recording = true
        paused = false

        val audioDescription = when {
            micEnabled -> "microphone audio"
            internalAudio -> "internal audio"
            else -> "no audio; internal capture requires Android 10+"
        }

        sendStatus(
            true,
            false,
            "Recording $qualityLabel ($width × $height), $audioDescription"
        )
        updateNotification("Recording in progress • $qualityLabel")
    }

    private fun startMicRecording(name: String, bitrate: Int) {
        val recorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        micRecorder = recorder

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
            val uri = outputUri ?: error("Output URI is unavailable.")
            outputPfd = contentResolver.openFileDescriptor(uri, "w")
            val pfd = outputPfd ?: error("Could not open output file.")
            recorder.setOutputFile(pfd.fileDescriptor)
        } else {
            val file = outputFile ?: error("Output file is unavailable.")
            recorder.setOutputFile(file.absolutePath)
        }

        recorder.prepare()

        display = projection!!.createVirtualDisplay(
            "GameCastScreenCapture",
            width,
            height,
            getDensity(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorder.surface,
            null,
            null
        ) ?: error("Could not create screen display.")

        recorder.start()
    }

    private fun startEncodedRecording(
        bitrate: Int,
        captureInternalAudio: Boolean
    ) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val encoder = MediaCodec.createEncoderByType(
            MediaFormat.MIMETYPE_VIDEO_AVC
        )
        videoCodec = encoder
        encoder.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        videoSurface = encoder.createInputSurface()
        encoder.start()

        if (captureInternalAudio) {
            startAudioEncoder()
        }

        display = projection!!.createVirtualDisplay(
            "GameCastScreenCapture",
            width,
            height,
            getDensity(),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoSurface,
            null,
            null
        ) ?: error("Could not create screen display.")

        running.set(true)
        videoThread = Thread(
            { drainVideoEncoder() },
            "GameCastVideoEncoder"
        ).apply {
            isDaemon = true
            start()
        }
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
            ),
            16384
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
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AUDIO_RATE,
            2
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
        }

        val encoder = MediaCodec.createEncoderByType(
            MediaFormat.MIMETYPE_AUDIO_AAC
        )

        try {
            encoder.configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            encoder.start()
            record.startRecording()
        } catch (e: Exception) {
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            throw e
        }

        audioRecord = record
        audioCodec = encoder
        audioRunning.set(true)

        audioThread = Thread(
            { pumpAudioEncoder() },
            "GameCastAudioEncoder"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun drainVideoEncoder() {
        val codec = videoCodec ?: return
        val info = MediaCodec.BufferInfo()

        try {
            while (running.get()) {
                val index = codec.dequeueOutputBuffer(info, 10000)

                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxLock) {
                            if (videoTrack == -1) {
                                videoTrack = muxer!!.addTrack(codec.outputFormat)
                            }
                            startMuxerIfReady()
                        }
                    }

                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)

                        try {
                            if (buffer != null &&
                                info.size > 0 &&
                                info.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)

                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)

                                synchronized(muxLock) {
                                    if (!paused && videoTrack >= 0) {
                                        writeOrQueueSample(
                                            PendingSample(
                                                videoTrack,
                                                bytes,
                                                info.presentationTimeUs,
                                                info.flags
                                            )
                                        )
                                    }
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (recording) {
                sendStatus(true, paused, "Video encoder error: ${e.message}")
            }
        }
    }

    private fun pumpAudioEncoder() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(8192)

        try {
            while (audioRunning.get()) {
                val inputIndex = codec.dequeueInputBuffer(10000)

                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex)

                    if (input == null) {
                        codec.queueInputBuffer(inputIndex, 0, 0, audioPts, 0)
                    } else {
                        input.clear()

                        val count = record.read(
                            pcm,
                            0,
                            min(pcm.size, input.remaining()),
                            AudioRecord.READ_BLOCKING
                        )

                        if (count > 0) {
                            if (paused) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, audioPts, 0
                                )
                            } else {
                                input.put(pcm, 0, count)
                                val pts = audioPts
                                val frames = count / 4
                                audioPts += frames * 1_000_000L / AUDIO_RATE

                                codec.queueInputBuffer(
                                    inputIndex, 0, count, pts, 0
                                )
                            }
                        } else {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, audioPts, 0
                            )
                        }
                    }
                }

                drainAudioOutput(codec, info)
            }
        } catch (e: Exception) {
            if (recording) {
                sendStatus(true, paused, "Audio capture error: ${e.message}")
            }
        }
    }

    private fun drainAudioOutput(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo
    ) {
        val index = codec.dequeueOutputBuffer(info, 10000)

        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                synchronized(muxLock) {
                    if (audioTrack == -1) {
                        audioTrack = muxer!!.addTrack(codec.outputFormat)
                    }
                    startMuxerIfReady()
                }
            }

            index >= 0 -> {
                val buffer = codec.getOutputBuffer(index)

                try {
                    if (buffer != null &&
                        info.size > 0 &&
                        info.flags and
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)

                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)

                        synchronized(muxLock) {
                            if (!paused && audioTrack >= 0) {
                                writeOrQueueSample(
                                    PendingSample(
                                        audioTrack,
                                        bytes,
                                        info.presentationTimeUs,
                                        info.flags
                                    )
                                )
                            }
                        }
                    }
                } finally {
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun startMuxerIfReady() {
        if (muxerStarted || videoTrack < 0) return
        if (audioExpected && audioTrack < 0) return

        muxer!!.start()
        muxerStarted = true

        pendingSamples.sortBy { it.presentationTimeUs }
        pendingSamples.forEach { sample ->
            writeSampleNow(sample)
        }
        pendingSamples.clear()
    }

    private fun writeOrQueueSample(sample: PendingSample) {
        if (muxerStarted) {
            writeSampleNow(sample)
        } else {
            // Preserve encoded frames until both required tracks are ready.
            pendingSamples.add(sample)
        }
    }

    private fun writeSampleNow(sample: PendingSample) {
        val targetMuxer = muxer ?: return
        val track = sample.track

        if (track < 0) return

        val buffer = java.nio.ByteBuffer.wrap(sample.bytes)
        val info = MediaCodec.BufferInfo().apply {
            set(
                0,
                sample.bytes.size,
                sample.presentationTimeUs,
                sample.flags
            )
        }

        targetMuxer.writeSampleData(track, buffer, info)
    }

    private fun pauseRecording() {
        if (!recording || paused) return

        try {
            if (micEnabled) {
                if (Build.VERSION.SDK_INT >= 24) {
                    micRecorder?.pause()
                } else {
                    sendStatus(true, false, "Pause is not supported on this Android version.")
                    return
                }
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
                if (Build.VERSION.SDK_INT >= 24) {
                    micRecorder?.resume()
                }
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

    private fun createOutput(name: String, useMuxer: Boolean) {
        pendingSamples.clear()
        videoTrack = -1
        audioTrack = -1
        muxerStarted = false

        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/GameCast"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Could not create video file.")

            if (useMuxer) {
                outputPfd = contentResolver.openFileDescriptor(
                    outputUri!!,
                    "w"
                )
                val pfd = outputPfd
                    ?: error("Could not open output file descriptor.")

                muxer = MediaMuxer(
                    pfd.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            }
        } else {
            val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: error("App movie directory is unavailable.")

            val folder = File(base, "GameCast")
            if (!folder.exists()) {
                check(folder.mkdirs()) {
                    "Could not create output directory."
                }
            }

            outputFile = File(folder, name)

            if (useMuxer) {
                muxer = MediaMuxer(
                    outputFile!!.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            }
        }
    }

    private fun stopRecording(save: Boolean, message: String? = null) {
        if (projection == null &&
            !recording &&
            micRecorder == null &&
            muxer == null &&
            outputUri == null
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        recording = false
        running.set(false)
        audioRunning.set(false)

        try { audioRecord?.stop() } catch (_: Exception) {}

        try { audioThread?.join(1500) } catch (_: Exception) {}
        try { videoThread?.join(1500) } catch (_: Exception) {}

        audioThread = null
        videoThread = null

        var saved = save && !startFailed
        val wasMicRecording = micRecorder != null
        val wasMuxerRecording = muxer != null

        try { display?.release() } catch (_: Exception) {}
        display = null

        if (wasMicRecording) {
            try {
                micRecorder?.stop()
            } catch (_: Exception) {
                saved = false
            }
        }

        try { micRecorder?.reset() } catch (_: Exception) {}
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

        if (wasMuxerRecording) {
            try {
                synchronized(muxLock) {
                    if (muxerStarted) {
                        muxer?.stop()
                    } else {
                        saved = false
                    }
                    muxer?.release()
                }
            } catch (_: Exception) {
                saved = false
                try { muxer?.release() } catch (_: Exception) {}
            }
        }

        muxer = null
        muxerStarted = false
        pendingSamples.clear()

        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        projection = null

        try {
            outputPfd?.close()
        } catch (_: Exception) {}
        outputPfd = null

        if (Build.VERSION.SDK_INT >= 29) {
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
                    } catch (_: Exception) {}
                }
            }
        } else if (!saved) {
            outputFile?.delete()
        }

        outputUri = null
        outputFile = null
        paused = false
        internalAudio = false
        audioExpected = false
        micEnabled = false
        startFailed = false
        videoTrack = -1
        audioTrack = -1

        sendStatus(
            false,
            false,
            message ?: if (saved) {
                "Recording saved to Movies/GameCast"
            } else {
                "Recording could not be saved."
            }
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

    private fun sendStatus(
        isRecording: Boolean,
        isPaused: Boolean,
        message: String
    ) {
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName).apply {
                putExtra(EXTRA_RECORDING, isRecording)
                putExtra(EXTRA_PAUSED, isPaused)
                putExtra(EXTRA_MESSAGE, message)
            }
        )
    }

    private fun notification(message: String): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).apply {
            action = ACTION_STOP
        }

        val pending = PendingIntent.getService(
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
                pending
            )
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        if (projection != null ||
            micRecorder != null ||
            muxer != null ||
            outputUri != null
        ) {
            stopRecording(false, "Recording service closed")
        }
        super.onDestroy()
    }
}
