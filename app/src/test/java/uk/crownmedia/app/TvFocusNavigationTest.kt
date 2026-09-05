package uk.crownmedia.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TvFocusNavigationTest {
    @Test
    fun sixteenEpisodeGridStopsAtEveryPositionInItsFinalRow() {
        (12..15).forEach { position ->
            assertEquals(
                TvFocusMove(TvFocusRegion.STAY),
                tvContentFocusMove(position, 16, 4, KeyEvent.KEYCODE_DPAD_DOWN, hasCategories = true),
            )
        }
    }

    @Test
    fun contentLeftAndRightStayInRowWithPredictableBoundaries() {
        assertEquals(TvFocusMove(TvFocusRegion.CATEGORIES), tvContentFocusMove(0, 12, 5, KeyEvent.KEYCODE_DPAD_LEFT, true))
        assertEquals(TvFocusMove(TvFocusRegion.SIDEBAR), tvContentFocusMove(0, 12, 5, KeyEvent.KEYCODE_DPAD_LEFT, false))
        assertEquals(TvFocusMove(TvFocusRegion.ITEM, 1), tvContentFocusMove(0, 12, 5, KeyEvent.KEYCODE_DPAD_RIGHT, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvContentFocusMove(4, 12, 5, KeyEvent.KEYCODE_DPAD_RIGHT, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvContentFocusMove(11, 12, 5, KeyEvent.KEYCODE_DPAD_RIGHT, true))
    }

    @Test
    fun contentUpAndDownUseLogicalGridRowsIncludingIncompleteLastRow() {
        assertEquals(TvFocusMove(TvFocusRegion.SEARCH), tvContentFocusMove(3, 12, 5, KeyEvent.KEYCODE_DPAD_UP, true, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvContentFocusMove(3, 12, 5, KeyEvent.KEYCODE_DPAD_UP, true, false))
        assertEquals(TvFocusMove(TvFocusRegion.ITEM, 3), tvContentFocusMove(8, 12, 5, KeyEvent.KEYCODE_DPAD_UP, true))
        assertEquals(TvFocusMove(TvFocusRegion.ITEM, 11), tvContentFocusMove(8, 12, 5, KeyEvent.KEYCODE_DPAD_DOWN, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvContentFocusMove(11, 12, 5, KeyEvent.KEYCODE_DPAD_DOWN, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvContentFocusMove(2, 6, 3, KeyEvent.KEYCODE_DPAD_UP, false))
    }

    @Test
    fun categoriesMoveVerticallyAndCrossRailsOnlyWithLeftOrRight() {
        assertEquals(TvFocusMove(TvFocusRegion.SIDEBAR), tvCategoryFocusMove(2, 4, KeyEvent.KEYCODE_DPAD_LEFT, false, true))
        assertEquals(TvFocusMove(TvFocusRegion.CONTENT), tvCategoryFocusMove(2, 4, KeyEvent.KEYCODE_DPAD_RIGHT, false, true))
        assertEquals(TvFocusMove(TvFocusRegion.ITEM, 1), tvCategoryFocusMove(2, 4, KeyEvent.KEYCODE_DPAD_UP, false, true))
        assertEquals(TvFocusMove(TvFocusRegion.ITEM, 3), tvCategoryFocusMove(2, 4, KeyEvent.KEYCODE_DPAD_DOWN, false, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvCategoryFocusMove(0, 4, KeyEvent.KEYCODE_DPAD_UP, false, true))
        assertEquals(TvFocusMove(TvFocusRegion.CATEGORY_MENU), tvCategoryFocusMove(0, 4, KeyEvent.KEYCODE_DPAD_UP, true, true))
        assertEquals(TvFocusMove(TvFocusRegion.STAY), tvCategoryFocusMove(3, 4, KeyEvent.KEYCODE_DPAD_DOWN, false, true))
    }
}
