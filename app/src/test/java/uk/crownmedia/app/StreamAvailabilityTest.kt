package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import uk.crownmedia.core.design.StreamAvailability

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StreamAvailabilityTest {
    private val tracker by lazy { StreamAvailability(RuntimeEnvironment.getApplication()) }

    @Test
    fun singleFailureRanksBelowUncheckedWithoutBlacklisting() {
        val now = 1_800_000_000_000L
        tracker.recordPlaybackFailure("temporary", "101", now)

        assertEquals(StreamAvailability.Status.TEMPORARILY_FAILED, tracker.status("temporary", "101", now))
    }

    @Test
    fun repeatedPlaybackFailuresDeprioritizeButNeverHideChannel() {
        val now = 1_800_000_000_000L
        tracker.recordPlaybackFailure("repeated", "202", now)
        tracker.recordPlaybackFailure("repeated", "202", now + 1)
        assertEquals(StreamAvailability.Status.REPEATEDLY_FAILED, tracker.status("repeated", "202", now + 1))

        tracker.recordPlaybackFailure("repeated", "202", now + 2)
        assertEquals(StreamAvailability.Status.REPEATEDLY_FAILED, tracker.status("repeated", "202", now + 2))
    }

    @Test
    fun failedProbeAloneCannotMarkAChannelRepeatedlyFailed() {
        val now = 1_800_000_000_000L
        tracker.recordProbeFailure("probe-only", "207", now)
        assertEquals(StreamAvailability.Status.UNKNOWN, tracker.status("probe-only", "207", now))

        repeat(4) { tracker.recordProbeFailure("probe-only", "207", now + it + 1) }
        assertEquals(StreamAvailability.Status.TEMPORARILY_FAILED, tracker.status("probe-only", "207", now + 5))
    }

    @Test
    fun laterSuccessImmediatelyRestoresChannel() {
        val now = 1_800_000_000_000L
        repeat(3) { tracker.recordPlaybackFailure("recovery", "303", now + it) }
        tracker.recordSuccess("recovery", "303", now + 10)

        assertEquals(StreamAvailability.Status.HEALTHY, tracker.status("recovery", "303", now + 10))
    }

    @Test
    fun staleFailureExpiresSoChannelCanRecoverNaturally() {
        val now = 1_800_000_000_000L
        repeat(3) { tracker.recordPlaybackFailure("expired", "404", now + it) }
        val eightDaysLater = now + 8L * 24 * 60 * 60 * 1000

        assertEquals(StreamAvailability.Status.UNKNOWN, tracker.status("expired", "404", eightDaysLater))
    }

    @Test
    fun failedProbeIsNotRepeatedOnEveryScreenOpen() {
        val now = 1_800_000_000_000L
        tracker.recordProbeFailure("probe-cache", "505", now)

        assertFalse(tracker.shouldProbe("probe-cache", "505", now + 60_000))
        assertTrue(tracker.shouldProbe("probe-cache", "505", now + 13L * 60 * 60 * 1000))
    }

    @Test
    fun successfulProbeUsesLongerRefreshInterval() {
        val now = 1_800_000_000_000L
        tracker.recordSuccess("success-cache", "606", now)

        assertFalse(tracker.shouldProbe("success-cache", "606", now + 13L * 60 * 60 * 1000))
        assertTrue(tracker.shouldProbe("success-cache", "606", now + 25L * 60 * 60 * 1000))
    }
}
