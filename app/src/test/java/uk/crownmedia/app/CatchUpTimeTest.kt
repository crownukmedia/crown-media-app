package uk.crownmedia.app

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CatchUpTimeTest {
    @Test
    fun archiveStartUsesProviderTimezoneInsteadOfDeviceTimezone() {
        val instant = Instant.parse("2026-01-15T12:00:00Z").epochSecond

        assertEquals(
            "2026-01-15:07-00",
            formatCatchUpStart(instant, "America/New_York", fallbackZone = ZoneId.of("Asia/Karachi")),
        )
    }

    @Test
    fun missingOrInvalidProviderTimezoneUsesSafeFallback() {
        val instant = Instant.parse("2026-07-15T12:00:00Z").epochSecond
        val fallback = ZoneId.of("Europe/London")

        assertEquals("2026-07-15:13-00", formatCatchUpStart(instant, null, fallback))
        assertEquals("2026-07-15:13-00", formatCatchUpStart(instant, "not/a-zone", fallback))
    }
}
