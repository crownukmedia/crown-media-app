package uk.crownmedia.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryPolicyTest {
    @Test
    fun livePlaybackGetsBoundedBackoffRetries() {
        val policy = PlaybackRecoveryPolicy(live = true)

        assertEquals(1_000L, policy.nextRetryDelayMillis(recoverable = true))
        assertEquals(2_000L, policy.nextRetryDelayMillis(recoverable = true))
        assertEquals(4_000L, policy.nextRetryDelayMillis(recoverable = true))
        assertNull(policy.nextRetryDelayMillis(recoverable = true))
    }

    @Test
    fun stablePlaybackRestoresLiveRecoveryBudget() {
        val policy = PlaybackRecoveryPolicy(live = true)
        policy.nextRetryDelayMillis(recoverable = true)
        policy.nextRetryDelayMillis(recoverable = true)

        policy.onStablePlayback()

        assertEquals(0, policy.retriesUsed())
        assertEquals(1_000L, policy.nextRetryDelayMillis(recoverable = true))
    }

    @Test
    fun videoKeepsExistingSingleRetryAndFatalErrorsDoNotConsumeIt() {
        val policy = PlaybackRecoveryPolicy(live = false)

        assertNull(policy.nextRetryDelayMillis(recoverable = false))
        assertEquals(1_000L, policy.nextRetryDelayMillis(recoverable = true))
        assertNull(policy.nextRetryDelayMillis(recoverable = true))
    }
}
