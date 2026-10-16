
package com.gamecast.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class FloatingControlsService : Service() {

    companion object {
        const val ACTION_SHOW = "com.gamecast.recorder.FLOATING_SHOW"
        const val ACTION_HIDE = "com.gamecast.recorder.FLOATING_HIDE"
    }

    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopSelf()
            else -> showControls()
        }
        return START_NOT_STICKY
    }

    private fun showControls() {
        if (panel != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) {
            Toast.makeText(
                this,
                "Allow GameCast display-over-other-apps permission first",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            background = GradientDrawable().apply {
                setColor(Color.rgb(25, 29, 40))
                cornerRadius = 28f
                setStroke(2, Color.rgb(70, 135, 255))
            }
        }

        val header = TextView(this).apply {
            text = "GAMECAST  ⋮"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 12)
        }

        root.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val pauseButton = makeButton("Ⅱ  Pause")
        val stopButton = makeButton("■  Stop")
        val screenshotButton = makeButton("▣  Screenshot")
        val closeButton = makeButton("×  Hide controls")

        root.addView(pauseButton)
        root.addView(stopButton)
        root.addView(screenshotButton)
        root.addView(closeButton)

        pauseButton.setOnClickListener {
            paused = !paused
            val action = if (paused) {
                RecordingService.ACTION_PAUSE
            } else {
                RecordingService.ACTION_RESUME
            }

            sendRecordingAction(action)
            pauseButton.text = if (paused) "▶  Resume" else "Ⅱ  Pause"
        }

        stopButton.setOnClickListener {
            sendRecordingAction(RecordingService.ACTION_STOP)
            Toast.makeText(this, "Stop recording requested", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        screenshotButton.setOnClickListener {
            try {
                val launch = Intent(
                    this,
                    ScreenshotActivity::class.java
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                startActivity(launch)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Could not open screenshots: ${e.message ?: "Error"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        closeButton.setOnClickListener {
            stopSelf()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 180
        }

        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (touchX - event.rawX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()

                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(root, params)
            panel = root
            layoutParams = params
        } catch (e: Exception) {
            panel = null
            Toast.makeText(
                this,
                "Could not display floating controls: ${e.message ?: "Error"}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }

    private fun makeButton(label: String): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(44, 58, 83)
                )
            )
        }
    }

    private fun sendRecordingAction(action: String) {
        try {
            startService(
                Intent(this, RecordingService::class.java).apply {
                    this.action = action
                }
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Recording control failed: ${e.message ?: "Error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroy() {
        panel?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        super.onDestroy()
    }
}
