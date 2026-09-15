package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvResponsiveSizingTest {
    @Test
    fun expandedNavigationWidthIsResponsiveAcrossTvResolutionClasses() {
        // 720p-class TV configurations commonly expose about 640dp of usable width.
        assertEquals(184, MainActivity.responsiveTvNavigationWidthDp(640))
        // 1080p and 4K configurations retain a bounded overlay so content never jumps.
        assertEquals(216, MainActivity.responsiveTvNavigationWidthDp(960))
        assertEquals(216, MainActivity.responsiveTvNavigationWidthDp(1280))
    }

    @Test
    fun liveChannelRailLeavesUsefulPreviewSpaceAcrossTvAndTabletWidths() {
        assertEquals(220, MainActivity.responsiveLiveChannelNavigationWidthDp(640, television = true))
        assertEquals(220, MainActivity.responsiveLiveChannelNavigationWidthDp(960, television = true))
        assertEquals(282, MainActivity.responsiveLiveChannelNavigationWidthDp(1280, television = true))
        assertEquals(200, MainActivity.responsiveLiveChannelNavigationWidthDp(600, television = false))
        assertEquals(240, MainActivity.responsiveLiveChannelNavigationWidthDp(800, television = false))
        assertEquals(140L, MainActivity.LIVE_CHANNEL_BROWSER_PREVIEW_DELAY_MS)
    }

    @Test
    fun secondaryCategoryRailAndGridScaleAcrossTvResolutionClasses() {
        assertEquals(168, MainActivity.responsiveTvCategoryNavigationWidthDp(640))
        assertEquals(192, MainActivity.responsiveTvCategoryNavigationWidthDp(960))
        assertEquals(216, MainActivity.responsiveTvCategoryNavigationWidthDp(1280))

        assertEquals(2, MainActivity.responsiveTvContentColumnCount(640))
        assertEquals(4, MainActivity.responsiveTvContentColumnCount(960))
        assertEquals(5, MainActivity.responsiveTvContentColumnCount(1280))
    }

    @Test
    fun previewSchedulingLeavesAtLeastTwoAndHalfSecondsForRendering() {
        assertEquals(350L, MainActivity.TV_PREVIEW_DELAY_MS)
        assertEquals(450L, MainActivity.MOBILE_PREVIEW_DELAY_MS)
        assertTrue(MainActivity.TV_PREVIEW_DELAY_MS < 1_000L)
        assertTrue(MainActivity.MOBILE_PREVIEW_DELAY_MS < 1_000L)
    }
}
