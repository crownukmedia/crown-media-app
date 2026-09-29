package uk.crownmedia.app

import java.text.NumberFormat
import java.util.Locale

internal sealed interface ContentCountState {
    data object Loading : ContentCountState
    data class Ready(val count: Int) : ContentCountState
    data object Unavailable : ContentCountState
}

internal fun ContentCountState.displayValue(locale: Locale = Locale.getDefault()): String = when (this) {
    ContentCountState.Loading -> "…"
    is ContentCountState.Ready -> NumberFormat.getIntegerInstance(locale).format(count)
    ContentCountState.Unavailable -> "—"
}

internal fun ContentCountState.navigationLabel(
    title: String,
    locale: Locale = Locale.getDefault(),
    television: Boolean = false,
): String = if (television) "$title (${displayValue(locale)})" else "$title\n(${displayValue(locale)})"

internal fun ContentCountState.homeDescription(noun: String, locale: Locale = Locale.getDefault()): String = when (this) {
    ContentCountState.Loading -> "Loading $noun count…"
    is ContentCountState.Ready -> "${displayValue(locale)} $noun"
    ContentCountState.Unavailable -> "$noun count unavailable"
}

/**
 * Counts shown in navigation must never trigger a provider catalog download. A complete Room
 * catalog is authoritative; while a refresh is incomplete, the last verified snapshot remains
 * preferable to a partial row count.
 */
internal fun cachedContentCountState(
    catalogComplete: Boolean,
    cachedCount: Int,
    verifiedSnapshot: Int?,
): ContentCountState = when {
    catalogComplete -> ContentCountState.Ready(cachedCount.coerceAtLeast(0))
    verifiedSnapshot != null -> ContentCountState.Ready(verifiedSnapshot.coerceAtLeast(0))
    else -> ContentCountState.Loading
}
