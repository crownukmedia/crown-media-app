package uk.crownmedia.tv.data

import android.content.Context

class CrownPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("crown-media", Context.MODE_PRIVATE)

    fun favoriteKeys(): Set<String> = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    fun isFavorite(key: String): Boolean = favoriteKeys().contains(key)

    fun toggleFavorite(key: String): Set<String> {
        val next = favoriteKeys().toMutableSet()
        if (!next.add(key)) {
            next.remove(key)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
        return next
    }

    fun savePin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN, "").isNullOrBlank()

    fun verifyPin(pin: String): Boolean = pin == prefs.getString(KEY_PIN, "")

    fun saveLastUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, username).apply()
    }

    fun lastUsername(): String = prefs.getString(KEY_USERNAME, "") ?: ""

    companion object {
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_PIN = "parental_pin"
        private const val KEY_USERNAME = "last_username"
    }
}
