
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
import androidx.compose.material3.ExperimentalMaterial3Api
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

private data class RecycleBinItem(
    val uri: Uri,
    val name: String,
    val type: String,
    val path: String
)

class RecycleBinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                RecycleBinScreen(
                    loadItems = { loadTrashedItems() },
                    createRestoreRequest = { item ->
                        createMediaRequest(item, restore = true)
                    },
                    createDeleteRequest = { item ->
                        createMediaRequest(item, restore = false)
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

        for ((collectionUri, defaultType) in collections) {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.IS_TRASHED
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
                        val name =
                            cursor.getString(nameColumn) ?: "Unnamed file"
                        val path = cursor.getString(pathColumn).orEmpty()

                        result.add(
                            RecycleBinItem(
                                uri = ContentUris.withAppendedId(
                                    collectionUri,
                                    id
                                ),
                                name = name,
                                type = if (path.startsWith("Movies/")) {
                                    "Video"
                                } else {
                                    defaultType
                                },
                                path = path
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Continue loading the other media collection.
            }
        }

        return result.distinctBy { it.uri }
            .sortedByDescending { it.name.lowercase() }
    }

    private fun createMediaRequest(
        item: RecycleBinItem,
        restore: Boolean
    ): IntentSenderRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        return try {
            val request = if (restore) {
                MediaStore.createTrashRequest(
                    contentResolver,
                    listOf(item.uri),
                    false
                )
            } else {
                MediaStore.createDeleteRequest(
                    contentResolver,
                    listOf(item.uri)
                )
            }

            IntentSenderRequest.Builder(request.intentSender).build()
        } catch (_: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleBinScreen(
    loadItems: () -> List<RecycleBinItem>,
    createRestoreRequest: (RecycleBinItem) -> IntentSenderRequest?,
    createDeleteRequest: (RecycleBinItem) -> IntentSenderRequest?,
    onBack: () -> Unit
) {
    var binItems by remember {
        mutableStateOf(emptyList<RecycleBinItem>())
    }
    var loading by remember { mutableStateOf(true) }
    var selectedForDelete by remember {
        mutableStateOf<RecycleBinItem?>(null)
    }
    var status by remember { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }

    val mediaActionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        status = if (result.resultCode == Activity.RESULT_OK) {
            "Recycle Bin updated."
        } else {
            "Action cancelled."
        }
        refreshKey++
    }

    LaunchedEffect(refreshKey) {
        loading = true
        binItems = loadItems()
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
                "Deleted videos and screenshots",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(6.dp))

            Text(
                "Restore files or permanently delete them.",
                style = MaterialTheme.typography.bodyMedium
            )

            if (status.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(status)
            }

            Spacer(Modifier.height(16.dp))

            when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                    Text(
                        "Native Recycle Bin requires Android 11 or newer. " +
                        "On older Android versions, deleted files may be " +
                        "permanently removed."
                    )
                }

                loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Loading deleted files...")
                    }
                }

                binItems.isEmpty() -> {
                    Text("Recycle Bin is empty.")
                }

                else -> {
                    Text("${binItems.size} item(s)")
                    Spacer(Modifier.height(8.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            binItems,
                            key = { it.uri.toString() }
                        ) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        item.name,
                                        style = MaterialTheme.typography.titleSmall
                                    )

                                    Spacer(Modifier.height(4.dp))

                                    Text(
                                        "${item.type} • ${item.path}",
                                        style = MaterialTheme.typography.bodySmall
                                    )

                                    Spacer(Modifier.height(10.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                val request =
                                                    createRestoreRequest(item)

                                                if (request != null) {
                                                    mediaActionLauncher.launch(
                                                        request
                                                    )
                                                } else {
                                                    status =
                                                        "Could not prepare restore request."
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Restore")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                selectedForDelete = item
                                            },
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
    }

    selectedForDelete?.let { item ->
        AlertDialog(
            onDismissRequest = {
                selectedForDelete = null
            },
            title = {
                Text("Delete permanently?")
            },
            text = {
                Text(
                    "\"${item.name}\" will be permanently deleted " +
                    "and may not be recoverable."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedForDelete = null

                        val request = createDeleteRequest(item)

                        if (request != null) {
                            mediaActionLauncher.launch(request)
                        } else {
                            status =
                                "Could not prepare permanent-delete request."
                        }
                    }
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        selectedForDelete = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
