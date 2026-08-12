package com.example.tfloc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class MockLocationService : Service() {

    companion object {
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_RADIUS = "extra_radius"
        const val ACTION_STOP = "com.example.tfloc.action.STOP"
        private const val CHANNEL_ID = "tfloc_channel"
        private const val NOTIF_ID = 1
        private const val UPDATE_INTERVAL_MS = 4000L
        private const val EARTH_RADIUS_M = 6371000.0
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var centerLat = 0.0
    private var centerLng = 0.0
    private var radiusMeters = 500.0
    private var providersRegistered = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            pushRandomLocation()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        centerLat = intent?.getDoubleExtra(EXTRA_LAT, 0.0) ?: 0.0
        centerLng = intent?.getDoubleExtra(EXTRA_LNG, 0.0) ?: 0.0
        radiusMeters = intent?.getDoubleExtra(EXTRA_RADIUS, 500.0) ?: 500.0

        startForeground(NOTIF_ID, buildNotification())

        val ok = registerMockProviders()
        if (!ok) {
            // Not set as the system's mock location app in Developer Options — nothing more we can do.
            stopSelf()
            return START_NOT_STICKY
        }

        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return START_STICKY
    }

    private fun registerMockProviders(): Boolean {
        return try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                addTestProviderCompat(provider)
                locationManager.setTestProviderEnabled(provider, true)
            }
            providersRegistered = true
            true
        } catch (e: SecurityException) {
            // App is not selected as the mock location app for this device
            providersRegistered = false
            false
        }
    }

    private fun addTestProviderCompat(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val props = ProviderProperties.Builder()
                .setAccuracy(ProviderProperties.ACCURACY_FINE)
                .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                .build()
            locationManager.addTestProvider(provider, props)
        } else {
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                false, false, false, false, false, true, true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
        }
    }

    private fun pushRandomLocation() {
        if (!providersRegistered) return
        val (lat, lng) = randomPointInCircle(centerLat, centerLng, radiusMeters)

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val location = Location(provider).apply {
                latitude = lat
                longitude = lng
                accuracy = 5f
                altitude = 0.0
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    bearingAccuracyDegrees = 0f
                    verticalAccuracyMeters = 1f
                    speedAccuracyMetersPerSecond = 0f
                }
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: SecurityException) {
                // permission revoked mid-flight; stop cleanly
                stopSelf()
                return
            }
        }
    }

    /** Uniformly random point within `radiusMeters` of (centerLat, centerLng). */
    private fun randomPointInCircle(centerLat: Double, centerLng: Double, radiusMeters: Double): Pair<Double, Double> {
        val u = Random.nextDouble()
        val v = Random.nextDouble()
        val w = radiusMeters * sqrt(u)
        val t = 2 * PI * v
        val dx = w * cos(t)
        val dy = w * sin(t)

        val newLat = centerLat + (dy / EARTH_RADIUS_M) * (180.0 / PI)
        val newLng = centerLng + (dx / (EARTH_RADIUS_M * cos(Math.toRadians(centerLat)))) * (180.0 / PI)
        return Pair(newLat, newLng)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Location spoofing", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MockLocationService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("tf-loc active")
            .setContentText("Randomizing location inside your chosen area")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disable", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        if (providersRegistered) {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    locationManager.removeTestProvider(provider)
                } catch (e: Exception) {
                    // provider may already be gone
                }
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
