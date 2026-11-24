package io.max.paperplay.ui.app

import io.max.paperplay.player.BitPerfectStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerUiTest {
    @Test
    fun formatSignalPathSpecFormatsRatesAndBitDepths() {
        assertEquals("96 kHz / 24-bit", formatSignalPathSpec(96000, 24))
        assertEquals("44.1 kHz / 16-bit", formatSignalPathSpec(44100, 16))
        assertEquals("48 kHz / PCM", formatSignalPathSpec(48000, 0))
        assertEquals("Native rate / 32-bit", formatSignalPathSpec(0, 32))
    }

    @Test
    fun bitPerfectBypassRespectsPhoneUsbEqAllowance() {
        assertFalse(isBitPerfectBypassingEq(bitPerfect = false, outputBitPerfect = false, isPhone = true, allowEqInUsb = false))
        assertFalse(isBitPerfectBypassingEq(bitPerfect = true, outputBitPerfect = true, isPhone = true, allowEqInUsb = true))
        assertTrue(isBitPerfectBypassingEq(bitPerfect = true, outputBitPerfect = false, isPhone = true, allowEqInUsb = false))
        assertTrue(isBitPerfectBypassingEq(bitPerfect = true, outputBitPerfect = false, isPhone = false, allowEqInUsb = true))
        assertTrue(isBitPerfectBypassingEq(bitPerfect = false, outputBitPerfect = true, isPhone = false, allowEqInUsb = false))
    }

    @Test
    fun resolveEqSignalBadgeCoversAllSignalPaths() {
        val directEq = resolveEqSignalBadge(
            bitPerfectStatus = BitPerfectStatus.DirectWithEq("USB Direct", 96000, 32),
            eqOn = true,
            bitPerfectBypass = false,
            sampleRate = 96000,
            bitDepth = 32,
        )
        assertEquals("USB Direct + EQ · 96 kHz / 32-bit", directEq.title)
        assertEquals(EqSignalTone.Primary, directEq.tone)

        val usbBp = resolveEqSignalBadge(
            bitPerfectStatus = BitPerfectStatus.Active("USB Direct", 192000, 24),
            eqOn = true,
            bitPerfectBypass = true,
            sampleRate = 192000,
            bitDepth = 24,
        )
        assertEquals("USB Bit-Perfect · 192 kHz / 24-bit", usbBp.title)
        assertEquals(EqSignalTone.Secondary, usbBp.tone)

        val pcmActive = resolveEqSignalBadge(
            bitPerfectStatus = BitPerfectStatus.Off,
            eqOn = true,
            bitPerfectBypass = false,
            sampleRate = 48000,
            bitDepth = 16,
        )
        assertEquals("48 kHz / 16-bit · EQ Active", pcmActive.title)
        assertEquals(EqSignalTone.Primary, pcmActive.tone)

        val pcmBypassed = resolveEqSignalBadge(
            bitPerfectStatus = BitPerfectStatus.Off,
            eqOn = false,
            bitPerfectBypass = false,
            sampleRate = 48000,
            bitDepth = 16,
        )
        assertEquals("48 kHz / 16-bit · Bypassed", pcmBypassed.title)
        assertEquals(EqSignalTone.Muted, pcmBypassed.tone)
    }

    @Test
    fun formattingAndBuiltInPresetsAreConsistent() {
        assertEquals("1 kHz", fmtHz(1000f))
        assertEquals("2.5 kHz", fmtHz(2500f))
        assertEquals("80 Hz", fmtHz(80f))
        assertEquals("+6.5", fmtDb(6.5f))
        assertEquals("-3", fmtDb(-3f))
        assertEquals("0", fmtDb(0f))
        assertEquals("1.41", fmtQ(1.41f))
        assertEquals("1000", fmtRawNum(1000f))
        assertTrue(builtInParametricPresets.size >= 5)
        assertTrue(builtInParametricPresets.all { it.bands.isNotEmpty() })
    }
}
