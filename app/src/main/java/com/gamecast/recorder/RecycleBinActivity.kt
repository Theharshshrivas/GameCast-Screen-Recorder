package com.gamecast.recorder

import android.app.Activity
import android.content.ContentUris
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class RecycleBinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                RecycleBinScreen(
                    loadItems = { loadTrashedItems() },
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
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        )

        for (collectionUri in collections) {
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
                    val idColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns._ID
                    )
                    val nameColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.DISPLAY_NAME
                    )
                    val pathColumn = cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.RELATIVE_PATH
                    )

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn) ?: "Unnamed file"
                        val path = cursor.getString(pathColumn).orEmpty()
                        val uri = ContentUris.withAppendedId(collectionUri, id)

                        result.add(
                            RecycleBinItem(
                                uri = uri,
                                name = name,
                                type = if (collectionUri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {
                                    "Video"
                                } else {
                                    "Screenshot/Image"
                                },
                                path = path
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Continue loading the other media collection if one query fails.
            }
        }

        return result.distinctBy { it.uri }
            .sortedByDescending { it.name.lowercase() }
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val contentResolver = context.contentResolver

    var items by remember { mutableStateOf(emptyList<RecycleBinItem>()) }
    var loading by remember { mutableStateOf(true) }
    var selectedForDelete by remember { mutableStateOf<RecycleBinItem?>(null) }
    var status by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var pendingAction by remember { mutableStateOf("none") }

    val mediaActionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            status = when (pendingAction) {
                "restore" -> "File restored."
                "delete" -> "File permanently deleted."
                else -> "Action completed."
            }
        } else {
            status = "Action cancelled. No change confirmed."
        }

        pendingAction = "none"
        refreshKey++
    }

    LaunchedEffect(refreshKey) {
        loading = true
        items = loadItems()
        loading = false
    }

    fun launchRestore(item: RecycleBinItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status = "Native Recycle Bin requires Android 11 or newer."
            return
        }

        try {
            val request = MediaStore.createTrashRequest(
                contentResolver,
                listOf(item.uri),
                false
            )
            pendingAction = "restore"
            mediaActionLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        } catch (e: Exception) {
            pendingAction = "none"
            status = "Could not restore file: ${e.message ?: "Unknown error"}"
        }
    }

    fun launchPermanentDelete(item: RecycleBinItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status = "Native Recycle Bin requires Android 11 or newer."
            return
        }

        try {
            val request = MediaStore.createDeleteRequest(
                contentResolver,
                listOf(item.uri)
            )
            pendingAction = "delete"
            mediaActionLauncher.launch(
                IntentSenderRequest.Builder(request.intentSender).build()
            )
        } catch (e: Exception) {
            pendingAction = "none"
            status = "Could not delete file: ${e.message ?: "Unknown error"}"
        }
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
                text = "Restore files or permanently delete them from the system Recycle Bin.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Text(
                    text = "Native Recycle Bin requires Android 11 or newer. Files deleted on older Android versions may be permanently removed.",
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
                            Column(Modifier.padding(12.dp)) {
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
                                        onClick = { launchRestore(item) },
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
                        launchPermanentDelete(item)
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
