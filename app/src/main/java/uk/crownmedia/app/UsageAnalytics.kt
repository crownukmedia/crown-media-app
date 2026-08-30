package uk.crownmedia.app

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Privacy-conscious product analytics. Event parameters deliberately exclude credentials,
 * provider URLs, playlist identifiers, content titles/IDs, and search terms.
 */
class UsageAnalytics private constructor(
    context: Context,
    private val firebase: FirebaseAnalytics?,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean
        get() = firebase != null

    val consentDecision: Boolean?
        get() = preferences.getBoolean(CONSENT_KEY, true)

    val isEnabled: Boolean
        get() = isConfigured && consentDecision == true

    init {
        firebase?.setAnalyticsCollectionEnabled(consentDecision == true)
    }

    fun updateConsent(enabled: Boolean) {
        preferences.edit().putBoolean(CONSENT_KEY, enabled).apply()
        firebase?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setDeviceClass(value: DeviceClass) {
        if (!isEnabled) return
        firebase?.setUserProperty("device_class", value.name.lowercase())
    }

    fun trackScreen(name: String) = log(
        FirebaseAnalytics.Event.SCREEN_VIEW,
        "screen_name" to name,
        "screen_class" to "MainActivity",
    )

    fun trackLogin(outcome: String, service: CrownService) = log(
        "playlist_login",
        "outcome" to outcome,
        "service" to service.name.lowercase(),
    )

    fun trackContentOpened(kind: String) = log("content_opened", "content_type" to kind)

    fun trackPlaybackRequested(kind: String, player: String, live: Boolean) = log(
        "playback_requested",
        "content_type" to kind,
        "player" to player,
        "is_live" to if (live) 1L else 0L,
    )

    fun trackSearch(scope: String) = log("search_performed", "scope" to scope)

    fun trackCategorySelected(section: String) = log("category_selected", "section" to section)

    private fun log(name: String, vararg parameters: Pair<String, Any>) {
        if (!isEnabled) return
        val bundle = Bundle().apply {
            parameters.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value.take(MAX_PARAMETER_LENGTH))
                    is Long -> putLong(key, value)
                }
            }
        }
        firebase?.logEvent(name, bundle)
    }

    companion object {
        private const val PREFERENCES_NAME = "usage_analytics"
        private const val CONSENT_KEY = "collection_consent"
        private const val MAX_PARAMETER_LENGTH = 100

        fun create(context: Context): UsageAnalytics {
            val analytics = runCatching {
                val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(context)
                app?.let { FirebaseAnalytics.getInstance(context) }
            }.getOrNull()
            return UsageAnalytics(context, analytics)
        }
    }
}
