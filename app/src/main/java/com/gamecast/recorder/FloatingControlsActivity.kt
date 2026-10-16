
package com.gamecast.recorder

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

class FloatingControlsActivity : ComponentActivity() {

    private var permissionGranted by mutableStateOf(false)
    private var message by mutableStateOf("Checking overlay permission...")

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updatePermissionStatus()

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "GameCast Floating Controls",
                            fontSize = 25.sp
                        )

                        Spacer(Modifier.height(12.dp))
                        Text(message)
                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = { requestOverlayPermission() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Allow Overlay Permission")
                        }

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = { startFloatingControls() },
                            enabled = permissionGranted,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Show Floating Controls")
                        }

                        Spacer(Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = {
                                stopService(
                                    Intent(
                                        this@FloatingControlsActivity,
                                        FloatingControlsService::class.java
                                    ).apply {
                                        action = FloatingControlsService.ACTION_HIDE
                                    }
                                )
                                message = "Hide request sent."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Hide Floating Controls")
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Floating controls can appear over other apps. " +
                                "Allow this permission only if you want to use them."
                        )
                    }
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            settingsLauncher.launch(intent)
        } else {
            updatePermissionStatus()
        }
    }

    private fun updatePermissionStatus() {
        permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)

        message = if (permissionGranted) {
            "Permission granted. You can show floating controls."
        } else {
            "Overlay permission is not enabled."
        }
    }

    private fun startFloatingControls() {
        if (!permissionGranted) {
            requestOverlayPermission()
            return
        }

        try {
            startService(
                Intent(
                    this,
                    FloatingControlsService::class.java
                ).apply {
                    action = FloatingControlsService.ACTION_SHOW
                }
            )
            message = "Floating controls start requested."
        } catch (e: Exception) {
            message = "Could not start controls: " +
                (e.message ?: "Unknown error")
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }
}
