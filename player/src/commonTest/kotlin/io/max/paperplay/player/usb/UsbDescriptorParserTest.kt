package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbDescriptorParserTest {

    private fun loadFixture(name: String): ByteArray {
        val stream = UsbDescriptorParserTest::class.java.classLoader?.getResourceAsStream("fixtures/$name")
            ?: error("Fixture fixtures/$name not found")
        return stream.readBytes()
    }

    @Test
    fun parsesUac2DescriptorCorrectly() {
        val bytes = loadFixture("uac2_descriptor.bin")
        val desc = UsbDescriptorParser.parse(bytes)

        assertEquals(UacVersion.UAC_2_0, desc.uacVersion)
        assertTrue(desc.isSupported)
        assertTrue(desc.hasHardwareVolume)
        assertTrue(desc.rawDsdCapable)
        assertTrue(desc.doPCapable)

        // Clock source 41
        assertEquals(1, desc.clockSources.size)
        val clock = desc.clockSources.first()
        assertEquals(41, clock.id)
        assertTrue(clock.isProgrammable)
        assertTrue(clock.supportsFrequencySet)
        assertTrue(clock.supportsFrequencyGet)
        assertTrue(clock.supportsClockValid)

        // Terminals
        assertEquals(1, desc.inputTerminals.size)
        val inTerm = desc.inputTerminals.first()
        assertEquals(1, inTerm.id)
        assertTrue(inTerm.isUsbStreaming)
        assertEquals(41, inTerm.clockSourceId)
        assertEquals(2, inTerm.channelCount)

        assertEquals(1, desc.outputTerminals.size)
        val outTerm = desc.outputTerminals.first()
        assertEquals(20, outTerm.id)
        assertTrue(outTerm.isPhysicalOutput)

        // Feature Unit
        assertEquals(1, desc.featureUnits.size)
        val fu = desc.featureUnits.first()
        assertEquals(10, fu.id)
        assertTrue(fu.hasMasterMute)
        assertTrue(fu.hasMasterVolume)

        // Alternate settings
        assertEquals(2, desc.altSettings.size)

        // Alt 1: 32-bit, PCM + RAW_DATA
        val alt1 = desc.altSettings.first { it.altSetting == 1 }
        assertEquals(1, alt1.interfaceNumber)
        assertEquals(4, alt1.subslotSize)
        assertEquals(32, alt1.bitResolution)
        assertEquals(2, alt1.channelCount)
        assertTrue(alt1.isDsdRawCapable)
        assertNotNull(alt1.dataEndpoint)
        assertEquals(0x01, alt1.dataEndpoint?.address)
        assertTrue(alt1.dataEndpoint!!.isOut)
        assertEquals(EndpointSyncType.ASYNCHRONOUS, alt1.dataEndpoint?.syncType)
        assertNotNull(alt1.feedbackEndpoint)
        assertEquals(0x81, alt1.feedbackEndpoint?.address)
        assertEquals(EndpointUsageType.FEEDBACK, alt1.feedbackEndpoint?.usageType)

        // Alt 2: 24-bit, DoP capable
        val alt2 = desc.altSettings.first { it.altSetting == 2 }
        assertEquals(3, alt2.subslotSize)
        assertEquals(24, alt2.bitResolution)
        assertTrue(alt2.isDsdDoPCapable)
        assertFalse(alt2.isDsdRawCapable)
    }

    @Test
    fun parsesUac1DescriptorCorrectly() {
        val bytes = loadFixture("uac1_descriptor.bin")
        val desc = UsbDescriptorParser.parse(bytes)

        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        assertTrue(desc.isSupported)
        assertTrue(desc.hasHardwareVolume)
        assertFalse(desc.rawDsdCapable)
        assertTrue(desc.doPCapable) // Alt 2 has 24-bit and 176.4 kHz

        // Terminals
        assertEquals(1, desc.inputTerminals.size)
        assertTrue(desc.inputTerminals.first().isUsbStreaming)
        assertEquals(1, desc.outputTerminals.size)
        assertTrue(desc.outputTerminals.first().isPhysicalOutput)

        // Feature Unit
        assertEquals(1, desc.featureUnits.size)
        assertTrue(desc.featureUnits.first().hasMasterVolume)
        assertTrue(desc.featureUnits.first().hasMasterMute)

        // Alt settings
        assertEquals(2, desc.altSettings.size)

        // Alt 1: 16-bit 44.1k and 48k
        val alt1 = desc.altSettings.first { it.altSetting == 1 }
        assertEquals(2, alt1.subslotSize)
        assertEquals(16, alt1.bitResolution)
        assertEquals(listOf(44100, 48000), alt1.sampleRates)
        assertEquals(EndpointSyncType.ADAPTIVE, alt1.dataEndpoint?.syncType)
        assertFalse(alt1.isDsdDoPCapable)

        // Alt 2: 24-bit 44.1k, 48k, 96k, 176.4k
        val alt2 = desc.altSettings.first { it.altSetting == 2 }
        assertEquals(3, alt2.subslotSize)
        assertEquals(24, alt2.bitResolution)
        assertEquals(listOf(44100, 48000, 96000, 176400), alt2.sampleRates)
        assertTrue(alt2.isDsdDoPCapable)
    }

    @Test
    fun handlesTruncatedDescriptorsWithoutCrashing() {
        val full = loadFixture("uac2_descriptor.bin")
        for (cutLength in listOf(1, 2, 8, 9, 15, 30, 45, 60, 90, 120, 150)) {
            val truncated = full.copyOf(cutLength)
            val desc = UsbDescriptorParser.parse(truncated)
            assertNotNull(desc)
        }
    }

    @Test
    fun handlesMalformedDescriptorsWithoutCrashing() {
        // Zero length byte: must stop without infinite loop
        val zeroLen = byteArrayOf(0x00, 0x04, 0x01, 0x00)
        val desc1 = UsbDescriptorParser.parse(zeroLen)
        assertFalse(desc1.isSupported)

        // Length byte greater than array size
        val overflowLen = byteArrayOf(0x50, 0x04, 0x01, 0x00)
        val desc2 = UsbDescriptorParser.parse(overflowLen)
        assertFalse(desc2.isSupported)

        // Corrupted 0xFF bytes
        val garbage = ByteArray(64) { 0xFF.toByte() }
        val desc3 = UsbDescriptorParser.parse(garbage)
        assertFalse(desc3.isSupported)

        // Null and empty
        assertFalse(UsbDescriptorParser.parse(null).isSupported)
        assertFalse(UsbDescriptorParser.parse(ByteArray(0)).isSupported)
    }

    @Test
    fun detectsUac3AsUnsupportedCleanly() {
        val uac3Iad = byteArrayOf(
            0x09, 0x02, 0x11, 0x00, 0x01, 0x01, 0x00, 0xC0.toByte(), 0xFA.toByte(), // Config
            0x08, 0x0B, 0x00, 0x01, 0x01, 0x00, 0x30, 0x00, // IAD: Protocol 0x30 = UAC3
        )
        val desc = UsbDescriptorParser.parse(uac3Iad)
        assertEquals(UacVersion.UAC_3_0_UNSUPPORTED, desc.uacVersion)
        assertFalse(desc.isSupported)
        assertNotNull(desc.unsupportedReason)
        assertTrue(desc.unsupportedReason!!.contains("3.0"))
    }

    @Test
    fun parsesCx31993RealDacDescriptor() {
        val hex = "12010102ef020140023365330100010200010902a601040104a032080b00030100200009040000000101200009240100020473000008240a090307000011240201010100090203000000000000001224060201030000000c0000000c000000040c240303010304020900000008240a0a030700000c240306010100050a0000000e24060504030000000c00000006112402040102030a010100000000000000090401000001022000090401010101022000102401010001010000000203000000000624020102100705010dc000010825010000020200090401020101022000102401010001010000000203000000000624020103180705010d2001010825010000020200090401030101022000102401010001010000000203000000000624020104200705010d8001010825010000020200090402000001022000090402010101022000102401060001010000000101000000000624020102100705810d1800010825010000020200090402020101022000102401060501010000000101000000000624020103180705810d2400010825010000020200080b030103000000090403000103000000092111010001223f0007058303400007"
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val desc = UsbDescriptorParser.parse(bytes)
        assertEquals(UacVersion.UAC_2_0, desc.uacVersion)
        assertTrue(desc.isSupported)

        // Clocks
        assertEquals(2, desc.clockSources.size)
        val clk9 = desc.clockSources.first { it.id == 9 }
        assertTrue(clk9.isProgrammable)
        assertTrue(clk9.supportsFrequencySet)
        assertTrue(clk9.supportsFrequencyGet)
        assertTrue(clk9.supportsClockValid)

        val clk10 = desc.clockSources.first { it.id == 10 }
        assertTrue(clk10.isProgrammable)

        // Terminals
        assertEquals(2, desc.inputTerminals.size)
        val in1 = desc.inputTerminals.first { it.id == 1 }
        assertTrue(in1.isUsbStreaming)
        assertEquals(9, in1.clockSourceId)
        assertEquals(2, in1.channelCount)

        assertEquals(2, desc.outputTerminals.size)
        val out3 = desc.outputTerminals.first { it.id == 3 }
        assertTrue(out3.isPhysicalOutput)
        assertEquals(9, out3.clockSourceId)
        assertEquals(2, out3.sourceId)

        // Feature Units
        assertEquals(2, desc.featureUnits.size)
        val fu2 = desc.featureUnits.first { it.id == 2 }
        assertTrue(fu2.hasMasterMute)
        assertTrue(fu2.hasChannelVolume)

        // Alternate Settings
        assertEquals(5, desc.altSettings.size)
        assertEquals(3, desc.playbackAltSettings.size)
        assertEquals(2, desc.captureAltSettings.size)

        // Alt 1 (16-bit), Alt 2 (24-bit), Alt 3 (32-bit)
        val pb1 = desc.playbackAltSettings.first { it.altSetting == 1 }
        assertEquals(1, pb1.interfaceNumber)
        assertEquals(2, pb1.subslotSize)
        assertEquals(16, pb1.bitResolution)
        assertEquals(EndpointSyncType.SYNCHRONOUS, pb1.dataEndpoint?.syncType)
        assertEquals(null, pb1.feedbackEndpoint)

        val pb2 = desc.playbackAltSettings.first { it.altSetting == 2 }
        assertEquals(3, pb2.subslotSize)
        assertEquals(24, pb2.bitResolution)
        assertEquals(EndpointSyncType.SYNCHRONOUS, pb2.dataEndpoint?.syncType)

        val pb3 = desc.playbackAltSettings.first { it.altSetting == 3 }
        assertEquals(4, pb3.subslotSize)
        assertEquals(32, pb3.bitResolution)
        assertEquals(EndpointSyncType.SYNCHRONOUS, pb3.dataEndpoint?.syncType)
    }
}

