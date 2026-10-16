
package com.gamecast.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DarkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private data class RecordingItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val thumbnail: Bitmap?
)

private data class QualityOption(
    val label: String,
    val longEdge: Int
)

private val GameCastBackground = Color(0xFF0B1020)
private val GameCastSurface = Color(0xFF151D31)
private val GameCastAccent = Color(0xFF7C5CFF)
private val GameCastText = Color(0xFFF5F7FF)
private val GameCastMuted = Color(0xFF9BA8C4)

private val GameCastColors: DarkColorScheme = darkColorScheme(
    primary = GameCastAccent,
    onPrimary = Color.White,
    secondary = Color(0xFF26D9C1),
    background = GameCastBackground,
    onBackground = GameCastText,
    surface = GameCastSurface,
    onSurface = GameCastText,
    surfaceVariant = Color(0xFF222D45),
    onSurfaceVariant = GameCastMuted
)

class MainActivity : ComponentActivity() {

    companion object {
        private val QUALITY_OPTIONS = listOf(
            QualityOption("480p", 854),
            QualityOption("540p", 960),
            QualityOption("720p", 1280),
            QualityOption("1080p", 1920),
            QualityOption("2K", 2560)
        )
    }

    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var microphonePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var mediaChangeLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var micEnabled by mutableStateOf(true)
    private var isRecording by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready for your next capture")
    private var selectedQuality by mutableStateOf("1080p")
    private var showGallery by mutableStateOf(false)
    private var recordings by mutableStateOf<List<RecordingItem>>(emptyList())
    private var renameTarget by mutableStateOf<RecordingItem?>(null)
    private var renameValue by mutableStateOf("")
    private var deleteTarget by mutableStateOf<RecordingItem?>(null)

