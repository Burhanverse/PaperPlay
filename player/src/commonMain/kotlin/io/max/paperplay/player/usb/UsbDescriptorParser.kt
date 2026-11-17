package io.max.paperplay.player.usb

/**
 * Pure Kotlin parser for USB Audio Class descriptors from UsbDeviceConnection.getRawDescriptors().
 *
 * Implements UAC 2.0 and UAC 1.0 parsing directly against public USB-IF specifications:
 * - Universal Serial Bus Device Class Definition for Audio Devices (Release 1.0)
 * - Universal Serial Bus Device Class Definition for Audio Devices (Release 2.0)
 * - Universal Serial Bus Device Class Definition for Audio Data Formats (Release 1.0 & 2.0)
 */
object UsbDescriptorParser {
    // Descriptor types
    private const val CS_UNDEFINED = 0x20
    private const val CS_DEVICE = 0x21
    private const val CS_CONFIGURATION = 0x22
    private const val CS_STRING = 0x23
    private const val CS_INTERFACE = 0x24
    private const val CS_ENDPOINT = 0x25

    private const val USB_DT_INTERFACE_ASSOCIATION = 0x0B
    private const val USB_DT_INTERFACE = 0x04
    private const val USB_DT_ENDPOINT = 0x05

    // USB Audio Subclasses
    private const val USB_SUBCLASS_AUDIOCONTROL = 0x01
    private const val USB_SUBCLASS_AUDIOSTREAMING = 0x02

    // USB Audio Protocols
    private const val UAC_PROTOCOL_1_0 = 0x00
    private const val UAC_PROTOCOL_2_0 = 0x20
    private const val UAC_PROTOCOL_3_0 = 0x30

    // AudioControl Interface Subtypes (AC)
    private const val UAC_AC_HEADER = 0x01
    private const val UAC_INPUT_TERMINAL = 0x02
    private const val UAC_OUTPUT_TERMINAL = 0x03
    private const val UAC_MIXER_UNIT = 0x04
    private const val UAC_SELECTOR_UNIT = 0x05
    private const val UAC_FEATURE_UNIT = 0x06
    private const val UAC2_CLOCK_SOURCE = 0x0A
    private const val UAC2_CLOCK_SELECTOR = 0x0B
    private const val UAC2_CLOCK_MULTIPLIER = 0x0C

    // AudioStreaming Interface Subtypes (AS)
    private const val UAC_AS_GENERAL = 0x01
    private const val UAC_FORMAT_TYPE = 0x02

    // Format Types
    private const val FORMAT_TYPE_I = 0x01

    /** Slice of raw descriptor bytes. */
    private class RawDescriptor(
        val type: Int,
        val length: Int,
        val offset: Int,
        val data: ByteArray,
    ) {
        fun byte(index: Int): Int =
            if (index < length && offset + index < data.size) data[offset + index].toInt() and 0xFF else 0

        fun u16(index: Int): Int =
            if (index + 1 < length && offset + index + 1 < data.size) {
                (data[offset + index].toInt() and 0xFF) or ((data[offset + index + 1].toInt() and 0xFF) shl 8)
            } else 0

        fun u24(index: Int): Int =
            if (index + 2 < length && offset + index + 2 < data.size) {
                (data[offset + index].toInt() and 0xFF) or
                        ((data[offset + index + 1].toInt() and 0xFF) shl 8) or
                        ((data[offset + index + 2].toInt() and 0xFF) shl 16)
            } else 0

        fun u32(index: Int): Long =
            if (index + 3 < length && offset + index + 3 < data.size) {
                val b0 = data[offset + index].toLong() and 0xFF
                val b1 = (data[offset + index + 1].toLong() and 0xFF) shl 8
                val b2 = (data[offset + index + 2].toLong() and 0xFF) shl 16
                val b3 = (data[offset + index + 3].toLong() and 0xFF) shl 24
                b0 or b1 or b2 or b3
            } else 0L
    }

