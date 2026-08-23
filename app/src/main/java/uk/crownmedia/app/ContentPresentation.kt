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

internal fun ContentCountState.navigationLabel(title: String, locale: Locale = Locale.getDefault()): String =
    "$title\n(${displayValue(locale)})"

internal fun ContentCountState.homeDescription(noun: String, locale: Locale = Locale.getDefault()): String = when (this) {
    ContentCountState.Loading -> "Loading $noun count…"
    is ContentCountState.Ready -> "${displayValue(locale)} $noun"
    ContentCountState.Unavailable -> "$noun count unavailable"
}
