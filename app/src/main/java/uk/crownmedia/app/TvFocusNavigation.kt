package uk.crownmedia.app

import android.view.KeyEvent

internal enum class TvFocusRegion {
    ITEM,
    SIDEBAR,
    CATEGORIES,
    CATEGORY_MENU,
    SEARCH,
    CONTENT,
    STAY,
}

internal data class TvFocusMove(val region: TvFocusRegion, val position: Int = -1)

internal fun tvContentFocusMove(
    position: Int,
    itemCount: Int,
    spanCount: Int,
    keyCode: Int,
    hasCategories: Boolean,
    hasSearch: Boolean = false,
): TvFocusMove {
    if (position !in 0 until itemCount) return TvFocusMove(TvFocusRegion.STAY)
    val columns = spanCount.coerceAtLeast(1)
    val column = position % columns
    val nextRowStart = (position / columns + 1) * columns
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (column > 0) {
            TvFocusMove(TvFocusRegion.ITEM, position - 1)
        } else if (hasCategories) TvFocusMove(TvFocusRegion.CATEGORIES)
        else TvFocusMove(TvFocusRegion.SIDEBAR)
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (column < columns - 1 && position + 1 < itemCount) {
            TvFocusMove(TvFocusRegion.ITEM, position + 1)
        } else TvFocusMove(TvFocusRegion.STAY)
        KeyEvent.KEYCODE_DPAD_UP -> if (position >= columns) {
            TvFocusMove(TvFocusRegion.ITEM, position - columns)
        } else if (hasSearch) TvFocusMove(TvFocusRegion.SEARCH) else TvFocusMove(TvFocusRegion.STAY)
        KeyEvent.KEYCODE_DPAD_DOWN -> if (nextRowStart < itemCount) {
            TvFocusMove(TvFocusRegion.ITEM, (position + columns).coerceAtMost(itemCount - 1))
        } else TvFocusMove(TvFocusRegion.STAY)
        else -> TvFocusMove(TvFocusRegion.STAY)
    }
}

internal fun tvCategoryFocusMove(
    position: Int,
    itemCount: Int,
    keyCode: Int,
    hasBackControl: Boolean,
    hasContent: Boolean,
): TvFocusMove {
    if (position !in 0 until itemCount) return TvFocusMove(TvFocusRegion.STAY)
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> TvFocusMove(TvFocusRegion.SIDEBAR)
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (hasContent) TvFocusMove(TvFocusRegion.CONTENT) else TvFocusMove(TvFocusRegion.STAY)
        KeyEvent.KEYCODE_DPAD_UP -> if (position > 0) {
            TvFocusMove(TvFocusRegion.ITEM, position - 1)
        } else if (hasBackControl) TvFocusMove(TvFocusRegion.CATEGORY_MENU)
        else TvFocusMove(TvFocusRegion.STAY)
        KeyEvent.KEYCODE_DPAD_DOWN -> if (position + 1 < itemCount) {
            TvFocusMove(TvFocusRegion.ITEM, position + 1)
        } else TvFocusMove(TvFocusRegion.STAY)
        else -> TvFocusMove(TvFocusRegion.STAY)
    }
}
