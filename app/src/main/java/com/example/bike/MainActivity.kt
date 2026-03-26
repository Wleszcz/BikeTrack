package com.example.bike

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var currentSpeedView: TextView
    private lateinit var tripTimeView: TextView
    private lateinit var clockView: TextView
    private lateinit var averageSpeedView: TextView
    private lateinit var maxSpeedView: TextView
    private lateinit var statusTextView: TextView

    private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    private var isTripRunning = false
    private var activeStartMs = 0L
    private var accumulatedElapsedMs = 0L
    private var totalDistanceM = 0f
    private var currentSpeedKmh = 0.0
    private var maxSpeedKmh = 0.0
    private var lastLocation: Location? = null
    private var timerJob: Job? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return

            if (!isTripRunning) {
                lastLocation = location
                return
            }

            val previous = lastLocation
            if (previous != null) {
                totalDistanceM += previous.distanceTo(location)
            }

            currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
            if (currentSpeedKmh > maxSpeedKmh) {
                maxSpeedKmh = currentSpeedKmh
            }

            lastLocation = location
            render()
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startLocationUpdates()
            statusTextView.text = getString(R.string.ready)
        } else {
            statusTextView.text = getString(R.string.permission_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentSpeedView = findViewById(R.id.currentSpeed)
        tripTimeView = findViewById(R.id.tripTime)
        clockView = findViewById(R.id.clock)
        averageSpeedView = findViewById(R.id.averageSpeed)
        maxSpeedView = findViewById(R.id.maxSpeed)
        statusTextView = findViewById(R.id.statusText)

        findViewById<Button>(R.id.startButton).setOnClickListener { startTrip() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopTrip() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetTrip() }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (hasLocationPermission()) {
            startLocationUpdates()
            statusTextView.text = getString(R.string.ready)
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        startTimer()
        render()
    }

    override fun onDestroy() {
        timerJob?.cancel()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(500L)
            .setMinUpdateIntervalMillis(500L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun startTrip() {
        if (!hasLocationPermission()) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }

        if (!isTripRunning) {
            isTripRunning = true
            activeStartMs = SystemClock.elapsedRealtime()
            currentSpeedKmh = 0.0
            lastLocation = null
            statusTextView.text = getString(R.string.tracking)
            render()
        }
    }

    private fun stopTrip() {
        if (isTripRunning) {
            accumulatedElapsedMs += SystemClock.elapsedRealtime() - activeStartMs
            isTripRunning = false
            activeStartMs = 0L
            currentSpeedKmh = 0.0
            lastLocation = null
            statusTextView.text = getString(R.string.paused)
            render()
        }
    }

    private fun resetTrip() {
        isTripRunning = false
        activeStartMs = 0L
        accumulatedElapsedMs = 0L
        totalDistanceM = 0f
        currentSpeedKmh = 0.0
        maxSpeedKmh = 0.0
        lastLocation = null
        statusTextView.text = if (hasLocationPermission()) getString(R.string.ready) else getString(R.string.permission_denied)
        render()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (isActive) {
                render()
                delay(1000)
            }
        }
    }

    private fun render() {
        val elapsedMs = elapsedMs()
        currentSpeedView.text = formatSpeed(currentSpeedKmh)
        averageSpeedView.text = formatSpeed(averageSpeedKmh(elapsedMs))
        maxSpeedView.text = formatSpeed(maxSpeedKmh)
        tripTimeView.text = formatTripTime(elapsedMs)
        clockView.text = formatClock()
    }

    private fun elapsedMs(): Long {
        return if (isTripRunning) {
            accumulatedElapsedMs + (SystemClock.elapsedRealtime() - activeStartMs)
        } else {
            accumulatedElapsedMs
        }
    }

    private fun averageSpeedKmh(elapsedMs: Long): Double {
        val hours = elapsedMs / 3_600_000.0
        return if (hours > 0.0) (totalDistanceM / 1000.0) / hours else 0.0
    }

    private fun formatSpeed(speed: Double): String {
        return String.format(Locale.getDefault(), "%.1f", speed)
    }

    private fun formatTripTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatClock(): String {
        return LocalTime.now().format(clockFormatter)
    }
}
