package uk.crownmedia.data.xtream

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
class MetadataMappingTest {
    private val credentials = ProviderCredentials("http://example.test", "user", "pass")

    @Test fun movieMapsProviderBackdropLocaleAndStructuredCast() = runBlocking {
        val client = clientFor("""{"info":{"name":"Film","plot":"Story","release_date":"2025-03-01","language":"en","country":"UK","backdrop_path":["https://img/back.jpg"],"cast":[{"name":"Actor One","profile_path":"https://img/a.jpg"}],"tmdb_id":42},"movie_data":{"container_extension":"mkv"}}""")
        val movie = client.movieInfo(credentials, "7")
        assertEquals("https://img/back.jpg", movie.backdropUrl)
        assertEquals("2025-03-01", movie.releaseDate)
        assertEquals("Actor One", movie.castMembers.single().name)
        assertEquals("https://img/a.jpg", movie.castMembers.single().imageUrl)
        assertEquals("42", movie.tmdbId)
    }

    @Test fun seriesMapsProviderDetailsAndEpisodeMetadata() = runBlocking {
        val client = clientFor("""{"info":{"name":"Show","director":"Director","original_language":"fr","origin_country":"FR","backdrop_path":["https://img/b.jpg"],"cast":["Actor A","Actor B"]},"episodes":{"1":[{"id":9,"title":"Pilot","episode_num":1,"container_extension":"mp4","info":{"rating":"8.1","air_date":"2025-01-02"}}]}}""")
        val series = client.seriesInfo(credentials, "8")
        assertEquals("Director", series.director)
        assertEquals("fr", series.language)
        assertEquals(listOf("Actor A", "Actor B"), series.castMembers.map { it.name })
        assertEquals("8.1", series.episodes.getValue(1).single().rating)
    }

    @Test fun epgMapsArtworkWithoutChangingScheduleFields() = runBlocking {
        val title = Base64.getEncoder().encodeToString("Now".toByteArray())
        val description = Base64.getEncoder().encodeToString("Details".toByteArray())
        val client = clientFor("""{"epg_listings":[{"title":"$title","description":"$description","start_timestamp":100,"stop_timestamp":200,"programme_icon":"https://img/epg.jpg"}]}""")
        val programme = client.shortEpg(credentials, "9").single()
        assertEquals("https://img/epg.jpg", programme.imageUrl)
        assertTrue(programme.title.contains("Now"))
    }

    private fun clientFor(json: String): XtreamClient = XtreamClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build(),
    )
}
