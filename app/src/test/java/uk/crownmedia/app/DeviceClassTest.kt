package uk.crownmedia.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClassTest {
    @Test
    fun startupLayoutKeepsMobileAndTelevisionResourcesSeparate() {
        assertEquals(R.layout.activity_main, DeviceClass.PHONE.startupLayoutResource())
        assertEquals(R.layout.activity_main, DeviceClass.TABLET.startupLayoutResource())
        assertEquals(R.layout.activity_main_television, DeviceClass.TELEVISION.startupLayoutResource())
    }
}
