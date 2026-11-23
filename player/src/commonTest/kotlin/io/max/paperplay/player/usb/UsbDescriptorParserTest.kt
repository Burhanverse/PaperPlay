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

    @Test
    fun parsesRealKzAcousticsC04ProbeDescriptorAndValidatesFormatMatrix() {
        // Real captured descriptor from KZ Acoustics C04 (KTMicro 0x31b2:0x0313, UAC 1.0 Full Speed, uac1-dump.txt):
        // - Interface 0: AudioControl (IT 1 Mic -> FU 2 -> SU 3 -> OT 4 USB In; IT 5 USB Out -> FU 7 -> OT 8 Headphones)
        // - Interface 1: AudioStreaming Capture (Alts 1 & 2, IN EP 0x84, 1ch 48000 Hz)
        // - Interface 2: AudioStreaming Playback (OUT EP 0x04, no feedback EP):
        //   * Alt 1: 2ch, 16-bit (2-byte subslot), discrete [44100, 48000, 88200, 96000], maxPacket=388, csBmAttributes=0x01
        //   * Alt 2: 2ch, 24-bit (3-byte subslot), discrete [44100, 88200, 48000, 96000], maxPacket=582, csBmAttributes=0x01
        // - Interface 3: HID (EP 0x83 IN, EP 0x03 OUT)
        val kzC04Hex =
            "1201000200000040b23113030300010402010902490104010080310904000000010100000a2401000147000201020c24" +
            "020101020001010000000924060201010102000924030401010002000c24020501010002030000000a24060705010102" +
            "0200092403080203000700090401000001020000090401010101020000072401040101000b2402010102100180bb0009" +
            "058405620001000007250101000000090401020101020000072401040101000b2402010103180180bb00090584059300" +
            "0100000725010100000009040200000102000009040201010102000007240105010100142402010202100444ac0080bb" +
            "008858010077010905040584010100000725010100000009040202010102000007240105010100142402010203180444" +
            "ac0088580180bb0000770109050405460201000007250101000000090403000203000000092111010001224600070583" +
            "0310000107050303100001"
        val bytes = ByteArray(kzC04Hex.length / 2) { i ->
            kzC04Hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        val desc = UsbDescriptorParser.parse(bytes)
        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        assertTrue(desc.isSupported)
        assertEquals(0, desc.acInterfaceNumber)
        assertTrue(desc.hasHardwareVolume)

        // Interfaces: 2 capture alts on interface 1, 2 playback alts on interface 2, 0 on HID interface 3
        assertEquals(4, desc.altSettings.size)
        assertEquals(2, desc.captureAltSettings.size)
        assertEquals(2, desc.playbackAltSettings.size)
        assertTrue(desc.captureAltSettings.all { it.interfaceNumber == 1 && it.dataEndpoint?.address == 0x84 })
        assertTrue(desc.playbackAltSettings.all { it.interfaceNumber == 2 && it.dataEndpoint?.address == 0x04 && it.feedbackEndpoint == null })
        assertTrue(desc.altSettings.none { it.interfaceNumber == 3 })

        // Feature Units: FU 2 (mic, src=1) and FU 7 (playback, src=5)
        assertEquals(2, desc.featureUnits.size)
        val fu2 = desc.featureUnits.first { it.id == 2 }
        assertEquals(1, fu2.sourceId)
        assertEquals(listOf(0x01L, 0x02L), fu2.bmaControls) // Master=Mute(0x01), Ch1=Volume(0x02)
        val fu7 = desc.featureUnits.first { it.id == 7 }
        assertEquals(5, fu7.sourceId)
        assertEquals(listOf(0x01L, 0x02L, 0x02L), fu7.bmaControls) // Master=Mute(0x01), Ch1=Vol(0x02), Ch2=Vol(0x02)
        assertTrue(fu7.hasMasterMute)
        assertFalse(fu7.hasMasterVolume)
        assertTrue(fu7.hasChannelVolume)

        // Format Negotiation & Topology Resolution for 44.1/16, 48/24, 96/16, and 96/24:
        val cases = listOf(
            // (rate, bitDepth, expectedIface, expectedAlt, expectedSubslot, expectedMaxPacket)
            intArrayOf(44100, 16, 2, 1, 2, 388),
            intArrayOf(48000, 24, 2, 2, 3, 582),
            intArrayOf(96000, 16, 2, 1, 2, 388),
            intArrayOf(96000, 24, 2, 2, 3, 582),
        )
        for (c in cases) {
            val rate = c[0]
            val depth = c[1]
            val expIface = c[2]
            val expAlt = c[3]
            val expSubslot = c[4]
            val expMaxPkt = c[5]
            val sel = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, rate, depth, 2)
            assertTrue(sel is FormatSelectionResult.Success, "Failed negotiation for $rate/$depth")
            val alt = sel.candidate
            assertEquals(expIface, alt.interfaceNumber)
            assertEquals(expAlt, alt.altSetting)
            assertEquals(depth, alt.bitResolution)
            assertEquals(expSubslot, alt.subslotSize)
            assertEquals(2, alt.channelCount)
            assertEquals(0x04, alt.dataEndpoint?.address)
            assertEquals(EndpointSyncType.ASYNCHRONOUS, alt.dataEndpoint?.syncType)
            assertEquals(expMaxPkt, alt.dataEndpoint?.maxPacketSize)
            assertEquals(0x01, alt.dataEndpoint?.csBmAttributes)
            assertTrue(alt.dataEndpoint!!.supportsSampleRateControl)
            assertEquals(null, alt.feedbackEndpoint)

            val resolvedFu = desc.resolvePlaybackFeatureUnit(alt)
            assertNotNull(resolvedFu)
            assertEquals(7, resolvedFu.id, "Must resolve playback FU 7, never mic FU 2")
        }
    }

    @Test
    fun parsesKzUac1CompositeGamingDacWithHidAndPerAltSampleRates() {
        // Captured KZ-style UAC 1.0 composite gaming DAC descriptor:
        // - Interface 0: AudioControl (UAC 1.0, InputTerminal 1 -> FeatureUnit 2 -> OutputTerminal 3, plus Mic capture path)
        // - Interface 1: AudioStreaming Playback (OUT EP 0x01):
        //   * Alt 1: 2ch, 16-bit (2-byte subslot), discrete 48000 Hz + 96000 Hz, CS_ENDPOINT bmAttributes = 0x01 (rate control supported)
        //   * Alt 2: 2ch, 24-bit (3-byte subslot), single discrete 96000 Hz, CS_ENDPOINT bmAttributes = 0x00 (fixed-rate, no rate control)
        //   * Alt 3: 2ch, 24-bit (3-byte subslot), continuous 44100..96000 Hz, CS_ENDPOINT bmAttributes = 0x01 (rate control supported)
        // - Interface 2: AudioStreaming Capture (IN EP 0x82, Alt 1: 1ch, 16-bit, 48000 Hz)
        // - Interface 3: HID (Class 0x03) for hardware EQ switches and sound-mode buttons
        val kzDescriptor = byteArrayOf(
            // Configuration Descriptor (4 interfaces: AC=0, AS_OUT=1, AS_IN=2, HID=3)
            0x09, 0x02, 0x00, 0x01, 0x04, 0x01, 0x00, 0x80.toByte(), 0x32,
            // Interface 0: AudioControl (Class 1, Subclass 1, Protocol 0)
            0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00,
            // CS_INTERFACE AC_HEADER (bcdADC 1.00, 2 streaming interfaces: 1 and 2)
            0x0A, 0x24, 0x01, 0x00, 0x01, 0x47, 0x00, 0x02, 0x01, 0x02,
            // CS_INTERFACE INPUT_TERMINAL (ID=1, USB Streaming 0x0101, 2ch)
            0x0C, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00,
            // CS_INTERFACE FEATURE_UNIT (ID=2, src=1, bControlSize=1, Master=0x01(Mute), Ch1=0x02(Vol), Ch2=0x02(Vol), iFeature=0x03!)
            0x0A, 0x24, 0x06, 0x02, 0x01, 0x01, 0x01, 0x02, 0x02, 0x03,
            // CS_INTERFACE OUTPUT_TERMINAL (ID=3, Headphones 0x0302, src=2)
            0x09, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x02, 0x00,
            // CS_INTERFACE INPUT_TERMINAL (ID=4, Microphone 0x0201, 1ch)
            0x0C, 0x24, 0x02, 0x04, 0x01, 0x02, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00,
            // CS_INTERFACE FEATURE_UNIT (ID=5, src=4, bControlSize=1, Master=0x03(Mute+Vol), Ch1=0x00, iFeature=0x00)
            0x09, 0x24, 0x06, 0x05, 0x04, 0x01, 0x03, 0x00, 0x00,
            // CS_INTERFACE OUTPUT_TERMINAL (ID=6, USB Streaming 0x0101, src=5)
            0x09, 0x24, 0x03, 0x06, 0x01, 0x01, 0x00, 0x05, 0x00,

            // Interface 1, Alt 0: AudioStreaming Zero Bandwidth
            0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00,
            // Interface 1, Alt 1: 16-bit 2ch, discrete 48k + 96k, rate control supported (0x01)
            0x09, 0x04, 0x01, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00,
            0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00, // AS_GENERAL: terminalLink=1, PCM (0x0001)
            0x0E, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x02, // FORMAT_TYPE_I: 2ch, 2B subslot, 16-bit, 2 discrete rates
            0x80.toByte(), 0xBB.toByte(), 0x00, // 48000 Hz
            0x00, 0x77, 0x01, // 96000 Hz
            0x09, 0x05, 0x01, 0x09, 0x80.toByte(), 0x01, 0x01, 0x00, 0x00, // EP 0x01 OUT Adaptive Isochronous, maxPacket=384
            0x07, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00, // CS_ENDPOINT: bmAttributes = 0x01 (Sampling Frequency control supported)

            // Interface 1, Alt 2: 24-bit (3-byte subslot) 2ch, fixed single discrete 96k, NO rate control (0x00)
            0x09, 0x04, 0x01, 0x02, 0x01, 0x01, 0x02, 0x00, 0x00,
            0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00,
            0x0B, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x01, // FORMAT_TYPE_I: 2ch, 3B subslot, 24-bit, 1 discrete rate
            0x00, 0x77, 0x01, // 96000 Hz
            0x09, 0x05, 0x01, 0x09, 0x48, 0x02, 0x01, 0x00, 0x00, // EP 0x01 OUT Adaptive Isochronous, maxPacket=584
            0x07, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00, // CS_ENDPOINT: bmAttributes = 0x00 (NO Sampling Frequency control!)

            // Interface 1, Alt 3: 24-bit (3-byte subslot) 2ch, continuous 44100..96000 Hz, rate control supported (0x01)
            0x09, 0x04, 0x01, 0x03, 0x01, 0x01, 0x02, 0x00, 0x00,
            0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00,
            0x0E, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x00, // FORMAT_TYPE_I: bSamFreqType = 0 (continuous)
            0x44, 0xAC.toByte(), 0x00, // tLowerSamFreq = 44100 Hz
            0x00, 0x77, 0x01, // tUpperSamFreq = 96000 Hz
            0x09, 0x05, 0x01, 0x09, 0x48, 0x02, 0x01, 0x00, 0x00,
            0x07, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00, // CS_ENDPOINT: bmAttributes = 0x01

            // Interface 2, Alt 0 & Alt 1: AudioStreaming Capture (Mic IN EP 0x82, 1ch, 16-bit, 48k)
            0x09, 0x04, 0x02, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00,
            0x09, 0x04, 0x02, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00,
            0x07, 0x24, 0x01, 0x06, 0x01, 0x01, 0x00,
            0x0B, 0x24, 0x02, 0x01, 0x01, 0x02, 0x10, 0x01,
            0x80.toByte(), 0xBB.toByte(), 0x00,
            0x09, 0x05, 0x82.toByte(), 0x05, 0x60, 0x00, 0x01, 0x00, 0x00,
            0x07, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00,

            // Interface 3: HID (Class 3) for hardware EQ switches and sound-mode buttons
            0x09, 0x04, 0x03, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00,
            0x09, 0x21, 0x11, 0x01, 0x00, 0x01, 0x22, 0x2B, 0x00,
            0x07, 0x05, 0x83.toByte(), 0x03, 0x10, 0x00, 0x0A,
        )

        val desc = UsbDescriptorParser.parse(kzDescriptor)
        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        assertTrue(desc.isSupported)
        assertEquals(0, desc.acInterfaceNumber)

        // HID interface 3 must not appear in audio streaming alt settings
        assertTrue(desc.altSettings.none { it.interfaceNumber == 3 })
        assertEquals(3, desc.playbackAltSettings.size)
        assertEquals(1, desc.captureAltSettings.size)

        // Topology resolver must pick Playback Feature Unit 2 (and never Capture Feature Unit 5)
        val pbFu = desc.resolvePlaybackFeatureUnit(desc.playbackAltSettings.first())
        assertNotNull(pbFu)
        assertEquals(2, pbFu.id)
        // iFeature = 0x03 at the end of FU 2 must NOT be mistaken for a 3rd channel control byte!
        assertEquals(listOf(0x01L, 0x02L, 0x02L), pbFu.bmaControls)
        assertTrue(pbFu.hasMasterMute)
        assertFalse(pbFu.hasMasterVolume)
        assertTrue(pbFu.hasChannelVolume)
        assertFalse(pbFu.hasChannelMute)

        // Alt 1: 16-bit discrete 48k/96k with rate control supported (csBmAttributes = 0x01)
        val alt1 = desc.playbackAltSettings.first { it.altSetting == 1 }
        assertEquals(2, alt1.subslotSize)
        assertEquals(16, alt1.bitResolution)
        assertEquals(listOf(48000, 96000), alt1.sampleRates)
        assertEquals(null, alt1.continuousRateRange)
        assertEquals(0x01, alt1.dataEndpoint?.csBmAttributes)
        assertTrue(alt1.dataEndpoint!!.supportsSampleRateControl)
        assertEquals(0x01, alt1.computeNativeEpAttributes(96000)) // 2 discrete rates -> bit 7 clear

        // Alt 2: 24-bit single discrete 96k without rate control (csBmAttributes = 0x00)
        val alt2 = desc.playbackAltSettings.first { it.altSetting == 2 }
        assertEquals(3, alt2.subslotSize)
        assertEquals(24, alt2.bitResolution)
        assertEquals(listOf(96000), alt2.sampleRates)
        assertEquals(0x00, alt2.dataEndpoint?.csBmAttributes)
        assertFalse(alt2.dataEndpoint!!.supportsSampleRateControl)
        assertEquals(0x80, alt2.computeNativeEpAttributes(96000)) // single discrete rate == 96000 -> bit 7 set (0x80)

        // Alt 3: 24-bit continuous 44100..96000 Hz with rate control supported (csBmAttributes = 0x01)
        val alt3 = desc.playbackAltSettings.first { it.altSetting == 3 }
        assertEquals(3, alt3.subslotSize)
        assertEquals(24, alt3.bitResolution)
        assertTrue(alt3.sampleRates.isEmpty())
        assertEquals(Pair(44100, 96000), alt3.continuousRateRange)
        assertTrue(alt3.dataEndpoint!!.supportsSampleRateControl)
        assertEquals(0x01, alt3.computeNativeEpAttributes(44100))

        // Format negotiator on KZ descriptor:
        // - For 96 kHz / 24-bit / 2ch, must prefer Alt 2 (dedicated single discrete 96 kHz alt) over Alt 3 (continuous range)
        val pick96k24 = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, 96000, 24, 2)
        assertTrue(pick96k24 is FormatSelectionResult.Success)
        assertEquals(2, pick96k24.candidate.altSetting)

        // - For 44.1 kHz / 24-bit / 2ch, must select Alt 3 (continuous 44100..96000 Hz)
        val pick44k24 = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, 44100, 24, 2)
        assertTrue(pick44k24 is FormatSelectionResult.Success)
        assertEquals(3, pick44k24.candidate.altSetting)

        // - For 48 kHz / 16-bit / 2ch, must select Alt 1 (exact 16-bit resolution match)
        val pick48k16 = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, 48000, 16, 2)
        assertTrue(pick48k16 is FormatSelectionResult.Success)
        assertEquals(1, pick48k16.candidate.altSetting)
    }
}

