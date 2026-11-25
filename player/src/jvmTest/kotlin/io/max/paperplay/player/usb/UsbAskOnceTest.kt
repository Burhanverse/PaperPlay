package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbAskOnceTest {
    @Test
    fun aDeviceJustPluggedInIsLeftToAndroid() {
        val a = UsbAskOnce()
        a.attached(1)
        assertFalse(a.mayAsk(1))
        a.detached(1)
        assertTrue(a.mayAsk(1)) // found at the start of the app
    }

    @Test
    fun askedOnceAndAgainOnlyWhenTheOwnerWantsIt() {
        val a = UsbAskOnce()
        assertTrue(a.mayAsk(1))
        assertFalse(a.mayAsk(1))
        a.askAgain()
        assertTrue(a.mayAsk(1))
    }
}