    fun parse(rawDescriptors: ByteArray?): UsbAudioDeviceDescription {
        if (rawDescriptors == null || rawDescriptors.size < 9) {
            return UsbAudioDeviceDescription(
                uacVersion = UacVersion.UNKNOWN,
                isSupported = false,
                unsupportedReason = "Raw descriptor is null or too short (< 9 bytes)",
            )
        }

        return try {
            parseInternal(rawDescriptors)
        } catch (e: Exception) {
            UsbAudioDeviceDescription(
                uacVersion = UacVersion.UNKNOWN,
                isSupported = false,
                unsupportedReason = "Failed to parse descriptors: ${e.message}",
            )
        }
    }

    private fun parseInternal(data: ByteArray): UsbAudioDeviceDescription {
        val descriptors = mutableListOf<RawDescriptor>()
        var offset = 0
        while (offset < data.size) {
            val len = data[offset].toInt() and 0xFF
            if (len < 2 || offset + len > data.size) {
                break // Stop cleanly on truncated or zero-length descriptor
            }
            val type = data[offset + 1].toInt() and 0xFF
            descriptors.add(RawDescriptor(type, len, offset, data))
            offset += len
        }

        if (descriptors.isEmpty()) {
            return UsbAudioDeviceDescription(
                uacVersion = UacVersion.UNKNOWN,
                isSupported = false,
                unsupportedReason = "No valid USB descriptors found",
            )
        }

        // 1. Detect UAC Version from IAD or AudioControl Interface
        var detectedVersion = UacVersion.UNKNOWN
        for (desc in descriptors) {
            if (desc.type == USB_DT_INTERFACE_ASSOCIATION && desc.length >= 8) {
                val funcClass = desc.byte(4)
                val protocol = desc.byte(6)
                if (funcClass == 0x01) { // Audio
                    detectedVersion = when (protocol) {
                        UAC_PROTOCOL_2_0 -> UacVersion.UAC_2_0
                        UAC_PROTOCOL_1_0 -> UacVersion.UAC_1_0
                        UAC_PROTOCOL_3_0 -> UacVersion.UAC_3_0_UNSUPPORTED
                        else -> detectedVersion
                    }
                }
            } else if (desc.type == USB_DT_INTERFACE && desc.length >= 9) {
                val ifClass = desc.byte(5)
                val ifSubclass = desc.byte(6)
                val ifProtocol = desc.byte(7)
                if (ifClass == 0x01 && ifSubclass == USB_SUBCLASS_AUDIOCONTROL) {
                    if (detectedVersion == UacVersion.UNKNOWN) {
                        detectedVersion = when (ifProtocol) {
                            UAC_PROTOCOL_2_0 -> UacVersion.UAC_2_0
                            UAC_PROTOCOL_1_0 -> UacVersion.UAC_1_0
                            UAC_PROTOCOL_3_0 -> UacVersion.UAC_3_0_UNSUPPORTED
                            else -> detectedVersion
                        }
                    }
                }
            }
        }

        // Secondary check from CS_INTERFACE header bcdADC if still unknown
        if (detectedVersion == UacVersion.UNKNOWN) {
            for (desc in descriptors) {
                if (desc.type == CS_INTERFACE && desc.byte(2) == UAC_AC_HEADER) {
                    val bcd = desc.u16(3)
                    detectedVersion = when {
                        bcd >= 0x0300 -> UacVersion.UAC_3_0_UNSUPPORTED
                        bcd >= 0x0200 -> UacVersion.UAC_2_0
                        bcd >= 0x0100 -> UacVersion.UAC_1_0
                        else -> detectedVersion
                    }
                    break
                }
            }
        }

        if (detectedVersion == UacVersion.UAC_3_0_UNSUPPORTED) {
            return UsbAudioDeviceDescription(
                uacVersion = UacVersion.UAC_3_0_UNSUPPORTED,
                isSupported = false,
                unsupportedReason = "USB Audio Class 3.0 (protocol 0x30) is not supported",
            )
        }

        if (detectedVersion == UacVersion.UNKNOWN) {
            detectedVersion = UacVersion.UAC_1_0 // fallback assumption for legacy devices with 0x00 protocol
        }

        val clockSources = mutableListOf<UsbClockSource>()
        val clockSelectors = mutableListOf<UsbClockSelector>()
        val clockMultipliers = mutableListOf<UsbClockMultiplier>()
        val inputTerminals = mutableListOf<UsbInputTerminal>()
        val outputTerminals = mutableListOf<UsbOutputTerminal>()
        val featureUnits = mutableListOf<UsbFeatureUnit>()

        // 2. Parse AudioControl Interface structures
        var inAudioControl = false
        for (desc in descriptors) {
            if (desc.type == USB_DT_INTERFACE) {
                val ifClass = desc.byte(5)
                val ifSubclass = desc.byte(6)
                inAudioControl = (ifClass == 0x01 && ifSubclass == USB_SUBCLASS_AUDIOCONTROL)
                continue
            }

            if (inAudioControl && desc.type == CS_INTERFACE) {
                val subtype = desc.byte(2)
                when (subtype) {
                    UAC_INPUT_TERMINAL -> {
                        val id = desc.byte(3)
                        val type = desc.u16(4)
                        val assoc = desc.byte(6)
                        if (detectedVersion == UacVersion.UAC_2_0 && desc.length >= 17) {
                            val cSource = desc.byte(7)
                            val ch = desc.byte(8)
                            val cfg = desc.u32(9)
                            inputTerminals.add(UsbInputTerminal(id, type, assoc, cSource, ch, cfg))
                        } else if (desc.length >= 12) {
                            val ch = desc.byte(7)
                            val cfg = desc.u16(8).toLong()
                            inputTerminals.add(UsbInputTerminal(id, type, assoc, 0, ch, cfg))
                        }
                    }
                    UAC_OUTPUT_TERMINAL -> {
                        val id = desc.byte(3)
                        val type = desc.u16(4)
                        val assoc = desc.byte(6)
                        val src = desc.byte(7)
                        val cSource = if (detectedVersion == UacVersion.UAC_2_0 && desc.length >= 12) desc.byte(8) else 0
                        outputTerminals.add(UsbOutputTerminal(id, type, assoc, src, cSource))
                    }
                    UAC_FEATURE_UNIT -> {
                        val id = desc.byte(3)
                        val src = desc.byte(4)
                        if (detectedVersion == UacVersion.UAC_2_0 && desc.length >= 10) {
                            // bmaControls is 4 bytes per logical channel plus master channel
                            val controls = mutableListOf<Long>()
                            var o = 5
                            while (o + 3 < desc.length) {
                                controls.add(desc.u32(o))
                                o += 4
                            }
                            val master = controls.firstOrNull() ?: 0L
                            val hasMute = (master and 0x03L) != 0L
                            val hasVol = (master and 0x0CL) != 0L
                            val hasChVol = controls.drop(1).any { (it and 0x0CL) != 0L }
                            featureUnits.add(UsbFeatureUnit(id, src, 4, controls, hasMute, hasVol, hasChVol))
                        } else if (desc.length >= 7) {
                            val ctrlSize = desc.byte(5)
                            val controls = mutableListOf<Long>()
                            if (ctrlSize in 1..4) {
                                var o = 6
                                while (o + ctrlSize <= desc.length) {
                                    var v = 0L
                                    for (b in 0 until ctrlSize) {
                                        v = v or ((desc.byte(o + b).toLong() and 0xFF) shl (b * 8))
                                    }
                                    controls.add(v)
                                    o += ctrlSize
                                }
                            }
                            val master = controls.firstOrNull() ?: 0L
                            val hasMute = (master and 0x01L) != 0L
                            val hasVol = (master and 0x02L) != 0L
                            val hasChVol = controls.drop(1).any { (it and 0x02L) != 0L }
                            featureUnits.add(UsbFeatureUnit(id, src, ctrlSize, controls, hasMute, hasVol, hasChVol))
                        }
                    }
                    UAC2_CLOCK_SOURCE -> {
                        if (desc.length >= 8) {
                            val id = desc.byte(3)
                            val attr = desc.byte(4)
                            val ctrl = desc.byte(5)
                            val assoc = desc.byte(6)
                            val clockType = attr and 0x03
                            val isProg = clockType == 3
                            val freqSet = (ctrl and 0x02) != 0
                            val freqGet = (ctrl and 0x01) != 0
                            val clockValid = (ctrl and 0x04) != 0
                            clockSources.add(UsbClockSource(id, clockType, isProg, freqSet, freqGet, clockValid, assoc))
                        }
                    }
                    UAC2_CLOCK_SELECTOR -> {
                        if (desc.length >= 6) {
                            val id = desc.byte(3)
                            val pins = desc.byte(4)
                            val inputPins = mutableListOf<Int>()
                            for (p in 0 until pins) {
                                if (5 + p < desc.length) {
                                    inputPins.add(desc.byte(5 + p))
                                }
                            }
                            clockSelectors.add(UsbClockSelector(id, inputPins))
                        }
                    }
                    UAC2_CLOCK_MULTIPLIER -> {
                        if (desc.length >= 7) {
                            val id = desc.byte(3)
                            val src = desc.byte(4)
                            clockMultipliers.add(UsbClockMultiplier(id, src))
                        }
                    }
                }
            }
        }

        // 3. Parse AudioStreaming Interfaces
        val altSettings = mutableListOf<UsbAudioAltSetting>()
        var currentAsInterfaceNumber = -1
        var currentAltSetting = -1
        var currentEndpointCount = 0
        var currentTerminalLink = 0
        var currentFormatTag = AudioFormatTag.PCM
        var currentBmFormats = 1L // Default PCM
        var currentChannels = 2
        var currentSubslotSize = 2
        var currentBitResolution = 16
        var currentSampleRates = mutableListOf<Int>()
        var currentContinuousRange: Pair<Int, Int>? = null
        var currentDataEndpoint: UsbEndpointDescriptor? = null
        var currentFeedbackEndpoint: UsbEndpointDescriptor? = null

        fun flushAltSetting() {
            if (currentAsInterfaceNumber >= 0 && currentAltSetting > 0 && currentDataEndpoint != null) {
                val isDsdRaw = (currentBmFormats and 0x80000000L) != 0L || currentFormatTag == AudioFormatTag.RAW_DATA
                val isDoP = currentSubslotSize >= 3 && currentBitResolution >= 24 &&
                        (detectedVersion == UacVersion.UAC_2_0 || currentSampleRates.any { it >= 176400 } || (currentContinuousRange?.second ?: 0) >= 176400)

                altSettings.add(
                    UsbAudioAltSetting(
                        interfaceNumber = currentAsInterfaceNumber,
                        altSetting = currentAltSetting,
                        terminalLink = currentTerminalLink,
                        formatTag = currentFormatTag,
                        bmFormats = currentBmFormats,
                        channelCount = currentChannels,
                        subslotSize = currentSubslotSize,
                        bitResolution = currentBitResolution,
                        sampleRates = currentSampleRates.toList(),
                        continuousRateRange = currentContinuousRange,
                        dataEndpoint = currentDataEndpoint,
                        feedbackEndpoint = currentFeedbackEndpoint,
                        isDsdRawCapable = isDsdRaw,
                        isDsdDoPCapable = isDoP,
                    )
                )
            }
            currentTerminalLink = 0
            currentFormatTag = AudioFormatTag.PCM
            currentBmFormats = 1L
            currentChannels = 2
            currentSubslotSize = 2
            currentBitResolution = 16
            currentSampleRates = mutableListOf()
            currentContinuousRange = null
            currentDataEndpoint = null
            currentFeedbackEndpoint = null
        }

        for (desc in descriptors) {
            if (desc.type == USB_DT_INTERFACE) {
                val ifClass = desc.byte(5)
                val ifSubclass = desc.byte(6)
                if (ifClass == 0x01 && ifSubclass == USB_SUBCLASS_AUDIOSTREAMING) {
                    flushAltSetting()
                    currentAsInterfaceNumber = desc.byte(2)
                    currentAltSetting = desc.byte(3)
                    currentEndpointCount = desc.byte(4)
                } else {
                    flushAltSetting()
                    currentAsInterfaceNumber = -1
                    currentAltSetting = -1
                }
                continue
            }

            if (currentAsInterfaceNumber >= 0 && currentAltSetting > 0) {
                if (desc.type == CS_INTERFACE) {
                    val subtype = desc.byte(2)
                    when (subtype) {
                        UAC_AS_GENERAL -> {
                            currentTerminalLink = desc.byte(3)
                            if (detectedVersion == UacVersion.UAC_2_0 && desc.length >= 16) {
                                currentBmFormats = desc.u32(6)
                                currentChannels = desc.byte(10).coerceAtLeast(1)
                                currentFormatTag = if ((currentBmFormats and 0x80000000L) != 0L) {
                                    AudioFormatTag.RAW_DATA
                                } else {
                                    AudioFormatTag.PCM
                                }
                            } else if (desc.length >= 7) {
                                val tag = desc.u16(5)
                                currentFormatTag = AudioFormatTag.fromTag(tag)
                            }
                        }
                        UAC_FORMAT_TYPE -> {
                            val formatType = desc.byte(3)
                            if (formatType == FORMAT_TYPE_I) {
                                if (detectedVersion == UacVersion.UAC_2_0 && desc.length >= 6) {
                                    currentSubslotSize = desc.byte(4).coerceAtLeast(1)
                                    currentBitResolution = desc.byte(5).coerceAtLeast(1)
                                } else if (desc.length >= 8) {
                                    currentChannels = desc.byte(4).coerceAtLeast(1)
                                    currentSubslotSize = desc.byte(5).coerceAtLeast(1)
                                    currentBitResolution = desc.byte(6).coerceAtLeast(1)
                                    val samFreqType = desc.byte(7)
                                    if (samFreqType == 0 && desc.length >= 14) {
                                        val minRate = desc.u24(8)
                                        val maxRate = desc.u24(11)
                                        currentContinuousRange = Pair(minRate, maxRate)
                                    } else if (samFreqType > 0) {
                                        var o = 8
                                        for (i in 0 until samFreqType) {
                                            if (o + 2 < desc.length) {
                                                currentSampleRates.add(desc.u24(o))
                                                o += 3
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (desc.type == USB_DT_ENDPOINT && desc.length >= 7) {
                    val address = desc.byte(2)
                    val isOut = (address and 0x80) == 0
                    val bmAttributes = desc.byte(3)
                    val transferType = bmAttributes and 0x03
                    if (transferType == 0x01) { // Isochronous
                        val syncType = EndpointSyncType.fromAttributes(bmAttributes)
                        val usageType = EndpointUsageType.fromAttributes(bmAttributes)
                        val maxPacket = desc.u16(4) and 0x07FF
                        val interval = desc.byte(6)
                        val refresh = if (desc.length >= 8) desc.byte(7) else 0
                        val synchAddress = if (desc.length >= 9) desc.byte(8) else 0
                        val ep = UsbEndpointDescriptor(
                            address = address,
                            isOut = isOut,
                            syncType = syncType,
                            usageType = usageType,
                            maxPacketSize = maxPacket,
                            interval = interval,
                            refresh = refresh,
                            synchAddress = synchAddress,
                        )
                        if (usageType == EndpointUsageType.FEEDBACK || (!isOut && syncType == EndpointSyncType.NONE)) {
                            currentFeedbackEndpoint = ep
                        } else {
                            currentDataEndpoint = ep
                        }
                    }
                }
            }
        }
        flushAltSetting()

        val hasHwVolume = featureUnits.any { it.hasMasterVolume || it.hasChannelVolume }
        val rawDsd = altSettings.any { it.isDsdRawCapable }
        val doP = altSettings.any { it.isDsdDoPCapable }

        return UsbAudioDeviceDescription(
            uacVersion = detectedVersion,
            isSupported = altSettings.isNotEmpty(),
            unsupportedReason = if (altSettings.isEmpty()) "No supported isochronous audio streaming alternate settings found" else null,
            clockSources = clockSources,
            clockSelectors = clockSelectors,
            clockMultipliers = clockMultipliers,
            inputTerminals = inputTerminals,
            outputTerminals = outputTerminals,
            featureUnits = featureUnits,
            altSettings = altSettings,
            hasHardwareVolume = hasHwVolume,
            rawDsdCapable = rawDsd,
            doPCapable = doP,
        )
    }
}

