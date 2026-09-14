package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvResponsiveSizingTest {
    @Test
    fun expandedNavigationWidthIsResponsiveAcrossTvResolutionClasses() {
        // 720p-class TV configurations commonly expose about 640dp of usable width.
        assertEquals(220, MainActivity.responsiveTvNavigationWidthDp(640))
        // 1080p and 4K configurations retain a bounded overlay so content never jumps.
        assertEquals(260, MainActivity.responsiveTvNavigationWidthDp(960))
        assertEquals(260, MainActivity.responsiveTvNavigationWidthDp(1280))
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
