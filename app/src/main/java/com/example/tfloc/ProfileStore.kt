package com.example.tfloc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores up to MAX_PROFILES saved (name, lat, lng, radius) profiles as a JSON array
 * in SharedPreferences. No database dependency needed for this small amount of data.
 */
object ProfileStore {

    const val MAX_PROFILES = 5
    private const val PREFS = "tfloc_profiles"
    private const val KEY_PROFILES = "profiles_json"

    data class Profile(
        val name: String,
        val lat: Double,
        val lng: Double,
        val radius: Double
    )

    fun load(context: Context): MutableList<Profile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null) ?: return mutableListOf()
        val arr = JSONArray(raw)
        val result = mutableListOf<Profile>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result.add(
                Profile(
                    name = obj.getString("name"),
                    lat = obj.getDouble("lat"),
                    lng = obj.getDouble("lng"),
                    radius = obj.getDouble("radius")
                )
            )
        }
        return result
    }

    private fun persist(context: Context, profiles: List<Profile>) {
        val arr = JSONArray()
        for (p in profiles) {
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("radius", p.radius)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, arr.toString())
            .apply()
    }

    /** Returns false if MAX_PROFILES already reached and nothing was saved. */
    fun add(context: Context, profile: Profile): Boolean {
        val current = load(context)
        if (current.size >= MAX_PROFILES) return false
        current.add(profile)
        persist(context, current)
        return true
    }

    fun delete(context: Context, index: Int) {
        val current = load(context)
        if (index !in current.indices) return
        current.removeAt(index)
        persist(context, current)
    }
}
