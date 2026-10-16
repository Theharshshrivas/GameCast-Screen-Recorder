
package com.gamecast.recorder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class StorageDashboardActivity : ComponentActivity() {

    private var videoCount by mutableStateOf(0)
    private var screenshotCount by mutableStateOf(0)
    private var videoBytes by mutableStateOf(0L)
    private var screenshotBytes by mutableStateOf(0L)
    private var status by mutableStateOf("Storage information")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshStorage()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("GameCast Tools", fontSize = 27.sp)

                        Text(
                            "Storage overview and quick access",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Recorded Videos",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("Videos found: $videoCount")
                                Text("Total size: ${formatBytes(videoBytes)}")
                                Text("Folder: Movies/GameCast")
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Screenshots",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("Images found: $screenshotCount")
                                Text("Total size: ${formatBytes(screenshotBytes)}")
                                Text("Folder: Pictures/GameCast")
                            }
                        }

                        Text(status)

                        Button(
                            onClick = { refreshStorage() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Refresh Storage Information")
                        }

                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@StorageDashboardActivity,
                                        MainActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Screen Recorder")
                        }

                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@StorageDashboardActivity,
                                        ScreenshotActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Screenshot Tools")
                        }

                        OutlinedButton(
                            onClick = {
                                startActivity(
                                    Intent(
                                        this@StorageDashboardActivity,
                                        FloatingControlsActivity::class.java
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Open Floating Controls")
                        }

                        Spacer(Modifier.height(4.dp))

                        Text(
                            "These totals cover media visible to GameCast, " +
                                "not all free space on your phone.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    private fun refreshStorage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            videoCount = 0
            screenshotCount = 0
            videoBytes = 0L
            screenshotBytes = 0L
            status = "Folder statistics require Android 10 or newer. " +
                "Other GameCast tools remain available."
            return
        }

        val videos = queryMedia(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RELATIVE_PATH,
            "Movies/GameCast/"
        )

        val images = queryMedia(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH,
            "Pictures/GameCast/"
        )

        videoCount = videos.first
        videoBytes = videos.second
        screenshotCount = images.first
        screenshotBytes = images.second
        status = "Storage information refreshed."
    }

    private fun queryMedia(
        collection: android.net.Uri,
        sizeColumn: String,
        pathColumn: String,
        expectedPath: String
    ): Pair<Int, Long> {
        var count = 0
        var bytes = 0L

        try {
            contentResolver.query(
                collection,
                arrayOf(sizeColumn, pathColumn),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(sizeColumn)
                val pathIndex = cursor.getColumnIndex(pathColumn)

                if (sizeIndex < 0 || pathIndex < 0) {
                    return 0 to 0L
                }

                while (cursor.moveToNext()) {
                    val path = cursor.getString(pathIndex).orEmpty()

                    if (path.startsWith(expectedPath, ignoreCase = true)) {
                        count++
                        if (!cursor.isNull(sizeIndex)) {
                            bytes += cursor.getLong(sizeIndex).coerceAtLeast(0L)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            status = "Some storage information could not be read."
        }

        return count to bytes
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"

        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = -1

        while (value >= 1024.0 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }

        return String.format(Locale.US, "%.2f %s", value, units[index])
    }
}
