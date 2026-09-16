package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePreviewSelectionTest {
    @Test
    fun ignoresCardsThatAreNotSubstantiallyVisible() {
        assertNull(
            primaryLivePreviewPosition(
                listOf(
                    LivePreviewVisibility(3, 0.59f, 4),
                    LivePreviewVisibility(4, 0.40f, 0),
                ),
            ),
        )
    }

    @Test
    fun choosesMostVisibleCardBeforeViewportDistance() {
        assertEquals(
            8,
            primaryLivePreviewPosition(
                listOf(
                    LivePreviewVisibility(7, 0.75f, 0),
                    LivePreviewVisibility(8, 0.95f, 300),
                ),
            ),
        )
    }

    @Test
    fun equalVisibilityPrefersViewportCenterThenStableListOrder() {
        assertEquals(
            2,
            primaryLivePreviewPosition(
                listOf(
                    LivePreviewVisibility(4, 1f, 120),
                    LivePreviewVisibility(3, 1f, 40),
                    LivePreviewVisibility(2, 1f, 40),
                ),
            ),
        )
    }
}
