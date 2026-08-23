package uk.crownmedia.data.xtream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import uk.crownmedia.core.model.ProviderCredentials

class StreamFormatTest {
    @Test
    fun `prefers direct transport stream when provider allows both formats`() {
        assertEquals("ts", preferredLiveExtension(listOf("ts", "m3u8")))
    }

    @Test
    fun `uses transport stream when it is the only supported format`() {
        assertEquals("ts", preferredLiveExtension(listOf("ts")))
    }

    @Test
    fun `normalizes dotted and mixed-case provider values`() {
        assertEquals("m3u8", preferredLiveExtension(listOf(".M3U8")))
    }

    @Test
    fun `keeps transport stream compatibility for migrated accounts`() {
        assertEquals("ts", preferredLiveExtension(emptyList()))
    }

    @Test
    fun `builds exact Xtream paths for every playable content type`() {
        val credentials = ProviderCredentials("http://provider.test:8880/", "user name", "p@ss/word")
        val client = XtreamClient()

        assertEquals(
            "http://provider.test:8880/live/user%20name/p%40ss%2Fword/11.ts",
            client.streamUrl(credentials, "live", "11", "ts"),
        )
        assertEquals(
            "http://provider.test:8880/movie/user%20name/p%40ss%2Fword/22.mkv",
            client.streamUrl(credentials, "movie", "22", "mkv"),
        )
        assertEquals(
            "http://provider.test:8880/series/user%20name/p%40ss%2Fword/33.mp4",
            client.streamUrl(credentials, "episode", "33", "mp4"),
        )
    }

    @Test
    fun `rejects non-playable kinds rather than silently using the series path`() {
        val credentials = ProviderCredentials("http://provider.test", "user", "pass")
        assertThrows(IllegalArgumentException::class.java) {
            XtreamClient().streamUrl(credentials, "series", "44", null)
        }
    }

    @Test
    fun `falls back from malformed provider extension`() {
        val credentials = ProviderCredentials("http://provider.test", "user", "pass")
        assertEquals(
            "http://provider.test/movie/user/pass/22.mp4",
            XtreamClient().streamUrl(credentials, "movie", "22", "mp4?token=bad"),
        )
    }
}
