package uk.crownmedia.player

import android.view.View
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineLivePreviewViewTest {
    @Test
    fun previewSurfaceNeverParticipatesInCardInputOrFocus() {
        val preview = InlineLivePreviewView(RuntimeEnvironment.getApplication())

        assertEquals(View.GONE, preview.visibility)
        assertFalse(preview.isClickable)
        assertFalse(preview.isFocusable)
        assertEquals(0, preview.childCount)

        val playerView = androidx.media3.ui.PlayerView(RuntimeEnvironment.getApplication()).apply {
            useController = false
        }
        preview.prepare(playerView)
        assertEquals(View.VISIBLE, preview.visibility)
        assertEquals(0f, preview.alpha)
        assertEquals(1, preview.childCount)
        assertEquals(playerView, preview.getChildAt(0))

        preview.clearPreviewSurface()
        assertEquals(View.GONE, preview.visibility)
        assertEquals(0f, preview.alpha)
        assertEquals(0, preview.childCount)
    }
}
