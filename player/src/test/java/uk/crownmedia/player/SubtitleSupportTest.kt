package uk.crownmedia.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class SubtitleSupportTest {
    @Test
    fun recognizesSupportedExternalSubtitleFormatsAndRejectsUnknownOnes() {
        val srt = ExternalSubtitle.fromProvider("https://cdn.example/en.srt", language = "en")
        val vtt = ExternalSubtitle.fromProvider("https://cdn.example/track", "text/vtt", language = "es")
        val ass = ExternalSubtitle.fromProvider("https://cdn.example/ar.ass", isForced = true)

        assertEquals(MimeTypes.APPLICATION_SUBRIP, srt?.mimeType)
        assertEquals(MimeTypes.TEXT_VTT, vtt?.mimeType)
        assertEquals(MimeTypes.TEXT_SSA, ass?.mimeType)
        assertTrue(requireNotNull(ass).selectionFlags and C.SELECTION_FLAG_FORCED != 0)
        assertNull(ExternalSubtitle.fromProvider("https://cdn.example/image.pgs"))
    }

    @Test
    fun mediaItemKeepsExternalTracksOffUnlessProviderMarksOneDefault() {
        val english = requireNotNull(ExternalSubtitle.fromProvider("https://cdn.example/en.srt", language = "en", label = "English"))
        val spanish = requireNotNull(ExternalSubtitle.fromProvider("https://cdn.example/es.vtt", language = "es", label = "Spanish", isDefault = true))

        val item = buildMediaItem("https://cdn.example/movie.mkv", null, listOf(english, spanish))
        val subtitles = requireNotNull(item.localConfiguration).subtitleConfigurations

        assertEquals(2, subtitles.size)
        assertEquals(0, subtitles[0].selectionFlags)
        assertTrue(subtitles[1].selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
        assertEquals(listOf("English", "Spanish"), subtitles.map { it.label })
    }

    @Test
    fun settingsExposeOnlySupportedSubtitleGroups() {
        val supported = Tracks.Group(
            TrackGroup(
                "supported",
                Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("en").build(),
            ),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
        val unsupported = Tracks.Group(
            TrackGroup(
                "unsupported",
                Format.Builder().setSampleMimeType("application/pgs").setLanguage("es").build(),
            ),
            false,
            intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
            booleanArrayOf(false),
        )
        val audio = Tracks.Group(
            TrackGroup(
                "audio",
                Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage("en").build(),
            ),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )

        val subtitles = supportedTrackGroups(Tracks(listOf(supported, unsupported, audio)), C.TRACK_TYPE_TEXT)

        assertEquals(listOf(supported), subtitles)
        assertFalse(subtitles.single().isSelected)
    }
}
