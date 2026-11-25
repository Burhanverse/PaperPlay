package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UsbFormatNegotiatorTest {

    private fun loadFixture(name: String): ByteArray {
        val stream = UsbFormatNegotiatorTest::class.java.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("Fixture fixtures/$name not found")
        return stream.readBytes()
    }

    @Test
    fun picksExactUac1Matches() {
        val uac1Bytes = loadFixture("uac1_descriptor.bin")
        val desc = UsbDescriptorParser.parse(uac1Bytes)

        // 1. 44.1 kHz / 16-bit / 2 channels -> Alt 1 (16-bit)
        val res1 = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 44100, 16, 2)
        assertIs<FormatSelectionResult.Success>(res1)
        assertEquals(1, res1.candidate.altSetting)
        assertEquals(16, res1.candidate.bitResolution)

        // 2. 96 kHz / 24-bit / 2 channels -> Alt 2 (24-bit)
        val res2 = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 96000, 24, 2)
        assertIs<FormatSelectionResult.Success>(res2)
        assertEquals(2, res2.candidate.altSetting)
        assertEquals(24, res2.candidate.bitResolution)
    }

    @Test
    fun returnsDescriptiveFailureWhenNoFormatFits() {
        val uac1Bytes = loadFixture("uac1_descriptor.bin")
        val desc = UsbDescriptorParser.parse(uac1Bytes)

        // Unsupported sample rate (192 kHz)
        val resRate = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 192000, 16, 2)
        assertIs<FormatSelectionResult.Failure>(resRate)
        assertTrue(resRate.reason.contains("192000 Hz"))

        // Unsupported channel count (6 channels)
        val resCh = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 44100, 16, 6)
        assertIs<FormatSelectionResult.Failure>(resCh)
        assertTrue(resCh.reason.contains("6 channels"))

        // Unsupported bit depth (32-bit on device that supports max 24-bit)
        val resDepth = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 44100, 32, 2)
        assertIs<FormatSelectionResult.Failure>(resDepth)
        assertTrue(resDepth.reason.contains("32"))
    }

    @Test
    fun ranksUac2CandidatesByResolutionAndSyncType() {
        val uac2Bytes = loadFixture("uac2_descriptor.bin")
        val desc = UsbDescriptorParser.parse(uac2Bytes)

        // For a 24-bit source, Alt 2 (24-bit resolution in 3-byte subslot)
        // should be preferred over Alt 1 (32-bit resolution in 4-byte subslot)
        val res24 = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 96000, 24, 2)
        assertIs<FormatSelectionResult.Success>(res24)
        assertEquals(2, res24.candidate.altSetting)
        assertEquals(24, res24.candidate.bitResolution)

        // For a 32-bit source, only Alt 1 fits
        val res32 = UsbFormatNegotiator.pickBestFormat(desc.altSettings, 96000, 32, 2)
        assertIs<FormatSelectionResult.Success>(res32)
        assertEquals(1, res32.candidate.altSetting)
        assertEquals(32, res32.candidate.bitResolution)
    }

    @Test
    fun handlesEmptyCandidateList() {
        val res = UsbFormatNegotiator.pickBestFormat(emptyList(), 44100, 16, 2)
        assertIs<FormatSelectionResult.Failure>(res)
        assertTrue(res.reason.contains("No audio streaming"))
    }

    @Test
    fun decoderFloatGoesToThe24BitSettingOfADacWithout32Bit() {
        val alts = UsbDescriptorParser.parse(loadFixture("uac1_descriptor.bin")).altSettings
        // The DAC stops at 24 bit, so asking for 32 finds nothing and the music went to Android's mixer.
        assertIs<FormatSelectionResult.Failure>(UsbFormatNegotiator.pickBestFormat(alts, 96000, 32, 2))
        for (isFloat in listOf(true, false)) {
            val r = UsbFormatNegotiator.pickForSource(alts, 96000, 32, isFloat, 2)
            assertIs<FormatSelectionResult.Success>(r)
            assertEquals(24, r.candidate.bitResolution)
        }
        // Integers keep their depth; a 16 bit only DAC takes float.
        val only16 = alts.filter { it.bitResolution == 16 }
        assertIs<FormatSelectionResult.Failure>(UsbFormatNegotiator.pickForSource(only16, 44100, 24, false, 2))
        assertIs<FormatSelectionResult.Success>(UsbFormatNegotiator.pickForSource(only16, 44100, 32, true, 2))
    }

    @Test
    fun supportsContinuousRateRangesAndPrefersDedicatedDiscreteAlt() {
        val ep = UsbEndpointDescriptor(
            address = 0x01,
            isOut = true,
            syncType = EndpointSyncType.ADAPTIVE,
            usageType = EndpointUsageType.DATA,
            maxPacketSize = 576,
            interval = 1,
            csBmAttributes = 0x01,
        )
        val altContinuous = UsbAudioAltSetting(
            interfaceNumber = 1,
            altSetting = 1,
            terminalLink = 1,
            formatTag = AudioFormatTag.PCM,
            bmFormats = 1L,
            channelCount = 2,
            subslotSize = 3,
            bitResolution = 24,
            sampleRates = emptyList(),
            continuousRateRange = Pair(8000, 96000),
            dataEndpoint = ep,
            isDsdRawCapable = false,
            isDsdDoPCapable = false,
        )
        val altDedicated96k = altContinuous.copy(
            altSetting = 2,
            sampleRates = listOf(96000),
            continuousRateRange = null,
            dataEndpoint = ep.copy(csBmAttributes = 0x00),
        )
        val candidates = listOf(altContinuous, altDedicated96k)

        // 96 kHz matches both; dedicated single discrete alt 2 is preferred over continuous alt 1
        val pick96 = UsbFormatNegotiator.pickBestFormat(candidates, 96000, 24, 2)
        assertIs<FormatSelectionResult.Success>(pick96)
        assertEquals(2, pick96.candidate.altSetting)

        // 88.2 kHz matches only continuous alt 1 (8000..96000 Hz)
        val pick88 = UsbFormatNegotiator.pickBestFormat(candidates, 88200, 24, 2)
        assertIs<FormatSelectionResult.Success>(pick88)
        assertEquals(1, pick88.candidate.altSetting)

        // 192 kHz exceeds both -> Failure message lists both discrete and continuous range
        val pick192 = UsbFormatNegotiator.pickBestFormat(candidates, 192000, 24, 2)
        assertIs<FormatSelectionResult.Failure>(pick192)
        assertTrue(pick192.reason.contains("96000"))
        assertTrue(pick192.reason.contains("8000..96000"))
    }
}

