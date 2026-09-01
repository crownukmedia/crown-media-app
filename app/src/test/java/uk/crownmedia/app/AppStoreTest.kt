package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.crownmedia.core.model.ProviderCredentials

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppStoreTest {
    private val credentials = ProviderCredentials("http://novixa.uk:8880", "user", "secret")

    @Test
    fun uncheckedLoginExistsOnlyForTheCurrentSession() {
        val secureStore = FakeSecureStore()
        val currentSession = AppStore(secureStore)
        currentSession.save("Crown Premium", credentials, null, "ACTIVE", null, null, persist = false)

        assertNotNull(currentSession.selected())
        assertNull(AppStore(secureStore).selected())
        assertEquals(emptyList<SavedPlaylist>(), AppStore(secureStore).playlists())
    }

    @Test
    fun checkedLoginSurvivesStoreRecreation() {
        val secureStore = FakeSecureStore()
        AppStore(secureStore).save(
            "Crown Premium", credentials, null, "ACTIVE", null, null,
            serverTimezone = "Europe/London",
            persist = true,
        )

        val restored = AppStore(secureStore).selected()
        assertEquals("user", restored?.credentials?.username)
        assertEquals("secret", restored?.credentials?.password)
        assertEquals("Europe/London", restored?.serverTimezone)
    }

    @Test
    fun removingTransientPlaylistRestoresPersistedPlaylist() {
        val secureStore = FakeSecureStore()
        val appStore = AppStore(secureStore)
        val persisted = appStore.save("Saved", credentials, null, "ACTIVE", null, null, persist = true)
        val transient = appStore.save("Session", credentials, null, "ACTIVE", null, null, persist = false)

        appStore.remove(transient.id)

        assertEquals(persisted.id, appStore.selected()?.id)
    }

    @Test
    fun catalogCompletionAndFreshnessSurviveStoreRecreation() {
        val secureStore = FakeSecureStore()
        AppStore(secureStore).markCatalogRefreshed("p", "movie", null, 1234L)

        val restored = AppStore(secureStore)
        assertEquals(true, restored.catalogComplete("p", "movie", null))
        assertEquals(1234L, restored.catalogRefreshAt("p", "movie", null))
    }

    @Test
    fun savedCredentialsAreIsolatedByCrownService() {
        val secureStore = FakeSecureStore()
        val store = AppStore(secureStore)
        store.saveLoginDetails(
            CrownService.PREMIUM,
            SavedLoginDetails("Premium", CrownService.PREMIUM, "premium-user", "premium-pass"),
        )
        store.saveLoginDetails(
            CrownService.PRO,
            SavedLoginDetails("Pro", CrownService.PRO, "pro-user", "pro-pass"),
        )

        assertEquals("premium-user", store.savedLoginDetails(CrownService.PREMIUM)?.username)
        assertEquals("pro-user", store.savedLoginDetails(CrownService.PRO)?.username)
        assertNull(store.savedLoginDetails(CrownService.EIGHT_K))
    }

    private class FakeSecureStore : CrownSecureStore {
        private val strings = mutableMapOf<String, String?>()
        private val sets = mutableMapOf<String, Set<String>>()

        override fun getString(key: String, fallback: String?): String? =
            if (strings.containsKey(key)) strings[key] else fallback

        override fun putString(key: String, value: String?) { strings[key] = value }
        override fun getStringSet(key: String): Set<String> = sets[key].orEmpty()
        override fun putStringSet(key: String, value: Set<String>) { sets[key] = value }
    }
}
