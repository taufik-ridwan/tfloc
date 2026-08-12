package com.example.tfloc

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Minimal client for OpenStreetMap's free Nominatim search API. No API key required.
 * Deliberately avoids pulling in a full HTTP client library (Retrofit/OkHttp) to keep
 * the app small — java.net.HttpURLConnection + org.json (both built into Android) is enough
 * for a handful of user-triggered searches.
 */
object NominatimClient {

    data class SearchResult(val displayName: String, val lat: Double, val lng: Double)

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun search(query: String, userAgent: String, callback: (List<SearchResult>) -> Unit) {
        executor.execute {
            val results = try {
                fetch(query, userAgent)
            } catch (e: Exception) {
                emptyList()
            }
            mainHandler.post { callback(results) }
        }
    }

    private fun fetch(query: String, userAgent: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=5")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        // Nominatim's usage policy requires a descriptive User-Agent identifying the app.
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val arr = JSONArray(body)
        val out = mutableListOf<SearchResult>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(
                SearchResult(
                    displayName = obj.getString("display_name"),
                    lat = obj.getString("lat").toDouble(),
                    lng = obj.getString("lon").toDouble()
                )
            )
        }
        return out
    }
}
