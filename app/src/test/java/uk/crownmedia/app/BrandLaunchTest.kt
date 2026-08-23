package uk.crownmedia.app

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class BrandLaunchTest {
    @Test
    @Config(sdk = [31])
    fun launchThemeUsesWhiteBackgroundAndSafeSquareLogo() {
        val context = RuntimeEnvironment.getApplication()
        val activityInfo = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val theme = context.resources.newTheme().apply { applyStyle(activityInfo.theme, true) }
        val background = TypedValue()
        val icon = TypedValue()

        assertEquals(R.style.Theme_CrownMedia_Starting, activityInfo.theme)
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenBackground, background, true))
        assertEquals(R.color.white, background.resourceId)
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon, icon, true))
        assertEquals(R.drawable.crown_media_logo, icon.resourceId)
    }

    @Test
    @Config(sdk = [28])
    fun launcherForegroundUsesUncroppedSquareSafetyCanvas() {
        val context = RuntimeEnvironment.getApplication()
        val foreground = context.getDrawable(R.drawable.ic_launcher_foreground) as LayerDrawable
        val bitmap = foreground.getDrawable(0) as BitmapDrawable

        assertEquals(bitmap.intrinsicWidth, bitmap.intrinsicHeight)
        assertEquals(1024, bitmap.intrinsicWidth)
    }
}
