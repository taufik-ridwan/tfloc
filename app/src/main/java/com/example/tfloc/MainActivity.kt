package com.example.tfloc

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

// Default starting point: Jakarta. User pans/long-presses/searches to pick their own area.
private val DEFAULT_CENTER = GeoPoint(-6.2088, 106.8456)
private const val MIN_RADIUS_M = 100
private const val PREFS_NAME = "tfloc_prefs"

// osmdroid's bundled TileSourceFactory.MAPNIK constant hardcodes plain http:// URLs, which
// violates OSM's usage policy (HTTPS required) and gets silently blocked. Defining our own
// source lets us pin it to https:// explicitly.
private val OSM_HTTPS_TILE_SOURCE = XYTileSource(
    "MapnikHttps",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.tile.openstreetmap.org/",
        "https://b.tile.openstreetmap.org/",
        "https://c.tile.openstreetmap.org/"
    ),
    "© OpenStreetMap contributors"
)

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var centerLabel: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var radiusSeek: SeekBar
    private lateinit var startStopBtn: Button
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchBtn: Button
    private lateinit var searchResultsList: ListView
    private lateinit var profilesContainer: LinearLayout
    private lateinit var saveProfileBtn: Button

    private var centerPoint: GeoPoint? = null
    private var radiusMeters: Double = 500.0
    private var circleOverlay: Polygon? = null
    private var centerMarker: Marker? = null
    private var spoofing = false
    private var lastSearchResults: List<NominatimClient.SearchResult> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid needs a user agent + a writable cache dir configured before use.
        // OSM's tile servers also now require a Referer header (recent tightening of
        // their usage policy) — without it tiles come back as "Access blocked" images.
        Configuration.getInstance().load(
            applicationContext,
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = "$packageName/1.0"
        Configuration.getInstance().additionalHttpRequestProperties["Referer"] = "https://$packageName"

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        centerLabel = findViewById(R.id.centerLabel)
        radiusLabel = findViewById(R.id.radiusLabel)
        radiusSeek = findViewById(R.id.radiusSeek)
        startStopBtn = findViewById(R.id.startStopBtn)
        statusText = findViewById(R.id.statusText)
        searchInput = findViewById(R.id.searchInput)
        searchBtn = findViewById(R.id.searchBtn)
        searchResultsList = findViewById(R.id.searchResultsList)
        profilesContainer = findViewById(R.id.profilesContainer)
        saveProfileBtn = findViewById(R.id.saveProfileBtn)

        setupMap()
        setupRadiusSeek()
        setupStartStopButton()
        setupSearch()
        setupProfiles()
        ensureLocationPermission()
    }

    private fun setupMap() {
        map.setTileSource(OSM_HTTPS_TILE_SOURCE)
        map.setMultiTouchControls(true)
        map.controller.setZoom(14.0)
        map.controller.setCenter(DEFAULT_CENTER)

        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                setCenter(p)
                return true
            }
        }
        map.overlays.add(MapEventsOverlay(receiver))
    }

    private fun setCenter(point: GeoPoint) {
        centerPoint = point
        centerLabel.text = "Center: %.5f, %.5f".format(point.latitude, point.longitude)
        drawCircle()
    }

    private fun drawCircle() {
        val center = centerPoint ?: return

        centerMarker?.let { map.overlays.remove(it) }
        circleOverlay?.let { map.overlays.remove(it) }

        val marker = Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Center"
        }
        val circle = Polygon(map).apply {
            points = Polygon.pointsAsCircle(center, radiusMeters)
            fillColor = 0x300000FF
            strokeColor = 0xFF0000FF.toInt()
            strokeWidth = 3f
        }

        centerMarker = marker
        circleOverlay = circle
        map.overlays.add(marker)
        map.overlays.add(circle)
        map.invalidate()
    }

    private fun setupRadiusSeek() {
        radiusMeters = (radiusSeek.progress + MIN_RADIUS_M).toDouble()
        radiusLabel.text = "Radius: ${radiusMeters.toInt()} m"

        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                radiusMeters = (progress + MIN_RADIUS_M).toDouble()
                radiusLabel.text = "Radius: ${radiusMeters.toInt()} m"
                if (centerPoint != null) drawCircle()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // ---------- Enable / Disable spoofing ----------

    private fun setupStartStopButton() {
        startStopBtn.setOnClickListener {
            if (!spoofing) startSpoofing() else stopSpoofing()
        }
    }

    private fun startSpoofing() {
        val center = centerPoint
        if (center == null) {
            Toast.makeText(this, "Long-press the map, search, or load a profile first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasLocationPermission()) {
            ensureLocationPermission()
            return
        }

        val intent = Intent(this, MockLocationService::class.java).apply {
            putExtra(MockLocationService.EXTRA_LAT, center.latitude)
            putExtra(MockLocationService.EXTRA_LNG, center.longitude)
            putExtra(MockLocationService.EXTRA_RADIUS, radiusMeters)
        }
        ContextCompat.startForegroundService(this, intent)

        spoofing = true
        startStopBtn.text = "Disable Spoofing"
        statusText.text = "Status: enabled — randomizing inside the circle"
    }

    private fun stopSpoofing() {
        stopService(Intent(this, MockLocationService::class.java))
        spoofing = false
        startStopBtn.text = "Enable Spoofing"
        statusText.text = "Status: disabled"
    }

    // ---------- Search ----------

    private fun setupSearch() {
        searchBtn.setOnClickListener { runSearch() }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else {
                false
            }
        }
        searchResultsList.setOnItemClickListener { _, _, position, _ ->
            val result = lastSearchResults.getOrNull(position) ?: return@setOnItemClickListener
            val point = GeoPoint(result.lat, result.lng)
            setCenter(point)
            map.controller.animateTo(point)
            map.controller.setZoom(15.0)
            searchResultsList.visibility = View.GONE
            searchInput.setText(result.displayName)
        }
    }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return

        searchBtn.isEnabled = false
        NominatimClient.search(query, userAgent = packageName) { results ->
            searchBtn.isEnabled = true
            lastSearchResults = results
            if (results.isEmpty()) {
                Toast.makeText(this, "No results found", Toast.LENGTH_SHORT).show()
                searchResultsList.visibility = View.GONE
                return@search
            }
            val labels = results.map { it.displayName }
            searchResultsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            searchResultsList.visibility = View.VISIBLE
        }
    }

    // ---------- Profiles ----------

    private fun setupProfiles() {
        saveProfileBtn.setOnClickListener { promptSaveProfile() }
        refreshProfilesUi()
    }

    private fun promptSaveProfile() {
        val center = centerPoint
        if (center == null) {
            Toast.makeText(this, "Set a center point first", Toast.LENGTH_SHORT).show()
            return
        }
        if (ProfileStore.load(this).size >= ProfileStore.MAX_PROFILES) {
            Toast.makeText(this, "Max ${ProfileStore.MAX_PROFILES} profiles saved — delete one first", Toast.LENGTH_LONG).show()
            return
        }

        val input = EditText(this).apply { hint = "Profile name (e.g. Home, Office)" }
        AlertDialog.Builder(this)
            .setTitle("Save current area")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Profile" }
                ProfileStore.add(this, ProfileStore.Profile(name, center.latitude, center.longitude, radiusMeters))
                refreshProfilesUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshProfilesUi() {
        profilesContainer.removeAllViews()
        val profiles = ProfileStore.load(this)

        profiles.forEachIndexed { index, profile ->
            val btn = Button(this).apply {
                text = profile.name
                setPadding(24, 12, 24, 12)
                setOnClickListener { loadProfile(profile) }
                setOnLongClickListener {
                    confirmDeleteProfile(index, profile.name)
                    true
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            profilesContainer.addView(btn, params)
        }

        if (profiles.isEmpty()) {
            val hint = TextView(this).apply {
                text = "No saved profiles yet"
                textSize = 13f
                setPadding(4, 8, 4, 8)
            }
            profilesContainer.addView(hint)
        }
    }

    private fun loadProfile(profile: ProfileStore.Profile) {
        val point = GeoPoint(profile.lat, profile.lng)
        radiusMeters = profile.radius
        radiusSeek.progress = (profile.radius - MIN_RADIUS_M).toInt().coerceIn(0, radiusSeek.max)
        radiusLabel.text = "Radius: ${radiusMeters.toInt()} m"
        setCenter(point)
        map.controller.animateTo(point)
        map.controller.setZoom(15.0)
        Toast.makeText(this, "Loaded \"${profile.name}\"", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteProfile(index: Int, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete profile?")
            .setMessage("Remove \"$name\" from saved profiles?")
            .setPositiveButton("Delete") { _, _ ->
                ProfileStore.delete(this, index)
                refreshProfilesUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Permissions ----------

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationPermission() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1001
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1002
            )
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
