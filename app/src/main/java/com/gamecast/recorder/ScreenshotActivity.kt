
package com.gamecast.recorder

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

private data class ScreenshotItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val dateAdded: Long
)

class ScreenshotActivity : ComponentActivity() {

    private var status by mutableStateOf("Ready to capture a screenshot")
    private var screenshots by mutableStateOf<List<ScreenshotItem>>(emptyList())
    private var busy by mutableStateOf(false)

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            busy = true
            status = "Capturing screenshot..."

            val intent = Intent(this, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_CAPTURE
                putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotService.EXTRA_PROJECTION_DATA, result.data)
            }

            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                busy = false
                status = "Could not start capture: ${e.message ?: "Unknown error"}"
            }
        } else {
            status = "Screenshot permission cancelled"
        }
    }

    private val resultReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenshotService.ACTION_RESULT) return
            busy = false
            status = intent.getStringExtra(ScreenshotService.EXTRA_MESSAGE)
                ?: "Capture finished"
            loadScreenshots()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.registerReceiver(
            this,
            resultReceiver,
            android.content.IntentFilter(ScreenshotService.ACTION_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadScreenshots()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp)
                    ) {
                        Text(
                            "GameCast Screenshots",
                            fontSize = 25.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(status)
                        Spacer(Modifier.height(14.dp))

                        Button(
                            onClick = {
                                if (!busy) {
                                    val manager = getSystemService(
                                        Context.MEDIA_PROJECTION_SERVICE
                                    ) as MediaProjectionManager

                                    captureLauncher.launch(
                                        manager.createScreenCaptureIntent()
                                    )
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (busy) "Capturing..." else "Take Screenshot")
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { loadScreenshots() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Refresh Gallery")
                        }

                        Spacer(Modifier.height(12.dp))
                        Text("${screenshots.size} screenshot(s)")

                        if (screenshots.isEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("No screenshots found yet.")
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(screenshots, key = { it.uri.toString() }) { item ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(item.name)
                                            Text(
                                                "${formatSize(item.size)} • ${
                                                    DateFormat.getDateTimeInstance(
                                                        DateFormat.MEDIUM,
                                                        DateFormat.SHORT
                                                    ).format(Date(item.dateAdded * 1000))
                                                }"
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                TextButton(onClick = {
                                                    shareScreenshot(item)
                                                }) {
                                                    Text("Share")
                                                }
                                                TextButton(onClick = {
                                                    deleteScreenshot(item)
                                                }) {
                                                    Text("Delete")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadScreenshots() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            screenshots = emptyList()
            status = "Gallery listing requires Android 10 or newer."
            return
        }

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val result = mutableListOf<ScreenshotItem>()
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        try {
            contentResolver.query(
                collection,
                columns,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathCol).orEmpty()
                    if (path.contains("Pictures/GameCast", ignoreCase = true)) {
                        val id = cursor.getLong(idCol)
                        result.add(
                            ScreenshotItem(
                                Uri.withAppendedPath(collection, id.toString()),
                                cursor.getString(nameCol) ?: "Screenshot.png",
                                cursor.getLong(sizeCol),
                                cursor.getLong(dateCol)
                            )
                        )
                    }
                }
            }
            screenshots = result
        } catch (e: Exception) {
            status = "Gallery error: ${e.message ?: "Unknown error"}"
        }
    }

    private fun shareScreenshot(item: ScreenshotItem) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, item.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share screenshot"))
        } catch (e: Exception) {
            status = "Share failed: ${e.message ?: "Unknown error"}"
        }
    }

    private fun deleteScreenshot(item: ScreenshotItem) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val request = MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(item.uri)
                )
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(
                        request.intentSender
                    ).build()
                )
            } else {
                contentResolver.delete(item.uri, null, null)
                status = "Screenshot deleted"
                loadScreenshots()
            }
        } catch (e: Exception) {
            status = "Delete failed: ${e.message ?: "Unknown error"}"
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        status = if (result.resultCode == Activity.RESULT_OK) {
            "Screenshot deleted"
        } else {
            "Delete cancelled"
        }
        loadScreenshots()
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    override fun onResume() {
        super.onResume()
        loadScreenshots()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(resultReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
