package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbStreamMatchTest {
    private fun same(rate: Int = 44100, channels: Int = 2, encoding: Int = 2, mime: String? = "audio/raw") =
        sameUsbStream(44100, 2, 2, "audio/raw", rate, channels, encoding, mime)

    @Test
    fun onlyTheVerySameStreamGoesOn() {
        assertTrue(same())
        assertFalse(same(rate = 48000))
        assertFalse(same(channels = 1))
        assertFalse(same(encoding = 536870912))
        assertFalse(same(mime = "audio/dop"))
        assertFalse(sameUsbStream(-1, -1, 2, null, -1, -1, 2, null))
    }
}
