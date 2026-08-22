package uk.crownmedia.core.design

import android.content.Context

/**
 * Conservative playback health cache. Player failures are stronger evidence than background
 * probes: a probe may promote a stream after a successful media GET, but probe failures alone can
 * never classify content as repeatedly failed or prevent a playback attempt.
 */
class StreamAvailability(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun status(playlistId: String, streamId: String, now: Long = System.currentTimeMillis()): Status {
        val values = preferences.getString(key(playlistId, streamId), null)?.split('|') ?: return Status.UNKNOWN
        val playbackFailures = values.getOrNull(0)?.toIntOrNull() ?: return Status.UNKNOWN
        val probeFailures = values.getOrNull(1)?.toIntOrNull() ?: 0
        val lastSuccess = values.getOrNull(2)?.toLongOrNull() ?: 0L
        val lastFailure = values.getOrNull(3)?.toLongOrNull() ?: 0L
        if (lastSuccess >= lastFailure && lastSuccess >= now - SUCCESS_TTL_MS) return Status.HEALTHY
        if (lastFailure < now - FAILURE_TTL_MS) return Status.UNKNOWN
        return when {
            playbackFailures >= REPEATED_PLAYBACK_FAILURES -> Status.REPEATEDLY_FAILED
            playbackFailures > 0 || probeFailures >= TEMPORARY_PROBE_FAILURES -> Status.TEMPORARILY_FAILED
            else -> Status.UNKNOWN
        }
    }

    fun recordSuccess(playlistId: String, streamId: String, now: Long = System.currentTimeMillis()) {
        preferences.edit().putString(key(playlistId, streamId), "0|0|$now|0").apply()
    }

    fun recordPlaybackFailure(playlistId: String, streamId: String, now: Long = System.currentTimeMillis()) {
        updateFailure(playlistId, streamId, now, playback = true)
    }

    fun recordProbeFailure(playlistId: String, streamId: String, now: Long = System.currentTimeMillis()) {
        updateFailure(playlistId, streamId, now, playback = false)
    }

    fun shouldProbe(playlistId: String, streamId: String, now: Long = System.currentTimeMillis()): Boolean {
        val values = preferences.getString(key(playlistId, streamId), null)?.split('|') ?: return true
        val lastSuccess = values.getOrNull(2)?.toLongOrNull() ?: 0L
        val lastFailure = values.getOrNull(3)?.toLongOrNull() ?: 0L
        val lastObservation = maxOf(lastSuccess, lastFailure)
        val interval = if (status(playlistId, streamId, now) == Status.HEALTHY) SUCCESS_PROBE_INTERVAL_MS else FAILURE_PROBE_INTERVAL_MS
        return lastObservation < now - interval
    }

    enum class Status(val rank: Int) {
        HEALTHY(0), UNKNOWN(1), TEMPORARILY_FAILED(2), REPEATEDLY_FAILED(3),
    }

    private fun updateFailure(playlistId: String, streamId: String, now: Long, playback: Boolean) {
        val existing = preferences.getString(key(playlistId, streamId), null)?.split('|').orEmpty()
        val expired = (existing.getOrNull(3)?.toLongOrNull() ?: 0L) < now - FAILURE_TTL_MS
        val playbackFailures = if (expired) 0 else existing.getOrNull(0)?.toIntOrNull() ?: 0
        val probeFailures = if (expired) 0 else existing.getOrNull(1)?.toIntOrNull() ?: 0
        val lastSuccess = existing.getOrNull(2)?.toLongOrNull() ?: 0L
        val updatedPlaybackFailures = playbackFailures + if (playback) 1 else 0
        val updatedProbeFailures = probeFailures + if (playback) 0 else 1
        preferences.edit().putString(
            key(playlistId, streamId),
            "$updatedPlaybackFailures|$updatedProbeFailures|$lastSuccess|$now",
        ).apply()
    }

    companion object {
        // v2 intentionally discards false failures recorded while redirected media HTTP was blocked.
        private const val PREFERENCES = "stream_availability_v2"
        private const val REPEATED_PLAYBACK_FAILURES = 2
        private const val TEMPORARY_PROBE_FAILURES = 2
        private const val FAILURE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
        private const val FAILURE_PROBE_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val SUCCESS_PROBE_INTERVAL_MS = 24L * 60 * 60 * 1000

        private fun key(playlistId: String, streamId: String) = "$playlistId:$streamId"
    }
}
