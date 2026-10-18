package uk.crownmedia.app

import java.text.NumberFormat
import java.util.Locale

internal sealed interface ContentCountState {
    data object Loading : ContentCountState
    data class Ready(val count: Int) : ContentCountState
    data object Unavailable : ContentCountState
}

internal fun ContentCountState.displayValue(locale: Locale = Locale.getDefault()): String = when (this) {
    ContentCountState.Loading -> ""
    is ContentCountState.Ready -> NumberFormat.getIntegerInstance(locale).format(count)
    ContentCountState.Unavailable -> ""
}

internal fun ContentCountState.navigationLabel(
    title: String,
    locale: Locale = Locale.getDefault(),
    television: Boolean = false,
): String = displayValue(locale).let { value ->
    when {
        value.isEmpty() -> title
        television -> "$title ($value)"
        else -> "$title\n($value)"
    }
}

internal fun ContentCountState.homeDescription(noun: String, locale: Locale = Locale.getDefault()): String = when (this) {
    ContentCountState.Loading -> ""
    is ContentCountState.Ready -> "${displayValue(locale)} $noun"
    ContentCountState.Unavailable -> ""
}

internal fun ContentCountState.homeTileDescription(
    noun: String,
    detail: String,
    locale: Locale = Locale.getDefault(),
): String = listOf(homeDescription(noun, locale), detail).filter(String::isNotBlank).joinToString(" • ")

/**
 * Counts shown in navigation must never trigger a provider catalog download. A complete Room
 * catalog is authoritative; while a refresh is incomplete, no cached or partial value is shown.
 */
internal fun cachedContentCountState(
    catalogComplete: Boolean,
    cachedCount: Int,
): ContentCountState = when {
    catalogComplete -> ContentCountState.Ready(cachedCount.coerceAtLeast(0))
    else -> ContentCountState.Loading
}
