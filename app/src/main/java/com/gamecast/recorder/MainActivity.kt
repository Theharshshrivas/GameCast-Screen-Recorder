
package com.gamecast.recorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.app.RecoverableSecurityException
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

private data class RecordingItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long
)

private data class QualityOption(
    val label: String,
    val longEdge: Int
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

    private lateinit var screenCaptureLauncher:
        ActivityResultLauncher<Intent>

    private lateinit var microphonePermissionLauncher:
        ActivityResultLauncher<String>

    private lateinit var mediaChangeLauncher:
        ActivityResultLauncher<IntentSenderRequest>

    private var micEnabled by mutableStateOf(true)
    private var isRecording by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Ready to record your screen")
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
                        "Mic permission denied. Turn mic off for video-only recording."
                }
            } else if (!granted) {
                micEnabled = false
                statusMessage = "Microphone unavailable. Video-only mode selected."
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
                    putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(RecordingService.EXTRA_PROJECTION_DATA, result.data)
                    putExtra(RecordingService.EXTRA_MIC_ENABLED, micEnabled)
                    putExtra(RecordingService.EXTRA_QUALITY_LONG_EDGE, quality.longEdge)
                    putExtra(RecordingService.EXTRA_QUALITY_LABEL, quality.label)
                }

                ContextCompat.startForegroundService(this, serviceIntent)
                statusMessage = "Starting ${quality.label} recording..."
            } else {
                statusMessage = "Screen recording permission was cancelled"
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
                statusMessage = "Media change was cancelled"
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
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showGallery) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "My Recordings",
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showGallery = false }) {
                                    Text("Back")
                                }
                            }

                            Text("${recordings.size} video(s)")
                            Spacer(Modifier.height(8.dp))

                            Button(
                                onClick = { loadRecordings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Refresh")
                            }

                            if (recordings.isEmpty()) {
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "No videos found in Movies/GameCast. Record a video and refresh."
                                )
                            } else {
                                LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(
                                        recordings,
                                        key = { it.uri.toString() }
                                    ) { item ->
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(14.dp)) {
                                                Text(
                                                    item.name,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    "${formatSize(item.size)} • ${formatDate(item.dateAdded)}",
                                                    style = MaterialTheme.typography.bodySmall
                                                )

                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    OutlinedButton(
                                                        onClick = { playRecording(item) }
                                                    ) { Text("Play") }

                                                    OutlinedButton(
                                                        onClick = { shareRecording(item) }
                                                    ) { Text("Share") }
                                                }

                                                Row {
                                                    TextButton(onClick = {
                                                        renameTarget = item
                                                        renameValue =
                                                            item.name.substringBeforeLast(".mp4")
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
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            item {
                                Text(
                                    "GAMECAST",
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Screen Recorder",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text("Clean Capture.")
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    statusMessage,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(18.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            "Microphone audio",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            if (micEnabled) "Voice capture enabled"
                                            else "Video only",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }

                                    Switch(
                                        checked = micEnabled,
                                        enabled = !isRecording,
                                        onCheckedChange = { enabled ->
                                            if (!enabled) {
                                                micEnabled = false
                                            } else {
                                                val granted =
                                                    ContextCompat.checkSelfPermission(
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

                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "Recording quality",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Maximum long edge; actual output depends on your display and encoder.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(8.dp))

                                QUALITY_OPTIONS.chunked(3).forEach { group ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        group.forEach { option ->
                                            if (selectedQuality == option.label) {
                                                Button(
                                                    onClick = {
                                                        if (!isRecording) {
                                                            selectedQuality = option.label
                                                        }
                                                    },
                                                    enabled = !isRecording,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(option.label)
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        if (!isRecording) {
                                                            selectedQuality = option.label
                                                        }
                                                    },
                                                    enabled = !isRecording,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text(option.label)
                                                }
                                            }
                                        }
                                        repeat(3 - group.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }

                                Spacer(Modifier.height(20.dp))

                                if (!isRecording) {
                                    Button(
                                        onClick = { requestScreenCapture() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Start Recording")
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            sendRecordingAction(
                                                if (isPaused) RecordingService.ACTION_RESUME
                                                else RecordingService.ACTION_PAUSE
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            if (isPaused) "Resume Recording"
                                            else "Pause Recording"
                                        )
                                    }

                                    Spacer(Modifier.height(10.dp))

                                    OutlinedButton(
                                        onClick = {
                                            sendRecordingAction(RecordingService.ACTION_STOP)
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Stop Recording")
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                OutlinedButton(
                                    onClick = {
                                        showGallery = true
                                        loadRecordings()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("My Recordings")
                                }

                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "Phase 4 • Quality Settings",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(24.dp))
                            }
                        }
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
                                }) { Text("Save") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    renameTarget = null
                                }) { Text("Cancel") }
                            }
                        )
                    }

                    deleteTarget?.let { item ->
                        AlertDialog(
                            onDismissRequest = { deleteTarget = null },
                            title = { Text("Delete recording?") },
                            text = {
                                Text("This removes ${item.name} from your recordings.")
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    deleteRecording(item, true)
                                    deleteTarget = null
                                }) { Text("Delete") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    deleteTarget = null
                                }) { Text("Cancel") }
                            }
                        )
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
        val result = mutableListOf<RecordingItem>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            statusMessage =
                "Gallery listing currently requires Android 10 or newer."
            recordings = emptyList()
            return
        }

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
                    if (path.contains("Movies/GameCast", ignoreCase = true)) {
                        val id = cursor.getLong(idIndex)
                        result.add(
                            RecordingItem(
                                Uri.withAppendedPath(collection, id.toString()),
                                cursor.getString(nameIndex) ?: "Recording.mp4",
                                cursor.getLong(sizeIndex),
                                cursor.getLong(dateIndex)
                            )
                        )
                    }
                }
            }
            recordings = result
        } catch (e: Exception) {
            statusMessage =
                "Could not load recordings: ${e.message ?: "Unknown error"}"
        }
    }

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                requestUserConsent
            ) {
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                requestUserConsent
            ) {
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
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun formatDate(seconds: Long): String =
        DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
        ).format(Date(seconds * 1000))

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
