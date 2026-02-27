package com.example.gps_speedometer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.*

class SpeedService : Service() {

    private val CHANNEL_ID = "speed_channel"
    private val NOTIFICATION_ID = 1
    private val TAG = "SpeedService"

    private var currentSpeed = 0f
    private var radarLimit = -1
    private var flashState = false

    private lateinit var logFile: File

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_SPEED") {
                currentSpeed = intent.getFloatExtra("speed", 0f)
                radarLimit = intent.getIntExtra("radarLimit", -1)
                logToFile("Received update: speed=$currentSpeed, radar=$radarLimit")

                // Update notification
                updateNotification()

                // NEW: Forward update to overlay service
                val overlayIntent = Intent("UPDATE_OVERLAY").apply {
                    putExtra("speed", currentSpeed)
                    putExtra("radarLimit", radarLimit)
                }
                sendBroadcast(overlayIntent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        logToFile("onCreate started")
        createNotificationChannel()

        val filter = IntentFilter("UPDATE_SPEED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            logToFile("Foreground service started with notification")
        } catch (e: Exception) {
            logToFile("Error starting foreground: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logToFile("onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logToFile("onDestroy")
        unregisterReceiver(updateReceiver)
        super.onDestroy()
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
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speed Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows current speed and radar warnings"
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            logToFile("Notification channel created")
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speedText = String.format("%.0f km/h", currentSpeed)
        val contentText = if (radarLimit > 0) "⚠️ Radar $radarLimit ahead" else "No radar"

        flashState = !flashState
        val icon = if (radarLimit > 0 && flashState) {
            android.R.drawable.ic_dialog_alert
        } else {
            android.R.drawable.ic_dialog_info
        }

        logToFile("Building notification: speed=$speedText, radar=$radarLimit, icon=$icon")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Trip Companion")
            .setContentText(speedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$speedText\n$contentText"))
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        logToFile("Notification updated")
    }
}