package com.gamecast.recorder

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private data class TrashEntry(
    val uri: Uri,
    val name: String,
    val type: String
)

class RecycleBinActivity : ComponentActivity() {

    private var entries by mutableStateOf<List<TrashEntry>>(emptyList())
    private var status by mutableStateOf("Loading Recycle Bin...")
    private var pendingUri by mutableStateOf<Uri?>(null)
    private var pendingAction by mutableStateOf("")

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            status = when (pendingAction) {
                "restore" -> "Media restored."
                "delete" -> "Media permanently deleted."
                else -> "Recycle Bin updated."
            }
        } else {
            status = "Action cancelled."
        }

        pendingUri = null
        pendingAction = ""
        loadTrash()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Recycle Bin",
                            fontSize = 26.sp
                        )

                        Text(
                            text = if (Build.VERSION.SDK_INT >= 30) {
                                "Restore deleted videos and screenshots, or permanently delete them."
                            } else {
                                "Native Recycle Bin requires Android 11 or newer."
                            },
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                        )

                        Text(
                            text = status,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        OutlinedButton(
                            onClick = { finish() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Back")
                        }

                        if (Build.VERSION.SDK_INT >= 30) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(
                                    items = entries,
                                    key = { it.uri.toString() }
                                ) { entry ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(14.dp)
                                        ) {
                                            Text(entry.name)
                                            Text(
                                                text = entry.type,
                                                modifier = Modifier.padding(
                                                    top = 4.dp,
                                                    bottom = 10.dp
                                                )
                                            )

                                            Row(
                                                horizontalArrangement =
                                                    Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        restoreItem(entry)
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Restore")
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        permanentlyDelete(entry)
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("Delete forever")
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

        loadTrash()
    }

    private fun loadTrash() {
        if (Build.VERSION.SDK_INT < 30) {
            entries = emptyList()
            status = "Recycle Bin is supported on Android 11 and newer."
            return
        }

        val found = mutableListOf<TrashEntry>()

        try {
            val collections = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Video",
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Screenshot/Image"
            )

            for ((collection, type) in collections) {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME
                )

                val selection = "${MediaStore.MediaColumns.IS_TRASHED} = ?"
                val selectionArgs = arrayOf("1")

                contentResolver.query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns._ID
                    )
                    val nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DISPLAY_NAME
                    )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: "Unnamed media"
                        val uri = ContentUris.withAppendedId(collection, id)

                        found.add(
                            TrashEntry(
                                uri = uri,
                                name = name,
                                type = type
                            )
                        )
                    }
                }
            }

            entries = found
            status = if (found.isEmpty()) {
                "Recycle Bin is empty."
            } else {
                "${found.size} item(s) in Recycle Bin."
            }
        } catch (e: Exception) {
            entries = emptyList()
            status = "Could not load Recycle Bin: ${e.message ?: "Unknown error"}"
        }
    }

    private fun restoreItem(entry: TrashEntry) {
        if (Build.VERSION.SDK_INT < 30) return

        try {
            pendingUri = entry.uri
            pendingAction = "restore"

            val request = MediaStore.createTrashRequest(
                contentResolver,
                listOf(entry.uri),
                false
            )

            consentLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        } catch (e: Exception) {
            status = "Restore failed: ${e.message ?: "Unknown error"}"
        }
    }

    private fun permanentlyDelete(entry: TrashEntry) {
        if (Build.VERSION.SDK_INT < 30) return

        try {
            pendingUri = entry.uri
            pendingAction = "delete"

            val request = MediaStore.createDeleteRequest(
                contentResolver,
                listOf(entry.uri)
            )

            consentLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        } catch (e: Exception) {
            status = "Delete failed: ${e.message ?: "Unknown error"}"
        }
    }
}
