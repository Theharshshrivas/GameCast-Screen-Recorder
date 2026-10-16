
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val PageBg = Color(0xFFF4F6FC)
private val CardBg = Color(0xFFFFFFFF)
private val Ink = Color(0xFF17213A)
private val Muted = Color(0xFF68748C)
private val Blue = Color(0xFF465CFF)
private val Purple = Color(0xFF7047EB)
private val Teal = Color(0xFF078A78)
private val Red = Color(0xFFD93652)
private val Border = Color(0xFFE2E7F2)
private val SoftBlue = Color(0xFFEBEEFF)
private val SoftGreen = Color(0xFFE3F7F0)
private val SoftRed = Color(0xFFFFE9ED)

private val GameCastColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Purple,
    onSecondary = Color.White,
    background = PageBg,
    onBackground = Ink,
    surface = CardBg,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDF1F8),
    onSurfaceVariant = Muted,
    outline = Border
)

private data class QualityOption(
    val label: String,
    val longEdge: Int
)

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

private data class VideoMetadata(
    val duration: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val thumbnail: Bitmap? = null
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

    private lateinit var captureLauncher: ActivityResultLauncher<Intent>
    private lateinit var micLauncher: ActivityResultLauncher<String>
    private lateinit var mediaChangeLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var micEnabled by mutableStateOf(true)
    private var isRecording by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var statusMessage by mutableStateOf("Your next great moment starts here.")
    private var selectedQuality by mutableStateOf("1080p")
    private var showGallery by mutableStateOf(false)
    private var recordings by mutableStateOf<List<RecordingItem>>(emptyList())
    private var renameTarget by mutableStateOf<RecordingItem?>(null)
    private var renameValue by mutableStateOf("")
    private var deleteTarget by mutableStateOf<RecordingItem?>(null)

    private var waitingForMic = false
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

        micLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (waitingForMic) {
                waitingForMic = false
                if (granted) launchCapturePermission()
                else statusMessage = "Microphone denied. Disable mic to continue."
            } else if (!granted) {
                micEnabled = false
                statusMessage = "Microphone disabled."
            }
        }

        captureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val quality = QUALITY_OPTIONS.firstOrNull {
                    it.label == selectedQuality
                } ?: QUALITY_OPTIONS[3]

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
                statusMessage = "Starting ${quality.label} capture…"
            } else {
                statusMessage = "Screen capture cancelled."
            }
        }

        mediaChangeLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingRename?.let { item ->
                    pendingRenameValue?.let { name ->
                        renameRecording(item, name, false)
                    }
                }
                pendingDelete?.let { deleteRecording(it, false) }
            } else {
                statusMessage = "Media change cancelled."
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
                    color = PageBg
                ) {
                    if (showGallery) GalleryScreen() else HomeScreen()

                    renameTarget?.let { item ->
                        AlertDialog(
                            onDismissRequest = { renameTarget = null },
                            containerColor = CardBg,
                            title = { Text("Rename recording", color = Ink) },
                            text = {
                                OutlinedTextField(
                                    value = renameValue,
                                    onValueChange = { renameValue = it },
                                    label = { Text("Video name") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Ink,
                                        unfocusedTextColor = Ink
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    val name = renameValue.trim()
                                    if (name.isNotBlank()) {
                                        renameRecording(item, name, true)
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
                            containerColor = CardBg,
                            title = { Text("Delete recording?", color = Ink) },
                            text = {
                                Text(
                                    "Delete ${item.name} from your library?",
                                    color = Muted
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    deleteRecording(item, true)
                                    deleteTarget = null
                                }) {
                                    Text("Delete", color = Red)
                                }
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

    @Composable
    private fun HomeScreen() {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 18.dp,
                bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(Blue),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "GC",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 21.sp
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            "GameCast",
                            color = Ink,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            "SCREEN RECORDER",
                            color = Muted,
                            fontSize = 10.sp,
                            letterSpacing = 1.7.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        color = if (isRecording) SoftRed else SoftGreen,
                        shape = CircleShape
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 11.dp,
                                vertical = 8.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isRecording) Red else Teal)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (isRecording) "LIVE" else "READY",
                                color = if (isRecording) Red else Teal,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Surface(
                            color = SoftBlue,
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            Text(
                                if (!isRecording) "  CAPTURE STUDIO  "
                                else if (isPaused) "  RECORDING PAUSED  "
                                else "  RECORDING IN PROGRESS  ",
                                modifier = Modifier.padding(
                                    horizontal = 7.dp,
                                    vertical = 7.dp
                                ),
                                color = Blue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.7.sp
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            if (isRecording) {
                                if (isPaused) "Take your time." else "You're on air."
                            } else {
                                "Capture every\nmoment."
                            },
                            color = Ink,
                            fontSize = 31.sp,
                            lineHeight = 36.sp,
                            fontWeight = FontWeight.ExtraBold
                        )

                        Spacer(Modifier.height(9.dp))

                        Text(
                            statusMessage,
                            color = Muted,
                            fontSize = 14.sp,
                            lineHeight = 21.sp
                        )

                        Spacer(Modifier.height(22.dp))

                        if (!isRecording) {
                            Button(
                                onClick = { requestCapture() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(58.dp),
                                shape = RoundedCornerShape(17.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Blue,
                                    contentColor = Color.White
                                ),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 3.dp
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
                                    .height(54.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPaused) Teal else Purple
                                )
                            ) {
                                Text(
                                    if (isPaused) "▶   Resume Recording"
                                    else "Ⅱ   Pause Recording",
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = {
                                    sendRecordingAction(RecordingService.ACTION_STOP)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Red
                                )
                            ) {
                                Text("■   Stop and Save", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            item {
                SectionHeading("QUICK ACTIONS", "Your tools, one tap away")
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        icon = "▣",
                        title = "Screenshots",
                        subtitle = "Capture your screen",
                        tint = Purple,
                        background = Color(0xFFF0EAFF),
                        onClick = {
                            startActivity(Intent(this@MainActivity, ScreenshotActivity::class.java))
                        }
                    )
                    ActionCard(
                        modifier = Modifier.weight(1f),
                        icon = "▶",
                        title = "My Library",
                        subtitle = "Videos and captures",
                        tint = Blue,
                        background = SoftBlue,
                        onClick = {
                            showGallery = true
                            loadRecordings()
                        }
                    )
                }
            }

            item {
                SectionHeading("RECORDING SETTINGS", "Make every frame count")
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(23.dp),
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Microphone audio",
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Include your voice in recordings",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = micEnabled,
                                onCheckedChange = { enabled ->
                                    if (isRecording) return@Switch
                                    if (!enabled) {
                                        micEnabled = false
                                    } else if (
                                        ContextCompat.checkSelfPermission(
                                            this@MainActivity,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        micEnabled = true
                                    } else {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                enabled = !isRecording
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 16.dp),
                            color = Border
                        )

                        Text(
                            "Video quality",
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Choose the maximum long edge. Actual output depends on your screen.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(14.dp))

                        QUALITY_OPTIONS.chunked(3).forEach { group ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                group.forEach { option ->
                                    val selected = selectedQuality == option.label

                                    if (selected) {
                                        Button(
                                            onClick = {
                                                if (!isRecording) selectedQuality = option.label
                                            },
                                            enabled = !isRecording,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(
                                                horizontal = 3.dp,
                                                vertical = 9.dp
                                            ),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Blue
                                            )
                                        ) {
                                            Text(
                                                option.label,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                if (!isRecording) selectedQuality = option.label
                                            },
                                            enabled = !isRecording,
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(
                                                horizontal = 3.dp,
                                                vertical = 9.dp
                                            )
                                        ) {
                                            Text(
                                                option.label,
                                                fontSize = 12.sp,
                                                color = Ink
                                            )
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
            }

            item {
                Text(
                    "GameCast • Capture. Create. Share.",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        }
    }

    @Composable
    private fun SectionHeading(title: String, subtitle: String) {
        Column {
            Text(
                title,
                color = Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
    }

    @Composable
    private fun ActionCard(
        modifier: Modifier,
        icon: String,
        title: String,
        subtitle: String,
        tint: Color,
        background: Color,
        onClick: () -> Unit
    ) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(21.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(background),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        icon,
                        color = tint,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    title,
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }

    @Composable
    private fun GalleryScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 17.dp)
        ) {
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "YOUR CONTENT",
                        color = Blue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.3.sp
                    )
                    Text(
                        "My Library",
                        color = Ink,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text("${recordings.size} recording(s)", color = Muted, fontSize = 12.sp)
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
                Text("↻   Refresh library")
            }

            Spacer(Modifier.height(12.dp))

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(78.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(SoftBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("▶", color = Blue, fontSize = 32.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Your library is empty",
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Record something, then refresh.",
                            color = Muted,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(recordings, key = { it.uri.toString() }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(21.dp),
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFFE9EDF6)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    item.thumbnail?.let { bitmap ->
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } ?: Text("▶", color = Blue, fontSize = 38.sp)

                                    if (item.durationMs > 0) {
                                        Surface(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(8.dp),
                                            color = Color(0xD9000000),
                                            shape = RoundedCornerShape(7.dp)
                                        ) {
                                            Text(
                                                formatDuration(item.durationMs),
                                                modifier = Modifier.padding(
                                                    horizontal = 8.dp,
                                                    vertical = 5.dp
                                                ),
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(12.dp))
                                Text(
                                    item.name,
                                    color = Ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    "${item.width} × ${item.height}  •  ${formatSize(item.size)}",
                                    color = Blue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    formatDate(item.dateAdded),
                                    color = Muted,
                                    fontSize = 11.sp
                                )

                                Spacer(Modifier.height(11.dp))

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { playRecording(item) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text("▶  Play") }

                                    OutlinedButton(
                                        onClick = { shareRecording(item) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text("Share") }
                                }

                                Row {
                                    TextButton(onClick = {
                                        renameTarget = item
                                        renameValue = item.name.substringBeforeLast(".mp4")
                                    }) { Text("Rename") }

                                    TextButton(onClick = {
                                        deleteTarget = item
                                    }) { Text("Delete", color = Red) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestCapture() {
        if (micEnabled) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                waitingForMic = true
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }

        launchCapturePermission()
    }

    private fun launchCapturePermission() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        statusMessage = "Waiting for screen capture permission…"
        captureLauncher.launch(manager.createScreenCaptureIntent())
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
                    val metadata = readMetadata(uri)

                    result.add(
                        RecordingItem(
                            uri = uri,
                            name = cursor.getString(nameIndex) ?: "Recording.mp4",
                            size = cursor.getLong(sizeIndex),
                            dateAdded = cursor.getLong(dateIndex),
                            durationMs = metadata.duration,
                            width = metadata.width,
                            height = metadata.height,
                            thumbnail = metadata.thumbnail
                        )
                    )
                }
            }

            recordings = result
        } catch (e: Exception) {
            statusMessage = "Could not load library: ${e.message ?: "Unknown error"}"
        }
    }

    private fun readMetadata(uri: Uri): VideoMetadata {
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

            VideoMetadata(duration, width, height, bitmap)
        } catch (_: Exception) {
            VideoMetadata()
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
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
            statusMessage = "No compatible video player found."
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
                    contentResolver,
                    listOf(item.uri)
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
                    contentResolver,
                    listOf(item.uri)
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
        bytes < 1024L * 1024L ->
            "%.1f KB".format(Locale.US, bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L ->
            "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
        else ->
            "%.2f GB".format(
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
        val total = (milliseconds / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60

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
