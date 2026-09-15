package uk.crownmedia.app

internal data class LivePreviewVisibility(
    val adapterPosition: Int,
    val visibleFraction: Float,
    val centerDistancePx: Int,
)

/** Chooses one stable, substantially visible card; ties prefer viewport center then list order. */
internal fun primaryLivePreviewPosition(
    candidates: List<LivePreviewVisibility>,
    minimumVisibleFraction: Float = 0.60f,
): Int? = candidates
    .asSequence()
    .filter { it.visibleFraction >= minimumVisibleFraction }
    .sortedWith(
        compareByDescending<LivePreviewVisibility> { it.visibleFraction }
            .thenBy { it.centerDistancePx }
            .thenBy { it.adapterPosition },
    )
    .firstOrNull()
    ?.adapterPosition
