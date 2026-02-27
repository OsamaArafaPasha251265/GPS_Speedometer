package com.example.gps_speedometer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.*

class OverlayService : Service() {

    private val TAG = "OverlayService"

    private lateinit var windowManager: WindowManager
    private var overlayView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var currentSpeed = 0f
    private var radarLimit = -1

    // For dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // For logging
    private lateinit var logFile: File

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_OVERLAY") {
                currentSpeed = intent.getFloatExtra("speed", 0f)
                radarLimit = intent.getIntExtra("radarLimit", -1)
                logToFile("Received update: speed=$currentSpeed, radar=$radarLimit")
                updateOverlayContent()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        logToFile("onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter("UPDATE_OVERLAY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        logToFile("Receiver registered")
    }

    private fun setupLogging() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "service_log.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TripCompanion")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write("=== Service log started at ${Date()} ===\n".toByteArray())
                    }
                }
                logFile = File(filesDir, "service_log.txt")
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val tripDir = File(downloadsDir, "TripCompanion")
                if (!tripDir.exists()) tripDir.mkdirs()
                logFile = File(tripDir, "service_log.txt")
            }
        } catch (e: Exception) {
            logFile = File(filesDir, "service_log.txt")
        }
    }

    private fun logToFile(message: String) {
        try {
            logFile.appendText("${Date()}: $message\n")
            Log.d(TAG, message) // Also log to Logcat for immediate visibility
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logToFile("onStartCommand with action: ${intent?.action}")
        when (intent?.action) {
            "SHOW" -> showOverlay()
            "HIDE" -> hideOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        logToFile("showOverlay() called")
        if (overlayView != null) {
            logToFile("overlayView already exists, returning")
            return
        }

        // Convert 80dp to pixels for minimum size
        val minSize = (80 * resources.displayMetrics.density).toInt()

        // Create a TextView for the bubble
        val textView = TextView(this).apply {
            text = String.format("%.1f", currentSpeed)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 24f
            setPadding(24, 12, 24, 12)
            minWidth = minSize
            minHeight = minSize

            // Circular background
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(if (radarLimit > 0) Color.RED else Color.BLUE)
            background = shape
        }

        // Touch listener for dragging and tap-to-open
        textView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params?.x = initialX + (event.rawX - initialTouchX).toInt()
                    params?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) < 10) {
                        logToFile("Tap detected, opening MainActivity")
                        val intent = Intent(this@OverlayService, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    }
                    true
                }
                else -> false
            }
        }

        // Define overlay window parameters
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        try {
            windowManager.addView(textView, params)
            overlayView = textView
            logToFile("Overlay view added successfully")
            // Removed debug Toast: "Overlay added!"
        } catch (e: Exception) {
            logToFile("Failed to add overlay view: ${e.message}")
            runOnUiThread {
                Toast.makeText(this@OverlayService, "Overlay failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun hideOverlay() {
        logToFile("hideOverlay() called")
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
            logToFile("Overlay view removed")
        }
    }

    private fun updateOverlayContent() {
        logToFile("updateOverlayContent() called with speed=$currentSpeed, radar=$radarLimit")
        overlayView?.let { view ->
            val newText = String.format("%.1f", currentSpeed)
            view.text = newText
            logToFile("View text set to: $newText")
            // Update background color based on radar
            val shape = view.background as GradientDrawable
            shape.setColor(if (radarLimit > 0) Color.RED else Color.BLUE)
        } ?: logToFile("overlayView is null, cannot update")
    }

    override fun onDestroy() {
        logToFile("onDestroy")
        hideOverlay()
        unregisterReceiver(updateReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }
}