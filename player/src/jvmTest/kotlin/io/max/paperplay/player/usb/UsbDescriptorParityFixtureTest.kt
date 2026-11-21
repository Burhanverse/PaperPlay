package io.max.paperplay.player.usb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies Kotlin [UsbDescriptorParser] + [UsbTopologyResolver] parity across real and
 * adversarial UAC1/UAC2 descriptors (CX31993 dual-path topology, ClockSelector/ClockMultiplier,
 * capture-vs-playback FeatureUnit disambiguation, and companion async feedback endpoints)
 * before removing native Rust descriptor parsing.
 */
class UsbDescriptorParityFixtureTest {
    private fun fixtureFile(name: String): File {
        val candidates = listOf(
            File("src/commonTest/resources/fixtures/$name"),
            File("player/src/commonTest/resources/fixtures/$name"),
        )
        return candidates.first { it.exists() }
    }

    private fun buildConfigDescriptor(body: List<ByteArray>, ifaceCount: Int): ByteArray {
        val payloadLen = body.sumOf { it.size }
        val totalLen = 9 + payloadLen
        val cfg = byteArrayOf(
            9, 2,
            (totalLen and 0xFF).toByte(),
            ((totalLen ushr 8) and 0xFF).toByte(),
            ifaceCount.toByte(),
            1, 0, 0x80.toByte(), 50,
        )
        return (listOf(cfg) + body).reduce { acc, b -> acc + b }
    }

    @Test
    fun `uac2 binary fixture resolves playback clock, feature unit, and acInterfaceNumber`() {
        val bytes = fixtureFile("uac2_descriptor.bin").readBytes()
        val desc = UsbDescriptorParser.parse(bytes)
        assertTrue(desc.isSupported)
        assertEquals(UacVersion.UAC_2_0, desc.uacVersion)
        assertEquals(0, desc.acInterfaceNumber)
        val playbackAlt = desc.playbackAltSettings.first()
        val clockId = desc.resolvePlaybackClockSourceId(playbackAlt)
        assertNotNull(clockId)
        assertTrue(desc.clockSources.any { it.id == clockId })
    }

    @Test
    fun `uac1 binary fixture resolves playback alts and null uac2 clock`() {
        val bytes = fixtureFile("uac1_descriptor.bin").readBytes()
        val desc = UsbDescriptorParser.parse(bytes)
        assertTrue(desc.isSupported)
        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        val playbackAlt = desc.playbackAltSettings.first()
        assertNull(desc.resolvePlaybackClockSourceId(playbackAlt))
        assertTrue(playbackAlt.sampleRates.isNotEmpty() || playbackAlt.continuousRateRange != null)
    }

