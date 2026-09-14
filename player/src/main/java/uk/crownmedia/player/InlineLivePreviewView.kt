package uk.crownmedia.player

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** Lightweight card overlay owned by the single activity-level preview controller. */
@UnstableApi
class InlineLivePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    init {
        visibility = View.GONE
        alpha = 0f
        isClickable = false
        isFocusable = false
    }

    internal fun prepare(playerView: PlayerView) {
        animate().cancel()
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        removeAllViews()
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        alpha = 0f
        visibility = View.VISIBLE
    }

    internal fun reveal() {
        if (visibility != View.VISIBLE) return
        animate().alpha(1f).setDuration(PREVIEW_FADE_MS).start()
    }

    internal fun reset() {
        animate().cancel()
        (getChildAt(0) as? PlayerView)?.player = null
        removeAllViews()
        alpha = 0f
        visibility = View.GONE
    }

    /** Clears recycled-holder presentation; stream ownership remains with the controller. */
    fun clearPreviewSurface() = reset()

    private companion object {
        const val PREVIEW_FADE_MS = 160L
    }
}
