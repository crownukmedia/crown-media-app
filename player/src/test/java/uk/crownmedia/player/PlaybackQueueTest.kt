package uk.crownmedia.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackQueueTest {
    @Test
    fun queueRoundTripPreservesEpisodeOrderAndSubtitles() {
        val queue = listOf(
            PlaybackQueueItem("https://example.test/s1e1.mkv", "Episode 1", "11"),
            PlaybackQueueItem(
                "https://example.test/s1e2.mkv",
                "Episode 2",
                "12",
                listOf(ExternalSubtitle("https://example.test/en.srt", MimeTypes.APPLICATION_SUBRIP, null, null, 1)),
            ),
        )

        assertEquals(queue, decodePlaybackQueue(encodePlaybackQueue(queue)))
    }

    @Test
    fun malformedOrIncompleteQueueCannotCreatePlaybackItems() {
        assertTrue(decodePlaybackQueue(null).isEmpty())
        assertTrue(decodePlaybackQueue("not-json").isEmpty())
        assertTrue(decodePlaybackQueue("[{\"url\":\"\",\"streamId\":\"1\"}]").isEmpty())
    }
}