    @Test
    fun `cx31993 style dual playback plus mic capture topology skips capture fu and clock`() {
        // Mirrors TTGK CX31993 (dac-dump.txt): playback clock=9, playback FU=2, capture FU=5 (ordered FIRST!), capture clock=10
        val acIface = byteArrayOf(9, 4, 0, 0, 0, 1, 1, 0x20, 0)
        val acHeader = byteArrayOf(9, 0x24, 1, 0x00, 0x02, 0x08, 9, 0, 0)
        // Capture Clock 10 + Playback Clock 9
        val csCapture = byteArrayOf(8, 0x24, 0x0A, 10, 0x03, 0x07, 0, 0)
        val csPlayback = byteArrayOf(8, 0x24, 0x0A, 9, 0x03, 0x07, 0, 0)
        // Mic Input Terminal 4 (0x0201) -> Capture FU 5 -> USB Streaming Out Terminal 6 (0x0101)
        val itMic = byteArrayOf(17, 0x24, 0x02, 4, 0x01, 0x02, 0, 10, 1, 0, 0, 0, 0, 0, 0, 0, 0)
        val fuCapture = byteArrayOf(
            14, 0x24, 0x06, 5, 4,
            0x0F, 0, 0, 0,
            0x0C, 0, 0, 0,
            0,
        )
        val otCaptureUsb = byteArrayOf(12, 0x24, 0x03, 6, 0x01, 0x01, 0, 5, 10, 0, 0, 0)
        // Playback USB Streaming In 1 (0x0101) -> Playback FU 2 -> Headphones Out 3 (0x0302)
        val itUsb = byteArrayOf(17, 0x24, 0x02, 1, 0x01, 0x01, 0, 9, 2, 3, 0, 0, 0, 0, 0, 0, 0)
        val fuPlayback = byteArrayOf(
            18, 0x24, 0x06, 2, 1,
            0x03, 0, 0, 0, // master mute only
            0x0C, 0, 0, 0, // ch1 vol
            0x0C, 0, 0, 0, // ch2 vol
            0,
        )
        val otHeadphones = byteArrayOf(12, 0x24, 0x03, 3, 0x02, 0x03, 0, 2, 9, 0, 0, 0)
        // Streaming iface 1 (alt 0 + alt 1 OUT with async feedback EP 0x81)
        val as1Alt0 = byteArrayOf(9, 4, 1, 0, 0, 1, 2, 0x20, 0)
        val as1Alt1 = byteArrayOf(9, 4, 1, 1, 2, 1, 2, 0x20, 0)
        val as1Gen = byteArrayOf(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
        val as1Fmt = byteArrayOf(6, 0x24, 2, 1, 4, 24)
        val epOut = byteArrayOf(9, 5, 0x01, 0x05, 0x00, 0x04, 1, 0, 0x81.toByte())
        val epFb = byteArrayOf(7, 5, 0x81.toByte(), 0x11, 4, 0, 4)

        val raw = buildConfigDescriptor(
            listOf(
                acIface, acHeader, csCapture, csPlayback,
                itMic, fuCapture, otCaptureUsb,
                itUsb, fuPlayback, otHeadphones,
                as1Alt0, as1Alt1, as1Gen, as1Fmt, epOut, epFb,
            ),
            ifaceCount = 2,
        )
        val desc = UsbDescriptorParser.parse(raw)
        assertEquals(UacVersion.UAC_2_0, desc.uacVersion)
        assertEquals(0, desc.acInterfaceNumber)
        val pbAlt = desc.playbackAltSettings.single()
        assertEquals(9, desc.resolvePlaybackClockSourceId(pbAlt))
        assertEquals(2, desc.resolvePlaybackFeatureUnit(pbAlt)?.id)
        assertEquals(0x81, pbAlt.dataEndpoint?.synchAddress)
        assertEquals(0x81, pbAlt.feedbackEndpoint?.address)
    }

    @Test
    fun `uac2 clock selector and multiplier chain resolves underlying clock source on non-zero acIface`() {
        val acIface = byteArrayOf(9, 4, 3, 0, 0, 1, 1, 0x20, 0) // non-zero AC iface = 3
        val acHeader = byteArrayOf(9, 0x24, 1, 0x00, 0x02, 0x08, 9, 0, 0)
        val cs = byteArrayOf(8, 0x24, 0x0A, 42, 0x03, 0x07, 0, 0)
        val cm = byteArrayOf(7, 0x24, 0x0C, 41, 42, 1, 0)
        val csel = byteArrayOf(7, 0x24, 0x0B, 40, 1, 41, 0)
        val itUsb = byteArrayOf(17, 0x24, 0x02, 1, 0x01, 0x01, 0, 40, 2, 3, 0, 0, 0, 0, 0, 0, 0)
        val otSpk = byteArrayOf(12, 0x24, 0x03, 3, 0x01, 0x03, 0, 1, 40, 0, 0, 0)
        val asAlt1 = byteArrayOf(9, 4, 4, 1, 1, 1, 2, 0x20, 0)
        val asGen = byteArrayOf(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
        val asFmt = byteArrayOf(6, 0x24, 2, 1, 3, 24)
        val epOut = byteArrayOf(7, 5, 0x02, 0x05, 0x80.toByte(), 0x01, 1)

        val raw = buildConfigDescriptor(
            listOf(acIface, acHeader, cs, cm, csel, itUsb, otSpk, asAlt1, asGen, asFmt, epOut),
            ifaceCount = 2,
        )
        val desc = UsbDescriptorParser.parse(raw)
        assertEquals(3, desc.acInterfaceNumber)
        val pbAlt = desc.playbackAltSettings.single()
        assertEquals(42, desc.resolvePlaybackClockSourceId(pbAlt))
    }
}
