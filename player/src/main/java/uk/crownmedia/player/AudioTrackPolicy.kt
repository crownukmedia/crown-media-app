package uk.crownmedia.player

import androidx.media3.common.C

internal data class AudioTrackCandidate(
    val groupIndex: Int,
    val trackIndex: Int,
    val supported: Boolean,
    val selected: Boolean,
    val selectionFlags: Int,
    val language: String?,
)

/** Keeps audio selection deterministic without inventing a track when the device cannot decode it. */
internal object AudioTrackPolicy {
    fun preferred(candidates: List<AudioTrackCandidate>, deviceLanguage: String?): AudioTrackCandidate? {
        val supported = candidates.filter(AudioTrackCandidate::supported)
        return supported.firstOrNull(AudioTrackCandidate::selected)
            ?: supported.firstOrNull { it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
            ?: supported.firstOrNull { candidate ->
                val candidateLanguage = candidate.language?.substringBefore('-')
                val preferredLanguage = deviceLanguage?.substringBefore('-')
                !candidateLanguage.isNullOrBlank() && candidateLanguage.equals(preferredLanguage, ignoreCase = true)
            }
            ?: supported.firstOrNull()
    }
}
