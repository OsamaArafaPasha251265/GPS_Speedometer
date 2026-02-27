package com.example.gps_speedometer

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationProvider
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.gps_speedometer.database.RadarDao
import com.example.gps_speedometer.database.RadarDatabase
import com.example.gps_speedometer.database.RadarLocation
import com.example.gps_speedometer.ui.theme.GPS_SpeedometerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity(), LocationListener {

    private val TAG = "MainActivity"

    // ---------- Crash Logging ----------
    private lateinit var crashLogFile: File

    // ---------- Permission Launchers ----------
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupLocationManager()
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for background alerts", Toast.LENGTH_LONG).show()
        }
    }

    private val requestOverlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            // Permission granted
        } else {
            Toast.makeText(this, "Overlay permission is required for background speed display", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Database ----------
    private lateinit var radarDao: RadarDao
    private val lastNotificationTime = mutableMapOf<Long, Long>()

    // ---------- TTS ----------
    private var tts: TextToSpeech? = null
    private var maleVoice: Voice? = null
    private var femaleVoice: Voice? = null

    // ---------- Maintenance Data ----------
    private data class MaintenanceTask(
        val offsetKm: Int,
        val message: String,
        val odometerAtCreation: Float
    )
    private var maintenanceTasks = listOf<MaintenanceTask>()
    private val prefs by lazy { getSharedPreferences("maintenance", MODE_PRIVATE) }
    private val maintenanceFile by lazy { File(filesDir, "maintenance.csv") }

    // ---------- Location & Speed ----------
    private lateinit var locationManager: LocationManager
    private var currentSpeed = 0f
    private var maxSpeed = 0f
    private var averageSpeed = 0f
    private var tripDistance = 0f
    private var totalDistance = 0f

    // Store previous location for distance calculations
    private var previousLocation: Location? = null
    private var lastLocation: Location? = null   // most recent fix, used for GPS signal

    // GPS signal tracking
    private var lastLocationTime = 0L
    private val GPS_TIMEOUT = 10000L // 10 seconds without update = signal lost
    private var gpsSignal by mutableStateOf(false) // true if we have recent fix

    // Feature 4: Average speed calculation based on distance/time
    private var tripStartTime = 0L
    private var lastIntegerKm = 0

    // Feature 8: Movement threshold
    private var tripStarted = false
    private var pendingDistance = 0f
    private val MIN_SPEED_THRESHOLD = 5f / 3.6f // 5 km/h in m/s
    private val MIN_DISTANCE_THRESHOLD = 300f // 0.3 km in meters

    // ---------- UI State for approaching radar ----------
    private var approachingRadarLimit by mutableStateOf<Int?>(null)
    private var withinRadarZone = false

    // ---------- Enhanced Radar Beep State ----------
    private var currentNearestRadarId: Long? = null
    private var lastNearestDistance: Float = 0f
    private var minNearestDistance: Float = 0f
    private var passStopDistance: Float? = null
    private var beepActive: Boolean = false

    // ---------- Screenshot ----------
    private var appStartTime = 0L
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // ---------- Background Service ----------
    private var speedServiceIntent: Intent? = null
    private var isServiceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            logToFile("SpeedService connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            logToFile("SpeedService disconnected")
        }
    }

    // Handler for periodic GPS signal check
    private val gpsCheckHandler = Handler(Looper.getMainLooper())
    private val gpsCheckRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val signal = lastLocation != null && (now - lastLocationTime) < GPS_TIMEOUT
            if (signal != gpsSignal) {
                gpsSignal = signal
                updateUI()
            }
            gpsCheckHandler.postDelayed(this, 2000) // check every 2 seconds
        }
    }

    // ---------- Custom beep player ----------
    private var radarBeepPlayer: MediaPlayer? = null

    // ---------- Lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        setupGlobalCrashHandler()
        super.onCreate(savedInstanceState)
        logToFile("onCreate started")

        try {
            val db = RadarDatabase.getInstance(this)
            radarDao = db.radarDao()

            ensureMaintenanceFile()
            loadMaintenanceTasks()

            totalDistance = DistancePrefs.getTotalDistance(this)
            appStartTime = System.currentTimeMillis()
            savedInstanceState?.let {
                tripDistance = it.getFloat("tripDistance", 0f)
                appStartTime = it.getLong("appStartTime", appStartTime)
            }

            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            tts = TextToSpeech(this) { status ->
                try {
                    if (status == TextToSpeech.SUCCESS) {
                        val voices = tts?.voices
                        maleVoice = voices?.firstOrNull { it.name.contains("male", ignoreCase = true) }
                        femaleVoice = voices?.firstOrNull { it.name.contains("female", ignoreCase = true) }
                        if (maleVoice == null && !voices.isNullOrEmpty()) {
                            tts?.voice = voices.first()
                        }
                        tts?.setSpeechRate(0.8f)
                        logToFile("TTS initialized successfully")
                    } else {
                        logToFile("TTS initialization failed with status: $status")
                    }
                } catch (e: Exception) {
                    logToFile("Error in TTS init: ${e.message}")
                }
            }

            requestPermissions()

            setContent {
                GPS_SpeedometerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SpeedometerUI(
                            currentSpeed = currentSpeed,
                            maxSpeed = maxSpeed,
                            averageSpeed = averageSpeed,
                            tripDistance = tripDistance,
                            totalDistance = totalDistance,
                            gpsSignal = gpsSignal,
                            approachingRadarLimit = approachingRadarLimit,
                            appStartTime = appStartTime,
                            tripStarted = tripStarted,
                            onAddRadar60 = { addRadar(60) },
                            onAddRadar80 = { addRadar(80) },
                            onAddRadar90 = { addRadar(90) },
                            onAddRadar120 = { addRadar(120) },
                            onAddMaintenanceTask = { km, message -> addMaintenanceTask(km, message) },
                            onSetOdometer = { newValue -> setOdometer(newValue) },
                            onExit = { exitApp() }
                        )
                    }
                }
            }

            try {
                startSpeedService()
            } catch (e: Exception) {
                logToFile("Failed to start service: ${e.message}")
            }

            // Start periodic GPS check
            gpsCheckHandler.post(gpsCheckRunnable)

            logToFile("onCreate completed successfully")
        } catch (e: Exception) {
            logToFile("CRASH in onCreate: ${e.message}")
            logToFile("Stack trace: ${Log.getStackTraceString(e)}")
            runOnUiThread {
                Toast.makeText(this, "App failed to start: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        logToFile("onResume - hiding overlay")
        hideOverlay()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            logToFile("onStop - showing overlay (app went to background)")
            showOverlayIfPermissionGranted()
        } else {
            logToFile("onStop - activity finishing, not showing overlay")
        }
    }

    override fun onDestroy() {
        gpsCheckHandler.removeCallbacks(gpsCheckRunnable)
        stopRadarContinuousAlert() // ensure beep stops and player is released
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            logToFile("Error shutting down TTS: ${e.message}")
        }
        stopSpeedService()
        super.onDestroy()
    }

    private fun requestPermissions() {
        // Location permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            setupLocationManager()
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---------- Global Crash Handler ----------
    private fun setupGlobalCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashToPublicFile(throwable)
            } catch (e: Exception) {
                // Ignore
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashToPublicFile(throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "crash_$timestamp.txt"
            val content = StringBuilder()
            content.append("=== CRASH at $timestamp ===\n")
            content.append("Thread: ${Thread.currentThread().name}\n")
            content.append("Throwable: ${throwable}\n")
            content.append("Stack trace:\n")
            content.append(Log.getStackTraceString(throwable))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TripCompanion")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(content.toString().toByteArray())
                    }
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val tripDir = File(downloadsDir, "TripCompanion")
                    if (!tripDir.exists()) tripDir.mkdirs()
                    val file = File(tripDir, fileName)
                    file.writeText(content.toString())
                } else {
                    val file = File(filesDir, fileName)
                    file.writeText(content.toString())
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun logToFile(message: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "app_log.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TripCompanion")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write("${Date()}: $message\n".toByteArray())
                    }
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val tripDir = File(downloadsDir, "TripCompanion")
                    if (!tripDir.exists()) tripDir.mkdirs()
                    val logFile = File(tripDir, "app_log.txt")
                    logFile.appendText("${Date()}: $message\n")
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun ensureMaintenanceFile() {
        try {
            if (!maintenanceFile.exists()) {
                val defaultContent = """
                    1000,Check radiator water
                    1000,Check engine oil
                    2000,Clean air conditioner filter
                    5000,Replace engine oil
                    10000,Replace air filter
                    20000,Replace spark plugs
                """.trimIndent()
                maintenanceFile.writeText(defaultContent)
            }
        } catch (e: Exception) {
            logToFile("Error ensuring maintenance file: ${e.message}")
        }
    }

    private fun loadMaintenanceTasks() {
        try {
            if (!maintenanceFile.exists()) return
            val reader = BufferedReader(FileReader(maintenanceFile))
            maintenanceTasks = reader.useLines { lines ->
                lines.mapNotNull { line ->
                    val parts = line.split(",")
                    when (parts.size) {
                        2 -> {
                            val offset = parts[0].toIntOrNull()
                            val msg = parts[1].trim()
                            if (offset != null && msg.isNotEmpty())
                                MaintenanceTask(offset, msg, 0f)
                            else null
                        }
                        3 -> {
                            val offset = parts[0].toIntOrNull()
                            val msg = parts[1].trim()
                            val odometer = parts[2].toFloatOrNull()
                            if (offset != null && msg.isNotEmpty() && odometer != null)
                                MaintenanceTask(offset, msg, odometer)
                            else null
                        }
                        else -> null
                    }
                }.toList()
            }
        } catch (e: Exception) {
            logToFile("Error loading maintenance tasks: ${e.message}")
            maintenanceTasks = emptyList()
        }
    }

    private fun appendMaintenanceTask(offsetKm: Int, message: String, odometerAtCreation: Float) {
        try {
            maintenanceFile.appendText("$offsetKm,$message,$odometerAtCreation\n")
            loadMaintenanceTasks()
        } catch (e: Exception) {
            logToFile("Error appending maintenance task: ${e.message}")
        }
    }

    private fun addMaintenanceTask(offsetKm: Int, message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            appendMaintenanceTask(offsetKm, message, totalDistance)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Maintenance task added", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkMaintenanceThresholds(currentKm: Float) {
        try {
            maintenanceTasks.forEach { task ->
                val triggerKm = task.odometerAtCreation + task.offsetKm
                if (currentKm >= triggerKm) {
                    val key = "shown_${task.offsetKm}_${task.message}_${task.odometerAtCreation}"
                    if (!prefs.getBoolean(key, false)) {
                        prefs.edit().putBoolean(key, true).apply()
                        playMaintenanceDingAndSpeak(task.message)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, task.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logToFile("Error checking maintenance thresholds: ${e.message}")
        }
    }

    private fun playMaintenanceDingAndSpeak(message: String) {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone.play()
        } catch (e: Exception) {
            logToFile("Error playing maintenance ding: ${e.message}")
        }
        try {
            if (femaleVoice != null) {
                tts?.voice = femaleVoice
            }
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
        } catch (e: Exception) {
            logToFile("Error speaking maintenance: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationManager() {
        logToFile("setupLocationManager called")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            logToFile("Location permission not granted, requesting again")
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000,
                1f,
                this
            )
            logToFile("Location updates requested")
        } catch (e: Exception) {
            logToFile("Error requesting location updates: ${e.message}")
        }
    }

    override fun onLocationChanged(location: Location) {
        try {
            // Store previous location before updating
            val prevLocation = lastLocation
            lastLocation = location
            lastLocationTime = System.currentTimeMillis()

            // GPS signal is present
            if (!gpsSignal) {
                gpsSignal = true
                updateUI()
            }

            val rawSpeed = location.speed
            currentSpeed = rawSpeed * 3.6f
            logToFile("onLocationChanged: rawSpeed=$rawSpeed, currentSpeed=$currentSpeed, tripStarted=$tripStarted")

            // ---------- Trip start logic (fixed) ----------
            if (!tripStarted) {
                if (rawSpeed > MIN_SPEED_THRESHOLD && prevLocation != null) {
                    val distance = location.distanceTo(prevLocation)
                    // Accumulate any positive distance (no arbitrary threshold)
                    if (distance > 0) {
                        pendingDistance += distance
                        logToFile("Pending distance now: $pendingDistance")
                        if (pendingDistance >= MIN_DISTANCE_THRESHOLD) {
                            tripStarted = true
                            tripDistance = pendingDistance / 1000f
                            tripStartTime = System.currentTimeMillis()
                            pendingDistance = 0f
                            maxSpeed = currentSpeed
                            logToFile("*** TRIP STARTED at distance: $tripDistance km, speed=$currentSpeed")
                        }
                    }
                }
                updateUI()
                // Don't return early – we still need to process radar and update service even before trip starts
                // (But radar processing is after the trip block, so it's fine)
            } else {
                // Trip has started – update max speed
                if (currentSpeed > maxSpeed) {
                    maxSpeed = currentSpeed
                }

                // Update trip distance using previous location
                if (prevLocation != null) {
                    val speedMps = location.speed
                    val distanceMeters = location.distanceTo(prevLocation)
                    val minSpeedMps = 0.5f
                    val minDistanceMeters = 5f

                    if (speedMps > minSpeedMps || distanceMeters > minDistanceMeters) {
                        val distanceDelta = distanceMeters / 1000f
                        tripDistance += distanceDelta
                        totalDistance += distanceDelta
                        DistancePrefs.saveTotalDistance(this@MainActivity, totalDistance)

                        checkMaintenanceThresholds(totalDistance)

                        val currentIntKm = tripDistance.toInt()
                        if (currentIntKm > lastIntegerKm) {
                            lastIntegerKm = currentIntKm
                            val elapsedHours = (System.currentTimeMillis() - tripStartTime) / 1000f / 3600f
                            if (elapsedHours > 0) {
                                averageSpeed = tripDistance / elapsedHours
                            }
                        }
                        logToFile("Trip update: distanceDelta=$distanceDelta km, trip=$tripDistance, total=$totalDistance")
                    }
                }
            }

            // ---------- Radar processing (always run) ----------
            lifecycleScope.launch {
                try {
                    val radars = radarDao.getAllRadars().first()
                    val now = System.currentTimeMillis()
                    var nearestLimit: Int? = null
                    var minDistance = Float.MAX_VALUE
                    var currentlyWithin = false
                    var nearestId: Long? = null
                    var nearestDist = Float.MAX_VALUE

                    for (radar in radars) {
                        val results = FloatArray(1)
                        Location.distanceBetween(
                            location.latitude, location.longitude,
                            radar.latitude, radar.longitude,
                            results
                        )
                        val distance = results[0]

                        if (distance < 300) {
                            currentlyWithin = true
                            if (distance < minDistance) {
                                minDistance = distance
                                nearestLimit = radar.speedLimit
                                nearestId = radar.id
                                nearestDist = distance
                            }

                            // Initial ding and voice (30s cooldown)
                            val last = lastNotificationTime[radar.id] ?: 0
                            if (now - last > 30000) {
                                playRadarDingAndSpeak(radar.speedLimit)
                                lastNotificationTime[radar.id] = now
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Radar ahead! (${radar.speedLimit} km/h)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }

                    // Update overall withinRadarZone (for UI flashing)
                    if (currentlyWithin != withinRadarZone) {
                        withinRadarZone = currentlyWithin
                    }

                    // ---------- Enhanced beep logic ----------
                    if (currentlyWithin && nearestId != null) {
                        if (currentNearestRadarId != nearestId) {
                            // New nearest radar – reset state
                            currentNearestRadarId = nearestId
                            lastNearestDistance = nearestDist
                            minNearestDistance = nearestDist
                            passStopDistance = null
                            beepActive = true  // start beeping immediately (we are within 300m)
                        } else {
                            // Same radar – update state
                            val prevDist = lastNearestDistance
                            lastNearestDistance = nearestDist

                            if (nearestDist < prevDist) {
                                // Approaching
                                minNearestDistance = minOf(minNearestDistance, nearestDist)
                                passStopDistance = null // we are still approaching, reset pass stop
                                beepActive = true
                            } else if (nearestDist > prevDist) {
                                // Receding
                                if (passStopDistance == null) {
                                    // First time we start receding after closest point
                                    passStopDistance = minNearestDistance + 100
                                }
                                // Keep beeping until we have passed by 100m
                                beepActive = nearestDist < passStopDistance!!
                            }
                        }
                        approachingRadarLimit = nearestLimit
                    } else {
                        // No radar within 300m
                        currentNearestRadarId = null
                        beepActive = false
                        approachingRadarLimit = null
                    }

                    // Manage the continuous beep coroutine based on beepActive
                    if (beepActive && (radarAlertJob == null || radarAlertJob?.isActive == false)) {
                        startRadarContinuousAlert()
                    } else if (!beepActive && radarAlertJob != null) {
                        stopRadarContinuousAlert()
                    }

                } catch (e: Exception) {
                    logToFile("Error in radar processing: ${e.message}")
                }
            }

            updateBackgroundService()
            updateUI()
        } catch (e: Exception) {
            logToFile("Error in onLocationChanged: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        when (status) {
            LocationProvider.AVAILABLE -> logToFile("Provider $provider available")
            LocationProvider.TEMPORARILY_UNAVAILABLE -> {
                logToFile("Provider $provider temporarily unavailable")
            }
            LocationProvider.OUT_OF_SERVICE -> {
                logToFile("Provider $provider out of service")
                runOnUiThread {
                    gpsSignal = false
                    updateUI()
                }
            }
        }
    }

    override fun onProviderEnabled(provider: String) {
        logToFile("Provider $provider enabled")
    }

    override fun onProviderDisabled(provider: String) {
        logToFile("Provider $provider disabled")
        runOnUiThread {
            gpsSignal = false
            updateUI()
        }
    }

    private var radarAlertJob: kotlinx.coroutines.Job? = null
    private fun startRadarContinuousAlert() {
        radarAlertJob = lifecycleScope.launch {
            while (beepActive) {
                playRadarBeep()
                // Calculate dynamic delay based on current distance to nearest radar
                val distance = lastNearestDistance.coerceIn(0f, 300f)
                // Map distance 300->2000ms, 0->500ms
                val delayMs = (500 + (distance / 300f) * 1500).toLong()
                delay(delayMs)
            }
        }
    }

    private fun stopRadarContinuousAlert() {
        radarAlertJob?.cancel()
        radarAlertJob = null
        // Release the beep player when no longer needed
        try {
            radarBeepPlayer?.release()
            radarBeepPlayer = null
        } catch (e: Exception) {
            logToFile("Error releasing radar beep player: ${e.message}")
        }
    }

    // Play a custom short beep from raw resource, falling back to default notification
    private fun playRadarBeep() {
        try {
            if (radarBeepPlayer == null) {
                // Try to load custom beep from raw resource
                val resId = R.raw.beep_short // Add your own beep file to res/raw/
                if (resId != 0) {
                    radarBeepPlayer = MediaPlayer.create(this, resId).apply {
                        setOnCompletionListener { it.release() } // release after each play
                    }
                } else {
                    // Fallback to default notification sound
                    val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    radarBeepPlayer = MediaPlayer.create(this, notificationUri)
                }
            }
            radarBeepPlayer?.start()
        } catch (e: Exception) {
            logToFile("Error playing radar beep: ${e.message}")
        }
    }

    /*
     * Plays the initial radar warning: a loud alarm sound followed by a male voice
     * with increased pitch for urgency.
     */
    private fun playRadarDingAndSpeak(speedLimit: Int) {
        // Use TYPE_ALARM for a more urgent sound
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
            ringtone.play()
        } catch (e: Exception) {
            logToFile("Error playing radar ding: ${e.message}")
        }
        try {
            // Use male voice if available
            if (maleVoice != null) {
                tts?.voice = maleVoice
            }
            val message = "Mobile Phone, seat belt or Over Speed Camera Ahead……Limit $speedLimit Km"

            // Increase pitch for stronger tone (1.2 = 20% higher), keep rate normal
            val params = Bundle()
            params.putString("pitch", "1.2")   // raw string key instead of constant
            params.putString("rate", "1.0")    // raw string key instead of constant

            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, null)
        } catch (e: Exception) {
            logToFile("Error speaking radar: ${e.message}")
        }
    }

    private fun addRadar(speedLimit: Int) {
        val location = lastLocation ?: run {
            Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val radar = RadarLocation(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedLimit = speedLimit
                )
                radarDao.insert(radar)
                Toast.makeText(this@MainActivity, "Radar ($speedLimit km/h) saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                logToFile("Error adding radar: ${e.message}")
            }
        }
    }

    // ---------- Set Odometer Function ----------
    private fun setOdometer(newValue: Float) {
        if (newValue >= 0) {
            totalDistance = newValue
            DistancePrefs.saveTotalDistance(this, totalDistance)
            checkMaintenanceThresholds(totalDistance)
            updateUI()
            Toast.makeText(this, "Odometer set to ${String.format("%.1f km", newValue)}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Odometer cannot be negative", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takeScreenshotAndSave() {
        try {
            val view = window.decorView.rootView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            saveBitmapToGallery(bitmap)
        } catch (e: Exception) {
            logToFile("Error taking screenshot: ${e.message}")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("tripDistance", tripDistance)
        outState.putLong("appStartTime", appStartTime)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val endTime = System.currentTimeMillis()
            val startStr = dateFormat.format(Date(appStartTime))
            val endStr = dateFormat.format(Date(endTime))
            val filename = "${startStr}_${endStr}.jpg"

            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GPS_Spedo")
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                Toast.makeText(this, "Screenshot saved: $filename", Toast.LENGTH_SHORT).show()
            } ?: run {
                Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            logToFile("Error saving screenshot to gallery: ${e.message}")
        }
    }

    private fun exitApp() {
        takeScreenshotAndSave()
        stopSpeedService()
        finishAffinity()
    }

    private fun startSpeedService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(this, SpeedService::class.java)
                speedServiceIntent = intent
                ContextCompat.startForegroundService(this, intent)
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                logToFile("Speed service started")
            } catch (e: Exception) {
                logToFile("Error starting speed service: ${e.message}")
            }
        }
    }

    private fun stopSpeedService() {
        try {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
            stopService(speedServiceIntent)
            logToFile("Speed service stopped")
        } catch (e: Exception) {
            logToFile("Error stopping speed service: ${e.message}")
        }
    }

    private fun updateBackgroundService() {
        try {
            val intent = Intent("UPDATE_SPEED")
            intent.putExtra("speed", currentSpeed)
            intent.putExtra("radarLimit", approachingRadarLimit ?: -1)
            sendBroadcast(intent)
        } catch (e: Exception) {
            logToFile("Error updating background service: ${e.message}")
        }
    }

    private fun updateUI() {
        runOnUiThread {
            setContent {
                GPS_SpeedometerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SpeedometerUI(
                            currentSpeed = currentSpeed,
                            maxSpeed = maxSpeed,
                            averageSpeed = averageSpeed,
                            tripDistance = tripDistance,
                            totalDistance = totalDistance,
                            gpsSignal = gpsSignal,
                            approachingRadarLimit = approachingRadarLimit,
                            appStartTime = appStartTime,
                            tripStarted = tripStarted,
                            onAddRadar60 = { addRadar(60) },
                            onAddRadar80 = { addRadar(80) },
                            onAddRadar90 = { addRadar(90) },
                            onAddRadar120 = { addRadar(120) },
                            onAddMaintenanceTask = { km, message -> addMaintenanceTask(km, message) },
                            onSetOdometer = { newValue -> setOdometer(newValue) },
                            onExit = { exitApp() }
                        )
                    }
                }
            }
        }
    }

    // ---------- Overlay Helper Methods ----------
    private fun showOverlayIfPermissionGranted() {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, OverlayService::class.java).apply { action = "SHOW" }
            try {
                startService(intent)
                logToFile("Sent SHOW intent to OverlayService")
            } catch (e: Exception) {
                logToFile("Error sending SHOW intent: ${e.message}")
            }
        } else {
            logToFile("Overlay permission not granted, requesting...")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            requestOverlayPermissionLauncher.launch(intent)
        }
    }

    private fun hideOverlay() {
        val intent = Intent(this, OverlayService::class.java).apply { action = "HIDE" }
        try {
            startService(intent)
            logToFile("Sent HIDE intent to OverlayService")
        } catch (e: Exception) {
            logToFile("Error sending HIDE intent: ${e.message}")
        }
    }
}

