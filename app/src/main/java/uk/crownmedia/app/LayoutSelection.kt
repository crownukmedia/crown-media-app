package uk.crownmedia.app

import android.content.Context

internal enum class AppLayout { MOBILE, TELEVISION }
internal enum class AppLayoutPreference { AUTO, MOBILE, TELEVISION }

/** Stores the user's explicit TV-layout consent before the activity inflates its shell. */
internal class LayoutSelection(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val hasUserChoice: Boolean
        get() = preferences.contains(KEY_LAYOUT)

    val preference: AppLayoutPreference
        get() = when (preferences.getString(KEY_LAYOUT, null)) {
            VALUE_MOBILE -> AppLayoutPreference.MOBILE
            VALUE_TELEVISION -> AppLayoutPreference.TELEVISION
            else -> AppLayoutPreference.AUTO
        }

    fun resolve(deviceClass: DeviceClass): AppLayout = when (preference) {
        AppLayoutPreference.MOBILE -> AppLayout.MOBILE
        AppLayoutPreference.TELEVISION -> AppLayout.TELEVISION
        AppLayoutPreference.AUTO -> deviceClass.defaultLayout()
    }

    fun select(layout: AppLayout) {
        select(if (layout == AppLayout.TELEVISION) AppLayoutPreference.TELEVISION else AppLayoutPreference.MOBILE)
    }

    fun select(preference: AppLayoutPreference) {
        val value = when (preference) {
            AppLayoutPreference.AUTO -> VALUE_AUTO
            AppLayoutPreference.MOBILE -> VALUE_MOBILE
            AppLayoutPreference.TELEVISION -> VALUE_TELEVISION
        }
        preferences.edit().putString(KEY_LAYOUT, value).apply()
    }

    internal fun clear() {
        preferences.edit().remove(KEY_LAYOUT).apply()
    }

    private companion object {
        const val PREFERENCES = "crown_layout_selection"
        const val KEY_LAYOUT = "layout"
        const val VALUE_AUTO = "auto"
        const val VALUE_MOBILE = "mobile"
        const val VALUE_TELEVISION = "television"
    }
}

internal fun DeviceClass.defaultLayout(): AppLayout = when (this) {
    DeviceClass.TELEVISION -> AppLayout.TELEVISION
    DeviceClass.PHONE, DeviceClass.TABLET -> AppLayout.MOBILE
}

internal fun AppLayout.startupLayoutResource(): Int = when (this) {
    AppLayout.TELEVISION -> R.layout.activity_main_television
    AppLayout.MOBILE -> R.layout.activity_main
}

internal val AppLayoutPreference.labelResource: Int
    get() = when (this) {
        AppLayoutPreference.AUTO -> R.string.app_layout_auto
        AppLayoutPreference.MOBILE -> R.string.app_layout_mobile
        AppLayoutPreference.TELEVISION -> R.string.app_layout_tv
    }

internal fun Context.appLayout(): AppLayout = LayoutSelection(this).resolve(deviceClass())
