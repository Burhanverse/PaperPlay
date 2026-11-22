package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.BitPerfectRouter
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.OutputRoute
import io.max.paperplay.player.PcmFormat
import io.max.paperplay.player.RouteInput
import io.max.paperplay.player.SampleEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitPerfectRouterTest {
    private val pcm16 = PcmFormat(44100, 16, 16, 16, SampleEncoding.PCM_INT, 2)
    private val wire32 = PcmFormat(44100, 16, 24, 32, SampleEncoding.PCM_INT, 2)

    @Test
    fun `speed other than 1x bypasses to normal and returns seamlessly at 1x`() {
        val fast = BitPerfectRouter.decide(
            RouteInput(
                mode = BitPerfectMode.ON,
                speed = 1.25f,
                usbDacAttached = true,
                usbPermissionGranted = true,
                dacReady = true,
                sourceFormat = pcm16,
                verifiedOutputFormat = wire32,
            )
        )
        assertEquals(OutputRoute.Normal, fast.route)
        assertFalse(fast.outputBitPerfect)
        assertTrue(fast.status is BitPerfectStatus.Bypassed)
        assertEquals("Not bit-perfect: speed 1.25x", fast.note)

        val normalAgain = BitPerfectRouter.decide(
            RouteInput(
                mode = BitPerfectMode.ON,
                speed = 1.0f,
                usbDacAttached = true,
                usbPermissionGranted = true,
                dacReady = true,
                sourceFormat = pcm16,
                verifiedOutputFormat = wire32,
            )
        )
        assertEquals(OutputRoute.UsbUacDirect, normalAgain.route)
        assertTrue(normalAgain.outputBitPerfect)
        assertTrue(normalAgain.status is BitPerfectStatus.Active)
    }

    @Test
    fun `DoP hard stops instead of falling back when mode off, speed changed, or non-USB route active`() {
        val dop = PcmFormat(176400, 24, 24, 32, SampleEncoding.DOP_24, 2)

        for (input in listOf(
            RouteInput(mode = BitPerfectMode.OFF, isDoP = true, sourceFormat = dop),
            RouteInput(mode = BitPerfectMode.ON, speed = 1.5f, usbDacAttached = true, usbPermissionGranted = true, dacReady = true, isDoP = true, sourceFormat = dop),
            RouteInput(mode = BitPerfectMode.ON, usbDacAttached = true, nonUsbRouteActive = true, isDoP = true, sourceFormat = dop),
            RouteInput(mode = BitPerfectMode.ON, usbDacAttached = false, isDoP = true, sourceFormat = dop),
        )) {
            val d = BitPerfectRouter.decide(input)
            assertTrue(d.route is OutputRoute.HardStop, "Expected HardStop for $input, got ${d.route}")
            assertFalse(d.allowPlayback)
            assertFalse(d.outputBitPerfect)
        }
    }

    @Test
    fun `API 34 mixer only selected when dev flag enabled and direct USB not attached`() {
        val pcm24 = PcmFormat(96000, 24, 24, 32, SampleEncoding.PCM_INT, 2)
        val mixerDecision = BitPerfectRouter.decide(
            RouteInput(
                mode = BitPerfectMode.ON,
                usbDacAttached = false,
                api34MixerEnabled = true,
                api34MixerAvailable = true,
                sourceFormat = pcm24,
                verifiedOutputFormat = pcm24,
            )
        )
        assertEquals(OutputRoute.Api34BitPerfectMixer, mixerDecision.route)
        assertTrue(mixerDecision.outputBitPerfect)
    }
}
