package com.example.ft_file_manager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object NetworkSettings {
    private const val PREFS_NAME = "network_prefs"
    private const val KEY_FAVORITES = "smb_favorites"

    // Αποθήκευση τελευταίας σύνδεσης
    fun saveLastConnection(context: Context, host: String, user: String, pass: String, port: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_host", host)
            putString("last_user", user)
            putString("last_pass", pass)
            putInt("last_port", port)
            apply()
        }
    }

    // Προσθήκη στα Favorites
    fun addFavorite(context: Context, name: String, host: String, user: String, pass: String, port: Int, share: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favoritesJson = prefs.getString(KEY_FAVORITES, "[]")
        val array = JSONArray(favoritesJson)

        val obj = JSONObject().apply {
            put("name", name)
            put("host", host)
            put("user", user)
            put("pass", pass)
            put("port", port)
            put("share", share)
        }

        array.put(obj)
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    // Έλεγχος αν υπάρχει ήδη στα Favorites (για να μην ξαναρωτάει)
    fun isFavorite(context: Context, host: String, share: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val favoritesJson = prefs.getString(KEY_FAVORITES, "[]")
        return favoritesJson?.contains("\"host\":\"$host\"") == true &&
                favoritesJson.contains("\"share\":\"$share\"")
    }
}