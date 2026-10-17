package com.gamecast.recorder

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class RecycleBinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                RecycleBinScreen(
                    loadItems = { loadTrashedItems() },
                    requestRestore = { item ->
                        restoreItem(item)
                    },
                    requestPermanentDelete = { item ->
                        permanentlyDeleteItem(item)
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun loadTrashedItems(): List<RecycleBinItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return emptyList()
        }

        val result = mutableListOf<RecycleBinItem>()

        val collections = listOf(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Video",
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Screenshot"
        )

        for ((collectionUri, type) in collections) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )

            val selection =
                "${MediaStore.MediaColumns.IS_TRASHED} = ? AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR " +
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"

            val selectionArgs = arrayOf(
                "1",
                "%Movies/GameCast%",
                "%Pictures/GameCast%"
            )

            try {
                contentResolver.query(
                    collectionUri,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val pathColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: "Unnamed file"
                        val path = cursor.getString(pathColumn).orEmpty()
                        val uri = ContentUris.withAppendedId(collectionUri, id)

                        val actualType =
                            if (path.startsWith("Movies/")) "Video" else "Screenshot"

                        result.add(
                            RecycleBinItem(
                                uri = uri,
                                name = name,
                                type = if (type == "Video") actualType else "Screenshot",
                                path = path
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Keep the screen usable if the system cannot query a media collection.
            }
        }

        return result.distinctBy { it.uri }.sortedBy { it.name.lowercase() }
    }

    private fun restoreItem(item: RecycleBinItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val request = MediaStore.createTrashRequest(
                contentResolver,
                listOf(item.uri),
                false
            )
            @Suppress("DEPRECATION")
            startIntentSenderForResult(
                request.intentSender,
                REQUEST_MEDIA_ACTION,
                null,
                0,
                0,
                0
            )
        } catch (_: Exception) {
            // The activity refreshes when it resumes.
        }
    }

    private fun permanentlyDeleteItem(item: RecycleBinItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val request = MediaStore.createDeleteRequest(
                contentResolver,
                listOf(item.uri)
            )
            @Suppress("DEPRECATION")
            startIntentSenderForResult(
                request.intentSender,
                REQUEST_MEDIA_ACTION,
                null,
                0,
                0,
                0
            )
        } catch (_: Exception) {
            // The activity refreshes when it resumes.
        }
    }

    companion object {
        private const val REQUEST_MEDIA_ACTION = 401
    }
}

private data class RecycleBinItem(
    val uri: Uri,
    val name: String,
    val type: String,
    val path: String
)

@Composable
private fun RecycleBinScreen(
    loadItems: () -> List<RecycleBinItem>,
    requestRestore: (RecycleBinItem) -> Unit,
    requestPermanentDelete: (RecycleBinItem) -> Unit,
    onBack: () -> Unit
) {
    var items by remember { mutableStateOf(emptyList<RecycleBinItem>()) }
    var loading by remember { mutableStateOf(true) }
    var selectedForDelete by remember { mutableStateOf<RecycleBinItem?>(null) }
    var refreshKey by remember { mutableStateOf(0) }

    val mediaActionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            refreshKey++
        }
    }

    LaunchedEffect(refreshKey) {
        loading = true
        items = loadItems()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recycle Bin") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
                actions = {
                    TextButton(onClick = { refreshKey++ }) {
                        Text("Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = "Deleted videos and screenshots",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "Restore files to remove them from the Recycle Bin, or permanently delete them.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(16.dp))

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Text(
                    text = "Native Recycle Bin requires Android 11 or newer. On this Android version, deleted files may be permanently removed.",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else if (loading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Loading deleted files…")
                }
            } else if (items.isEmpty()) {
                Text(
                    text = "Recycle Bin is empty.",
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                Text(
                    text = "${items.size} item(s)",
                    style = MaterialTheme.typography.labelLarge
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items, key = { it.uri.toString() }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleSmall
                                )

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    text = "${item.type} • ${item.path}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Spacer(Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { requestRestore(item) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Restore")
                                    }

                                    OutlinedButton(
                                        onClick = { selectedForDelete = item },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Delete Forever")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedForDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { selectedForDelete = null },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    "“${item.name}” will be permanently deleted and may not be recoverable."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedForDelete = null
                        requestPermanentDelete(item)
                    }
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedForDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
