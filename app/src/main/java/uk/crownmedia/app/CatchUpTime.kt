package uk.crownmedia.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CATCH_UP_START_FORMATTER =
    DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.US)

/** Formats the archive timestamp in the provider's timezone, as required by Xtream timeshift. */
internal fun formatCatchUpStart(
    epochSeconds: Long,
    providerTimezone: String?,
    fallbackZone: ZoneId = ZoneId.systemDefault(),
): String {
    val providerZone = providerTimezone
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: fallbackZone
    return CATCH_UP_START_FORMATTER.withZone(providerZone).format(Instant.ofEpochSecond(epochSeconds))
}
