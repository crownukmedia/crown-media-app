package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownServiceTest {
    @Test
    fun premiumMapsToTheConfiguredEndpoint() {
        assertEquals("http://novixa.uk:8880", CrownService.PREMIUM.serverUrl)
        assertTrue(CrownService.PREMIUM.isAvailable)
    }

    @Test
    fun futureServicesRemainVisibleButUnavailable() {
        assertEquals(listOf("Crown Premium", "Crown Pro", "Crown 8K"), CrownService.displayNames)
        assertFalse(CrownService.PRO.isAvailable)
        assertFalse(CrownService.EIGHT_K.isAvailable)
        assertNull(CrownService.PRO.serverUrl)
    }

    @Test
    fun unknownStoredServiceFallsBackSafely() {
        assertEquals(CrownService.PREMIUM, CrownService.fromStoredValue("REMOVED_SERVICE"))
        assertEquals(CrownService.PRO, CrownService.fromDisplayName("Crown Pro"))
    }
}
