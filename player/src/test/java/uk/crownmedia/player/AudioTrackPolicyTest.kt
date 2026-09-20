package uk.crownmedia.player

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackPolicyTest {
    @Test fun selectedSupportedTrackWins() {
        val tracks = listOf(track(0, false, true), track(1, true, true, C.SELECTION_FLAG_DEFAULT))
        assertEquals(1, AudioTrackPolicy.preferred(tracks, "en")?.trackIndex)
    }

    @Test fun defaultFlagWinsBeforeLocaleAndFirstTrack() {
        val tracks = listOf(track(0, false, true, language = "en"), track(1, false, true, C.SELECTION_FLAG_DEFAULT, "fr"))
        assertEquals(1, AudioTrackPolicy.preferred(tracks, "en")?.trackIndex)
    }

    @Test fun localeWinsWhenNoDefaultExists() {
        val tracks = listOf(track(0, false, true, language = "fr"), track(1, false, true, language = "en-US"))
        assertEquals(1, AudioTrackPolicy.preferred(tracks, "en")?.trackIndex)
    }

    @Test fun unsupportedTracksAreNeverForced() {
        assertNull(AudioTrackPolicy.preferred(listOf(track(0, false, false, C.SELECTION_FLAG_DEFAULT)), "en"))
    }

    private fun track(
        index: Int,
        selected: Boolean,
        supported: Boolean,
        flags: Int = 0,
        language: String? = null,
    ) = AudioTrackCandidate(0, index, supported, selected, flags, language)
}
