package uk.crownmedia.player

import org.json.JSONArray
import org.json.JSONObject

/** One provider episode in an internal-player continuation queue. */
data class PlaybackQueueItem(
    val url: String,
    val title: String,
    val streamId: String,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
)

internal fun encodePlaybackQueue(items: List<PlaybackQueueItem>): String = JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().apply {
            put("url", item.url)
            put("title", item.title)
            put("streamId", item.streamId)
            put("subtitles", JSONArray().apply {
                item.externalSubtitles.forEach { subtitle ->
                    put(JSONObject().apply {
                        put("uri", subtitle.uri)
                        put("mimeType", subtitle.mimeType)
                        put("language", subtitle.language ?: JSONObject.NULL)
                        put("label", subtitle.label ?: JSONObject.NULL)
                        put("selectionFlags", subtitle.selectionFlags)
                    })
                }
            })
        })
    }
}.toString()

internal fun decodePlaybackQueue(value: String?): List<PlaybackQueueItem> = runCatching {
    val array = JSONArray(value.orEmpty())
    buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val url = item.optString("url").trim()
            val streamId = item.optString("streamId").trim()
            if (url.isBlank() || streamId.isBlank()) continue
            val subtitles = item.optJSONArray("subtitles") ?: JSONArray()
            add(
                PlaybackQueueItem(
                    url = url,
                    title = item.optString("title"),
                    streamId = streamId,
                    externalSubtitles = buildList {
                        for (subtitleIndex in 0 until subtitles.length()) {
                            val subtitle = subtitles.optJSONObject(subtitleIndex) ?: continue
                            val uri = subtitle.optString("uri").trim()
                            val mimeType = subtitle.optString("mimeType").trim()
                            if (uri.isBlank() || mimeType.isBlank()) continue
                            add(
                                ExternalSubtitle(
                                    uri = uri,
                                    mimeType = mimeType,
                                    language = subtitle.optionalString("language"),
                                    label = subtitle.optionalString("label"),
                                    selectionFlags = subtitle.optInt("selectionFlags"),
                                ),
                            )
                        }
                    },
                ),
            )
        }
    }
}.getOrDefault(emptyList())

private fun JSONObject.optionalString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