// ---------- Composable UI (unchanged except version string) ----------
@Composable
fun SpeedometerUI(
    currentSpeed: Float,
    maxSpeed: Float,
    averageSpeed: Float,
    tripDistance: Float,
    totalDistance: Float,
    gpsSignal: Boolean,
    approachingRadarLimit: Int?,
    appStartTime: Long,
    tripStarted: Boolean,
    onAddRadar60: () -> Unit,
    onAddRadar80: () -> Unit,
    onAddRadar90: () -> Unit,
    onAddRadar120: () -> Unit,
    onAddMaintenanceTask: (km: Int, message: String) -> Unit,
    onSetOdometer: (Float) -> Unit,
    onExit: () -> Unit
) {
    val (showMaintenanceDialog, setShowMaintenanceDialog) = remember { mutableStateOf(false) }
    val (showOdometerDialog, setShowOdometerDialog) = remember { mutableStateOf(false) }
    val (maintenanceInput, setMaintenanceInput) = remember { mutableStateOf("") }
    val (odometerInput, setOdometerInput) = remember { mutableStateOf("") }
    val context = LocalContext.current

    val startDate = remember(appStartTime) {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(appStartTime))
    }
    val startTime = remember(appStartTime) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(appStartTime))
    }

    var elapsedTime by remember { mutableStateOf("00:00:00") }
    LaunchedEffect(appStartTime, tripStarted) {
        while (true) {
            val now = System.currentTimeMillis()
            val diff = now - appStartTime
            val hours = diff / (1000 * 60 * 60)
            val minutes = (diff % (1000 * 60 * 60)) / (1000 * 60)
            val seconds = (diff % (1000 * 60)) / 1000
            elapsedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            delay(1000)
        }
    }

    val shinyColor = Color(0xFFFFD700)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Updated version string
        Text(
            text = "Trip Companion Ver.3",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            color = shinyColor,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        Text(
            text = "Date: $startDate",
            fontSize = 14.sp,
            color = shinyColor,
            modifier = Modifier.padding(vertical = 1.dp)
        )
        Text(
            text = "Start: $startTime",
            fontSize = 14.sp,
            color = shinyColor,
            modifier = Modifier.padding(vertical = 1.dp)
        )
        Text(
            text = "Elapsed: $elapsedTime",
            fontSize = 14.sp,
            color = shinyColor,
            modifier = Modifier.padding(vertical = 1.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = String.format(Locale.getDefault(), "%.1f", if (tripStarted) currentSpeed else 0f),
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                currentSpeed < 60 -> Color(0xFF00FF00)
                currentSpeed < 80 -> Color.Yellow
                currentSpeed < 90 -> Color.Blue
                else -> Color.Red
            },
            textAlign = TextAlign.Center
        )

        if (approachingRadarLimit != null) {
            val infiniteTransition = rememberInfiniteTransition()
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500),
                    repeatMode = RepeatMode.Reverse
                )
            )
            Text(
                text = "RADAR $approachingRadarLimit",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Red.copy(alpha = alpha),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Text(
            text = "km/h",
            fontSize = 24.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MAX", fontSize = 16.sp, color = Color.Gray)
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", if (tripStarted) maxSpeed else 0f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Text("km/h", fontSize = 14.sp, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AVG", fontSize = 16.sp, color = Color.Gray)
                Text(
                    text = String.format(Locale.getDefault(), "%.1f", if (tripStarted) averageSpeed else 0f),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                )
                Text("km/h", fontSize = 14.sp, color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TRIP", fontSize = 16.sp, color = Color.Gray)
                Text(
                    text = String.format(Locale.getDefault(), "%.2f km", if (tripStarted) tripDistance else 0f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2196F3)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ODOMETER", fontSize = 16.sp, color = Color.Gray)
                Text(
                    text = String.format(Locale.getDefault(), "%.1f km", totalDistance),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF4CAF50)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // GPS status text now based on gpsSignal
        Text(
            text = if (gpsSignal) "Receiving GPS Signal …" else "GPS Signal Lost",
            fontSize = 18.sp,
            color = if (gpsSignal) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text("Mark Radar:", fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onAddRadar60,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("60", fontSize = 18.sp)
            }
            Button(
                onClick = onAddRadar80,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("80", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onAddRadar90,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("90", fontSize = 18.sp)
            }
            Button(
                onClick = onAddRadar120,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White
                )
            ) {
                Text("120", fontSize = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // AMN Button (purple)
        Button(
            onClick = { setShowMaintenanceDialog(true) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0))
        ) {
            Text("AMN (Add Maintenance)", color = Color.White, fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Set Odometer Button (orange)
        Button(
            onClick = { setShowOdometerDialog(true) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
        ) {
            Text("Set Odometer", color = Color.White, fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Exit button (red)
        Button(
            onClick = onExit,
            modifier = Modifier
                .width(120.dp)
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
        ) {
            Text("Exit", color = Color.White, fontSize = 18.sp)
        }
    }

    // Maintenance Dialog
    if (showMaintenanceDialog) {
        AlertDialog(
            onDismissRequest = { setShowMaintenanceDialog(false) },
            title = { Text("Add Maintenance Task") },
            text = {
                Column {
                    Text("Enter: offset,message")
                    TextField(
                        value = maintenanceInput,
                        onValueChange = { setMaintenanceInput(it) },
                        placeholder = { Text("e.g., 1000,Check radiator water") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = maintenanceInput.trim()
                        val parts = trimmed.split(",", limit = 2)
                        if (parts.size == 2) {
                            val offsetStr = parts[0].trim()
                            val msg = parts[1].trim()
                            val offset = offsetStr.toIntOrNull()
                            if (offset != null && offset.toString().length <= 6 && msg.length <= 100) {
                                onAddMaintenanceTask(offset, msg)
                                setShowMaintenanceDialog(false)
                                setMaintenanceInput("")
                            } else {
                                Toast.makeText(
                                    context,
                                    "Invalid: offset (max 6 digits) and message (max 100 chars)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                "Format must be: offset,message",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowMaintenanceDialog(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Odometer Dialog
    if (showOdometerDialog) {
        AlertDialog(
            onDismissRequest = { setShowOdometerDialog(false) },
            title = { Text("Set Odometer") },
            text = {
                Column {
                    Text("Enter new odometer value (km):")
                    TextField(
                        value = odometerInput,
                        onValueChange = { setOdometerInput(it) },
                        placeholder = { Text("e.g., 12345.6") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions.Default
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = odometerInput.trim()
                        val value = trimmed.toFloatOrNull()
                        if (value != null && value >= 0) {
                            onSetOdometer(value)
                            setShowOdometerDialog(false)
                            setOdometerInput("")
                        } else {
                            Toast.makeText(
                                context,
                                "Please enter a valid non‑negative number",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { setShowOdometerDialog(false) }) {
                    Text("Cancel")
                }
            }
        )
    }
}