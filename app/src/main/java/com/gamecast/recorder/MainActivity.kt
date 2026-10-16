
package com.gamecast.recorder

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var screenCaptureLauncher:
        ActivityResultLauncher<Intent>

    private var isRecording by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var statusMessage by mutableStateOf(
        "Ready to record your screen"
    )

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordingService.ACTION_STATUS) {
                return
            }

            isRecording = intent.getBooleanExtra(
                RecordingService.EXTRA_RECORDING,
                false
            )

            isPaused = intent.getBooleanExtra(
                RecordingService.EXTRA_PAUSED,
                false
            )

            statusMessage = intent.getStringExtra(
                RecordingService.EXTRA_MESSAGE
            ) ?: "Ready"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        screenCaptureLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                val serviceIntent = Intent(
                    this,
                    RecordingService::class.java
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
                }

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )
                statusMessage = "Starting screen recording..."
            } else {
                statusMessage = "Screen recording permission was cancelled"
            }
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "GAMECAST",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Screen Recorder",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Clean Capture.",
                            fontSize = 15.sp
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(modifier = Modifier.height(20.dp))

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
                                        if (isPaused) {
                                            RecordingService.ACTION_RESUME
                                        } else {
                                            RecordingService.ACTION_PAUSE
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (isPaused) {
                                        "Resume Recording"
                                    } else {
                                        "Pause Recording"
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedButton(
                                onClick = {
                                    sendRecordingAction(
                                        RecordingService.ACTION_STOP
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Stop Recording")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                statusMessage =
                                    "My Recordings will be implemented in Phase 3."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("My Recordings")
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "480p • 540p • 720p • 1080p • 2K",
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Phase 1 • Screen Video Capture",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        statusMessage = "Waiting for screen capture permission..."
        screenCaptureLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private fun sendRecordingAction(action: String) {
        val intent = Intent(this, RecordingService::class.java).apply {
            this.action = action
        }

        startService(intent)
    }

    override fun onDestroy() {
        unregisterReceiver(statusReceiver)
        super.onDestroy()
    }
}
