package uk.crownmedia.data.xtream

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.crownmedia.core.model.ProviderCredentials
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogStreamingTest {
    @Test
    fun largeCatalogParserEmitsBoundedBatchesAndSkipsUnknownFields() = runBlocking {
        val json = """[
            {"stream_id":1,"category_id":"10","name":"One","stream_icon":"https://img/1.jpg","rating":"7.5","added":"100","tv_archive":1,"tv_archive_duration":"2","is_adult":1,"num":42,"category_ids":[10]},
            {"stream_id":"2","category_id":10,"name":"Two","stream_icon":null,"epg_channel_id":"epg.two","extra":{"large":"ignored"}},
            {"stream_id":3,"category_id":"11","name":"Three","container_extension":"mkv"}
        ]""".trimIndent()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val client = XtreamClient(http)

        val batches = client.catalogBatches(
            ProviderCredentials("http://example.test", "user", "pass"),
            kind = "movie",
            batchSize = 2,
        ).toList()

        assertEquals(listOf(2, 1), batches.map(List<*>::size))
        assertEquals("One", batches[0][0].name)
        assertTrue(batches[0][0].catchUp)
        assertTrue(batches[0][0].isAdult)
        assertEquals(42, batches[0][0].providerOrder)
        assertEquals("epg.two", batches[0][1].epgChannelId)
        assertEquals("mkv", batches[1][0].extension)
    }

    @Test
    fun catchUpGuideUsesProviderArchiveEndpointAndParsesProgrammes() = runBlocking {
        var requestedUrl = ""
        val title = Base64.getEncoder().encodeToString("Archived Show".toByteArray())
        val json = """{"epg_listings":[{"title":"$title","description":"","start":"2026-09-06 10:00:00","end":"2026-09-06 11:00:00","start_timestamp":"1788688800","stop_timestamp":"1788692400"}]}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()

        val programmes = XtreamClient(http).catchUpEpg(
            ProviderCredentials("http://example.test", "user", "pass"),
            "42",
        )

        assertTrue(requestedUrl.contains("action=get_simple_data_table"))
        assertTrue(requestedUrl.contains("stream_id=42"))
        assertEquals("Archived Show", programmes.single().title)
        assertEquals(1788688800L, programmes.single().startTimestamp)
    }
}
