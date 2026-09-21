package uk.crownmedia.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks

/** A side-loaded subtitle supplied by the provider for the current media item. */
data class ExternalSubtitle(
    val uri: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
    val selectionFlags: Int = 0,
) {
    companion object {
        fun fromProvider(
            uri: String,
            format: String? = null,
            language: String? = null,
            label: String? = null,
            isDefault: Boolean = false,
            isForced: Boolean = false,
        ): ExternalSubtitle? {
            val mimeType = subtitleMimeType(format, uri) ?: return null
            val flags = (if (isDefault) C.SELECTION_FLAG_DEFAULT else 0) or
                (if (isForced) C.SELECTION_FLAG_FORCED else 0)
            return ExternalSubtitle(
                uri = uri,
                mimeType = mimeType,
                language = language?.takeIf(String::isNotBlank),
                label = label?.takeIf(String::isNotBlank),
                selectionFlags = flags,
            )
        }
    }
}

internal fun buildMediaItem(
    url: String,
    mimeType: String?,
    externalSubtitles: List<ExternalSubtitle>,
): MediaItem = MediaItem.Builder()
    .setUri(url)
    .apply {
        if (mimeType != null) setMimeType(mimeType)
        if (externalSubtitles.isNotEmpty()) {
            setSubtitleConfigurations(externalSubtitles.distinctBy { subtitle ->
                listOf(subtitle.uri, subtitle.language, subtitle.label).joinToString("|")
            }.mapIndexed { index, subtitle ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.uri))
                    .setId("provider-subtitle-$index")
                    .setMimeType(subtitle.mimeType)
                    .setSelectionFlags(subtitle.selectionFlags)
                    .apply {
                        subtitle.language?.let(::setLanguage)
                        subtitle.label?.let(::setLabel)
                    }
                    .build()
            })
        }
    }
    .build()

internal fun supportedTrackGroups(tracks: Tracks, trackType: Int): List<Tracks.Group> =
    tracks.groups.filter { group ->
        group.type == trackType && (0 until group.length).any(group::isTrackSupported)
    }

private fun subtitleMimeType(format: String?, uri: String): String? {
    val normalized = format.orEmpty().trim().lowercase()
        .substringBefore(';')
        .substringAfterLast('/')
        .trimStart('.')
    val extension = Uri.parse(uri).lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
    return when (normalized.ifBlank { extension }) {
        "srt", "subrip", "x-subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt", "text-vtt" -> MimeTypes.TEXT_VTT
        "ttml", "dfxp", "xml", "ttml+xml" -> MimeTypes.APPLICATION_TTML
        "ssa", "ass", "x-ssa" -> MimeTypes.TEXT_SSA
        else -> null
    }
}
