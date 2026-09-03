package uk.crownmedia.player

internal class PlaybackRecoveryPolicy(private val live: Boolean) {
    private var consecutiveRetries = 0

    fun nextRetryDelayMillis(recoverable: Boolean): Long? {
        if (!recoverable) return null
        val limit = if (live) LIVE_RETRY_LIMIT else VIDEO_RETRY_LIMIT
        if (consecutiveRetries >= limit) return null
        return RETRY_DELAYS_MS[consecutiveRetries.coerceAtMost(RETRY_DELAYS_MS.lastIndex)].also {
            consecutiveRetries++
        }
    }

    fun onStablePlayback() {
        consecutiveRetries = 0
    }

    internal fun retriesUsed(): Int = consecutiveRetries

    companion object {
        private const val LIVE_RETRY_LIMIT = 3
        private const val VIDEO_RETRY_LIMIT = 1
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}