    private var waitingForMicPermission = false
    private var pendingRename: RecordingItem? = null
    private var pendingRenameValue: String? = null
    private var pendingDelete: RecordingItem? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordingService.ACTION_STATUS) return

            isRecording = intent.getBooleanExtra(
                RecordingService.EXTRA_RECORDING, false
            )
            isPaused = intent.getBooleanExtra(
                RecordingService.EXTRA_PAUSED, false
            )
            statusMessage = intent.getStringExtra(
                RecordingService.EXTRA_MESSAGE
            ) ?: "Ready"

            if (!isRecording && showGallery) loadRecordings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        microphonePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (waitingForMicPermission) {
                waitingForMicPermission = false
                if (granted) {
                    launchScreenCapture()
                } else {
                    statusMessage =
                        "Microphone permission denied. You can record without mic audio."
                }
            } else if (!granted) {
                micEnabled = false
                statusMessage = "Microphone disabled"
            }
        }

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val quality = QUALITY_OPTIONS.first {
                    it.label == selectedQuality
                }

                val serviceIntent = Intent(
                    this, RecordingService::class.java
                ).apply {
                    action = RecordingService.ACTION_START
                    putExtra(
                        RecordingService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )
                    putExtra(
                        RecordingService.EXTRA_PROJECTION_DATA,
                        result.data
                    )
                    putExtra(
                        RecordingService.EXTRA_MIC_ENABLED,
                        micEnabled
                    )
                    putExtra(
                        RecordingService.EXTRA_QUALITY_LONG_EDGE,
                        quality.longEdge
                    )
                    putExtra(
                        RecordingService.EXTRA_QUALITY_LABEL,
                        quality.label
                    )
                }

                ContextCompat.startForegroundService(this, serviceIntent)
                statusMessage = "Starting ${quality.label} recording..."
            } else {
                statusMessage = "Screen recording permission cancelled"
            }
        }

        mediaChangeLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingRename?.let { item ->
                    pendingRenameValue?.let { newName ->
                        renameRecording(item, newName, false)
                    }
                }
                pendingDelete?.let { item ->
                    deleteRecording(item, false)
                }
            } else {
                statusMessage = "Media change cancelled"
            }

            pendingRename = null
            pendingRenameValue = null
            pendingDelete = null
            loadRecordings()
        }

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(RecordingService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme(colorScheme = GameCastColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showGallery) {
                        GalleryScreen()
                    } else {
                        HomeScreen()
                    }

                    renameTarget?.let { item ->
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            title = { Text("Rename recording") },
                            text = {
                                OutlinedTextField(
                                    value = renameValue,
                                    onValueChange = { renameValue = it },
                                    label = { Text("Video name") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val newName = renameValue.trim()
                                    if (newName.isNotBlank()) {
                                        renameRecording(item, newName, true)
                                    }
                                    renameTarget = null
                                }) {
                                    Text("Save")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    renameTarget = null
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    deleteTarget?.let { item ->
                        AlertDialog(
                            onDismissRequest = { deleteTarget = null },
                            title = { Text("Delete recording?") },
                            text = {
                                Text("Remove ${item.name} from your recordings?")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    deleteRecording(item, true)
                                    deleteTarget = null
                                }) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    deleteTarget = null
                                }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun HomeScreen() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(GameCastAccent, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("GC", fontWeight = FontWeight.Black, fontSize = 20.sp)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            "GAMECAST",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            "SCREEN RECORDER",
                            fontSize = 11.sp,
                            color = GameCastMuted,
                            letterSpacing = 1.2.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (isRecording) Color(0xFF48222F)
                                else Color(0xFF1E3A36),
                                CircleShape
                            )
                            .padding(horizontal = 11.dp, vertical = 7.dp)
                    ) {
                        Text(
                            if (isRecording) "● LIVE" else "● READY",
                            color = if (isRecording) Color(0xFFFF7187)
                            else Color(0xFF55E5C4),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = GameCastSurface
                    )
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            if (isRecording) {
                                if (isPaused) "RECORDING PAUSED" else "YOU'RE RECORDING"
                            } else {
                                "READY WHEN YOU ARE"
                            },
                            color = GameCastColors.secondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            if (isRecording) {
                                if (isPaused) "Take a break." else "Stay in the game."
                            } else {
                                "Capture every moment."
                            },
                            fontSize = 25.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            statusMessage,
                            color = GameCastMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(Modifier.height(18.dp))

                        if (!isRecording) {
                            Button(
                                onClick = { requestScreenCapture() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GameCastAccent
                                )
                            ) {
                                Text(
                                    "●   Start Recording",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    sendRecordingAction(
                                        if (isPaused) RecordingService.ACTION_RESUME
                                        else RecordingService.ACTION_PAUSE
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    if (isPaused) "▶  Resume Recording"
                                    else "Ⅱ  Pause Recording"
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            OutlinedButton(
                                onClick = {
                                    sendRecordingAction(RecordingService.ACTION_STOP)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("■  Stop and Save")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "QUICK ACTIONS",
                    fontWeight = FontWeight.Bold,
                    color = GameCastMuted,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            startActivity(
                                Intent(this@MainActivity, ScreenshotActivity::class.java)
                            )
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = GameCastSurface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("▣", fontSize = 28.sp, color = GameCastColors.secondary)
                            Spacer(Modifier.height(8.dp))
                            Text("Screenshot", fontWeight = FontWeight.Bold)
                            Text(
                                "Capture a frame",
                                color = GameCastMuted,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            showGallery = true
                            loadRecordings()
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = GameCastSurface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text("▶", fontSize = 28.sp, color = GameCastAccent)
                            Spacer(Modifier.height(8.dp))
                            Text("My Recordings", fontWeight = FontWeight.Bold)
                            Text(
                                "${recordings.size} loaded video(s)",
                                color = GameCastMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = GameCastSurface
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("CAPTURE SETTINGS", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Microphone audio", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (micEnabled) "Voice recording enabled"
                                    else "Microphone disabled",
                                    color = GameCastMuted,
                                    fontSize = 12.sp
                                )
                            }

                            Switch(
                                checked = micEnabled,
                                enabled = !isRecording,
                                onCheckedChange = { enabled ->
                                    if (!enabled) {
                                        micEnabled = false
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED

                                        if (granted) {
                                            micEnabled = true
                                        } else {
                                            microphonePermissionLauncher.launch(
                                                Manifest.permission.RECORD_AUDIO
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        Spacer(Modifier.height(18.dp))
                        Text("Video quality", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Maximum long edge. Output may be lower than the selected value.",
                            color = GameCastMuted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(12.dp))

                        QUALITY_OPTIONS.chunked(3).forEach { group ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                group.forEach { option ->
                                    val selected = selectedQuality == option.label
                                    if (selected) {
                                        Button(
                                            onClick = { if (!isRecording) selectedQuality = option.label },
                                            enabled = !isRecording,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                horizontal = 4.dp
                                            )
                                        ) {
                                            Text(option.label, fontSize = 12.sp)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { if (!isRecording) selectedQuality = option.label },
                                            enabled = !isRecording,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                horizontal = 4.dp
                                            )
                                        ) {
                                            Text(option.label, fontSize = 12.sp)
                                        }
                                    }
                                }
                                repeat(3 - group.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun GalleryScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("MY LIBRARY", color = GameCastColors.secondary, fontSize = 11.sp)
                    Text("Recordings", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${recordings.size} video(s)", color = GameCastMuted)
                }

                TextButton(onClick = { showGallery = false }) {
                    Text("Back")
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { loadRecordings() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("↻  Refresh library")
            }

            Spacer(Modifier.height(10.dp))

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▶", fontSize = 42.sp, color = GameCastAccent)
                        Spacer(Modifier.height(12.dp))
                        Text("No recordings yet", fontWeight = FontWeight.Bold)
                        Text(
                            "Record a video, then refresh this library.",
                            color = GameCastMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recordings, key = { it.uri.toString() }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = GameCastSurface
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .background(Color(0xFF080D18), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.thumbnail != null) {
                                        Image(
                                            bitmap = item.thumbnail.asImageBitmap(),
                                            contentDescription = "Thumbnail for ${item.name}",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text("▶", fontSize = 42.sp, color = GameCastAccent)
                                    }

                                    if (item.durationMs > 0) {
                                        Text(
                                            formatDuration(item.durationMs),
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp)
                                                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 7.dp, vertical = 4.dp),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${item.width} × ${item.height}  •  ${formatSize(item.size)}",
                                    color = GameCastColors.secondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    formatDate(item.dateAdded),
                                    color = GameCastMuted,
                                    fontSize = 11.sp
                                )

                                Spacer(Modifier.height(8.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = { playRecording(item) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Play") }

                                    OutlinedButton(
                                        onClick = { shareRecording(item) },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Share") }
                                }

                                Row {
                                    TextButton(onClick = {
                                        renameTarget = item
                                        renameValue = item.name.substringBeforeLast(".mp4")
                                    }) { Text("Rename") }

                                    TextButton(onClick = {
                                        deleteTarget = item
                                    }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        if (micEnabled) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                waitingForMicPermission = true
                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        statusMessage = "Waiting for screen capture permission..."
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun sendRecordingAction(action: String) {
        startService(
            Intent(this, RecordingService::class.java).apply {
                this.action = action
            }
        )
    }

    private fun loadRecordings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusMessage = "Gallery listing requires Android 10 or newer."
            recordings = emptyList()
            return
        }

        val result = mutableListOf<RecordingItem>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        try {
            contentResolver.query(
                collection,
                columns,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathIndex).orEmpty()
                    if (!path.contains("Movies/GameCast", ignoreCase = true)) continue

                    val id = cursor.getLong(idIndex)
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    val metadata = readVideoMetadata(uri)

                    result.add(
                        RecordingItem(
                            uri = uri,
                            name = cursor.getString(nameIndex) ?: "Recording.mp4",
                            size = cursor.getLong(sizeIndex),
                            dateAdded = cursor.getLong(dateIndex),
                            durationMs = metadata.first,
                            width = metadata.second,
                            height = metadata.third,
                            thumbnail = metadata.fourth
                        )
                    )
                }
            }

            recordings = result
        } catch (e: Exception) {
            statusMessage = "Could not load recordings: ${e.message ?: "Unknown error"}"
        }
    }

    private fun readVideoMetadata(
        uri: Uri
    ): MetadataResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)

            val duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0

            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0

            val bitmap = try {
                retriever.getFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } catch (_: Exception) {
                null
            }

            MetadataResult(duration, width, height, bitmap)
        } catch (_: Exception) {
            MetadataResult(0L, 0, 0, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private data class MetadataResult(
        val duration: Long,
        val width: Int,
        val height: Int,
        val thumbnail: Bitmap?
    )

    private fun playRecording(item: RecordingItem) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(item.uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play recording"))
        } catch (_: Exception) {
            statusMessage = "No compatible video player was found."
        }
    }

    private fun shareRecording(item: RecordingItem) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share recording"))
        } catch (_: Exception) {
            statusMessage = "Could not share this recording."
        }
    }

    private fun renameRecording(
        item: RecordingItem,
        requestedName: String,
        requestUserConsent: Boolean
    ) {
        val baseName = requestedName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .removeSuffix(".mp4")

        if (baseName.isBlank()) {
            statusMessage = "Enter a valid video name."
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestUserConsent) {
                pendingRename = item
                pendingRenameValue = baseName
                val request = MediaStore.createWriteRequest(
                    contentResolver, listOf(item.uri)
                )
                mediaChangeLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
                return
            }

            val changed = contentResolver.update(
                item.uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
                },
                null,
                null
            )

            statusMessage = if (changed > 0) "Recording renamed."
            else "Rename was not completed."
            loadRecordings()
        } catch (e: Exception) {
            statusMessage = "Rename failed: ${e.message ?: "Unknown error"}"
        }
    }

    private fun deleteRecording(
        item: RecordingItem,
        requestUserConsent: Boolean
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && requestUserConsent) {
                pendingDelete = item
                val request = MediaStore.createDeleteRequest(
                    contentResolver, listOf(item.uri)
                )
                mediaChangeLauncher.launch(
                    IntentSenderRequest.Builder(request.intentSender).build()
                )
                return
            }

            val deleted = contentResolver.delete(item.uri, null, null)
            statusMessage = if (deleted > 0) "Recording deleted."
            else "Recording was not deleted."
            loadRecordings()
        } catch (e: Exception) {
            statusMessage = "Delete failed: ${e.message ?: "Unknown error"}"
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(
            Locale.US,
            bytes / (1024.0 * 1024.0 * 1024.0)
        )
    }

    private fun formatDate(seconds: Long): String =
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(seconds * 1000))

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onResume() {
        super.onResume()
        if (showGallery) loadRecordings()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }
}
