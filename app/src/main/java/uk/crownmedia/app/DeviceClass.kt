package uk.crownmedia.app

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

enum class DeviceClass { PHONE, TABLET, TELEVISION }

fun Context.deviceClass(): DeviceClass {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
        return DeviceClass.TELEVISION
    }
    return if (resources.configuration.smallestScreenWidthDp >= 600) {
        DeviceClass.TABLET
    } else {
        DeviceClass.PHONE
    }
}
