package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownServiceTest {
    @Test
    fun premiumMapsToTheConfiguredEndpoint() {
        assertEquals("http://novixa.uk:8880", CrownService.PREMIUM.serverUrl)
        assertTrue(CrownService.PREMIUM.isAvailable)
    }

    @Test
    fun allCrownServicesMapToTheirProviderEndpoints() {
        assertEquals(listOf("Crown Premium", "Crown Pro", "Crown 8K"), CrownService.displayNames)
        assertEquals("http://slytv.uk", CrownService.PRO.serverUrl)
        assertEquals("http://pro.ultrastreamtvpro.uk", CrownService.EIGHT_K.serverUrl)
        assertTrue(CrownService.PRO.isAvailable)
        assertTrue(CrownService.EIGHT_K.isAvailable)
    }

    @Test
    fun unknownStoredServiceFallsBackSafely() {
        assertEquals(CrownService.PREMIUM, CrownService.fromStoredValue("REMOVED_SERVICE"))
        assertEquals(CrownService.PRO, CrownService.fromDisplayName("Crown Pro"))
    }
}
