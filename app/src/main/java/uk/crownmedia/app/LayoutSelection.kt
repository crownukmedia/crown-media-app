package uk.crownmedia.app

import android.content.Context

internal enum class AppLayout { MOBILE, TELEVISION }

/** Stores the user's explicit TV-layout consent before the activity inflates its shell. */
internal class LayoutSelection(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val hasUserChoice: Boolean
        get() = preferences.contains(KEY_LAYOUT)

    fun resolve(deviceClass: DeviceClass): AppLayout = when (preferences.getString(KEY_LAYOUT, null)) {
        VALUE_MOBILE -> AppLayout.MOBILE
        VALUE_TELEVISION -> AppLayout.TELEVISION
        else -> deviceClass.defaultLayout()
    }

    fun select(layout: AppLayout) {
        preferences.edit().putString(
            KEY_LAYOUT,
            if (layout == AppLayout.TELEVISION) VALUE_TELEVISION else VALUE_MOBILE,
        ).apply()
    }

    internal fun clear() {
        preferences.edit().remove(KEY_LAYOUT).apply()
    }

    private companion object {
        const val PREFERENCES = "crown_layout_selection"
        const val KEY_LAYOUT = "layout"
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

internal fun Context.appLayout(): AppLayout = LayoutSelection(this).resolve(deviceClass())
