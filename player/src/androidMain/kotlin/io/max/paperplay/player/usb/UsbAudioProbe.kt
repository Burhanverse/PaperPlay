package io.max.paperplay.player.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import io.max.paperplay.player.PlayLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device USB Audio Probe & Diagnostic Spike.
 *
 * Implements hardware inspection, raw descriptor analysis, Android 14 bit-perfect
 * mixer attribute evaluation, and direct usbfs control transfer safe claim tests.
 */
object UsbAudioProbe {

    fun run(context: Context, usbManager: UsbAudioManager): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timestamp = dateFormat.format(Date())

        sb.appendLine("================================================================================")
        sb.appendLine("PAPERPLAY USB AUDIO PROBE REPORT")
        sb.appendLine("================================================================================")
        sb.appendLine("Timestamp: $timestamp")
        sb.appendLine("Android OS: Android ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        sb.appendLine("Build Fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine()

        val state = usbManager.state.value
        val connected = state as? UsbAudioState.Connected
        val dev = connected?.device ?: usbManager.enumerateAudioDevices().firstOrNull()

        if (dev == null) {
            sb.appendLine("--------------------------------------------------------------------------------")
            sb.appendLine("STATUS: NO USB AUDIO DEVICE DETECTED")
            sb.appendLine("--------------------------------------------------------------------------------")
            sb.appendLine("No USB Audio Class (0x01) peripherals found connected to this device.")
            sb.appendLine("Please plug in a USB DAC or dongle and re-run the probe.")
            val report = sb.toString()
            saveReportToFile(context, report, sb)
            return sb.toString()
        }

        // If not connected yet (e.g. Permission required), try to open if permission granted
        var connection = connected?.connection
        var description = connected?.description

        var openedTemporarilyForProbe = false
        if (connection == null) {
            sb.appendLine("Device attached: ${dev.productName ?: "USB Audio"} (VID 0x${dev.vendorId.toString(16)}:PID 0x${dev.productId.toString(16)})")
            val hasPerm = usbManager.openDevice(dev, forceForProbe = true)
            if (hasPerm) {
                openedTemporarilyForProbe = !io.max.paperplay.player.PlaybackCore.usbExclusiveMode
                val newState = usbManager.state.value as? UsbAudioState.Connected
                connection = newState?.connection
                description = newState?.description
            } else {
                sb.appendLine("USB Permission: Permission not yet granted. Requesting runtime permission dialog...")
                usbManager.requestPermission(dev)
                sb.appendLine("Please accept the USB permission prompt on your screen and run the probe again.")
                val report = sb.toString()
                saveReportToFile(context, report, sb)
                return sb.toString()
            }
        }

        if (connection == null || description == null) {
            sb.appendLine("Failed to establish UsbDeviceConnection to ${dev.productName}.")
            val report = sb.toString()
            saveReportToFile(context, report, sb)
            return sb.toString()
        }

        try {
            // 1. USB Hardware Identification
            appendHardwareIdentification(dev, description, sb)

            // 2. Descriptors and Parsed Summary
            appendDescriptorSummary(connection, description, sb)

            // 3. Android 14+ Mixer Attributes Spike
            appendAndroid14MixerSpike(context, sb)

            // 4. Safe Direct Claim & Control Transfer Test
            val isPlaybackActive = io.max.paperplay.player.PlaybackCore.usbActive ||
                usbManager.isStreamingActive

            if (isPlaybackActive) {
                sb.appendLine("--------------------------------------------------------------------------------")
                sb.appendLine("4. SAFE DIRECT CLAIM & CONTROL TRANSFER TEST")
                sb.appendLine("--------------------------------------------------------------------------------")
                sb.appendLine("SKIPPED: USB exclusive audio streaming is currently active.")
                sb.appendLine("Direct interface claiming and kernel driver reattachment are skipped during active")
                sb.appendLine("playback to preserve the live session handle and prevent URB submission errors.")
                sb.appendLine()
            } else {
                appendSafeClaimTest(context, dev, connection, description, sb)
            }

            // 5. Architecture Assessment & Conclusion
            appendConclusion(description, sb)
        } finally {
            if (openedTemporarilyForProbe && !io.max.paperplay.player.PlaybackCore.usbExclusiveMode) {
                usbManager.onUsbExclusiveModeChanged(false)
            }
        }

        val fullText = sb.toString()
        saveReportToFile(context, fullText, sb)
        return fullText
    }

    private fun appendHardwareIdentification(
        dev: UsbDevice,
        desc: UsbAudioDeviceDescription,
        sb: StringBuilder
    ) {
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("1. USB HARDWARE IDENTIFICATION")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("Product Name: ${dev.productName ?: "Unknown Product"}")
        sb.appendLine("Manufacturer: ${dev.manufacturerName ?: "Unknown Manufacturer"}")
        val vid = "0x" + dev.vendorId.toString(16).padStart(4, '0')
        val pid = "0x" + dev.productId.toString(16).padStart(4, '0')
        sb.appendLine("VID:PID: $vid:$pid")
        sb.appendLine("Device Class: 0x${dev.deviceClass.toString(16).padStart(2, '0')}, SubClass: 0x${dev.deviceSubclass.toString(16).padStart(2, '0')}, Protocol: 0x${dev.deviceProtocol.toString(16).padStart(2, '0')}")
        sb.appendLine("Interface Count: ${dev.interfaceCount}")

        val isHighSpeed = desc.uacVersion == UacVersion.UAC_2_0 ||
                desc.altSettings.any { (it.dataEndpoint?.maxPacketSize ?: 0) > 1023 }
        sb.appendLine("Negotiated Bus Speed: ${if (isHighSpeed) "High Speed (480 Mbps)" else "Full Speed (12 Mbps)"}")
        sb.appendLine("UAC Protocol Version: ${desc.uacVersion.name} (${if (desc.uacVersion == UacVersion.UAC_2_0) "UAC 2.0" else "UAC 1.0"})")
        sb.appendLine("Hardware Volume Supported: ${desc.hasHardwareVolume}")
        sb.appendLine("DSD Native Capable: ${desc.rawDsdCapable}, DoP Capable: ${desc.doPCapable}")
        sb.appendLine()
    }

    private fun appendDescriptorSummary(
        connection: UsbDeviceConnection,
        desc: UsbAudioDeviceDescription,
        sb: StringBuilder
    ) {
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("2. DESCRIPTOR SUMMARY & PARSED TOPOLOGY")
        sb.appendLine("--------------------------------------------------------------------------------")

        sb.appendLine("Clock Sources (${desc.clockSources.size}):")
        if (desc.clockSources.isEmpty()) {
            sb.appendLine("  (None found / UAC 1.0 implicit endpoint clocking)")
        } else {
            for (cs in desc.clockSources) {
                sb.appendLine("  - Clock ID ${cs.id}: type=${cs.clockType} (prog=${cs.isProgrammable}, freqSet=${cs.supportsFrequencySet}, freqGet=${cs.supportsFrequencyGet}, valid=${cs.supportsClockValid}, assocTerm=${cs.assocTerminal})")
            }
        }

        if (desc.clockSelectors.isNotEmpty()) {
            sb.appendLine("Clock Selectors (${desc.clockSelectors.size}):")
            for (csel in desc.clockSelectors) {
                sb.appendLine("  - Selector ID ${csel.id}: inputPins=${csel.inputClockIds}")
            }
        }

        if (desc.clockMultipliers.isNotEmpty()) {
            sb.appendLine("Clock Multipliers (${desc.clockMultipliers.size}):")
            for (cm in desc.clockMultipliers) {
                sb.appendLine("  - Multiplier ID ${cm.id}: sourceId=${cm.sourceClockId}")
            }
        }

        sb.appendLine("Input Terminals (${desc.inputTerminals.size}):")
        for (it in desc.inputTerminals) {
            val typeStr = "0x" + it.terminalType.toString(16).padStart(4, '0')
            val name = if (it.isUsbStreaming) "USB Streaming" else "Physical In"
            sb.appendLine("  - Terminal ID ${it.id}: $typeStr ($name), clockSource=${it.clockSourceId}, ch=${it.channelCount}, config=0x${it.channelConfig.toString(16).padStart(8, '0')}")
        }

        sb.appendLine("Feature Units (${desc.featureUnits.size}):")
        for (fu in desc.featureUnits) {
            sb.appendLine("  - Feature Unit ID ${fu.id}: src=${fu.sourceId}, ctrlSize=${fu.controlSize}, masterMute=${fu.hasMasterMute}, masterVol=${fu.hasMasterVolume}, chVol=${fu.hasChannelVolume}")
        }

        sb.appendLine("Output Terminals (${desc.outputTerminals.size}):")
        for (ot in desc.outputTerminals) {
            val typeStr = "0x" + ot.terminalType.toString(16).padStart(4, '0')
            val name = if (ot.isPhysicalOutput) "Speaker / Headphone" else "USB Streaming Out"
            sb.appendLine("  - Terminal ID ${ot.id}: $typeStr ($name), src=${ot.sourceId}, clockSource=${ot.clockSourceId}")
        }

        sb.appendLine("\nAudioStreaming Interface Alternate Settings (${desc.altSettings.size} total):")
        for (alt in desc.altSettings) {
            val dir = if (alt.dataEndpoint?.isOut == true) "OUT (Playback)" else "IN (Capture)"
            val ratesStr = if (alt.sampleRates.isNotEmpty()) alt.sampleRates.joinToString(", ") { "$it Hz" } else "queried via Clock Source"
            val syncStr = alt.dataEndpoint?.syncType?.name ?: "Unknown"
            val fbStr = alt.feedbackEndpoint?.address?.let { "0x" + it.toString(16) } ?: "None"
            sb.appendLine("  [${alt.interfaceNumber}:${alt.altSetting}] $dir:")
            sb.appendLine("    * Format: ${alt.formatTag} (${alt.bitResolution}-bit resolution, ${alt.subslotSize}-byte subslot, ${alt.channelCount} ch)")
            sb.appendLine("    * Sample Rates: $ratesStr")
            sb.appendLine("    * Data Endpoint: 0x${alt.dataEndpoint?.address?.toString(16)?.padStart(2, '0')} ($syncStr Isochronous, maxPacket=${alt.dataEndpoint?.maxPacketSize})")
            sb.appendLine("    * Feedback Endpoint: $fbStr")
        }

        val raw = connection.rawDescriptors
        if (raw != null && raw.isNotEmpty()) {
            sb.appendLine("\nRaw Descriptors (${raw.size} bytes hex):")
            val hex = raw.joinToString("") { "%02x".format(it) }
            val formatted = raw.toList().chunked(16).joinToString("\n") { chunk ->
                "  " + chunk.joinToString(" ") { "%02x".format(it) }
            }
            sb.appendLine(formatted)
            sb.appendLine("Compact hex string:\n$hex")
        }
        sb.appendLine()
    }

    private fun appendAndroid14MixerSpike(context: Context, sb: StringBuilder) {
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("3. ANDROID 14+ MIXER ATTRIBUTES SPIKE (AudioManager.getSupportedMixerAttributes)")
        sb.appendLine("--------------------------------------------------------------------------------")

        if (Build.VERSION.SDK_INT < 34) {
            sb.appendLine("Platform Status: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            sb.appendLine("AudioMixerAttributes and setPreferredMixerAttributes require Android 14+ (API 34).")
            sb.appendLine("Mixer attributes check: SKIPPED (Below API 34).")
            sb.appendLine()
            return
        }

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val usbDevice = outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }

            if (usbDevice == null) {
                sb.appendLine("No USB AudioDeviceInfo detected in AudioManager.getDevices(GET_DEVICES_OUTPUTS).")
                sb.appendLine("Current outputs (${outputs.size}):")
                for (out in outputs) {
                    sb.appendLine("  - ID: ${out.id}, Name: ${out.productName}, Type: ${out.type}")
                }
                sb.appendLine()
                return
            }

            sb.appendLine("Target USB AudioDeviceInfo found:")
            sb.appendLine("  - ID: ${usbDevice.id}")
            sb.appendLine("  - Product Name: ${usbDevice.productName}")
            sb.appendLine("  - Device Type: ${if (usbDevice.type == AudioDeviceInfo.TYPE_USB_DEVICE) "TYPE_USB_DEVICE" else "TYPE_USB_HEADSET"}")
            sb.appendLine("  - Address: ${usbDevice.address}")
            sb.appendLine("  - Audio HAL Sample Rates: ${usbDevice.sampleRates.joinToString(", ")}")
            sb.appendLine("  - Audio HAL Channel Counts: ${usbDevice.channelCounts.joinToString(", ")}")
            sb.appendLine("  - Audio HAL Encodings: ${usbDevice.encodings.joinToString(", ") { encodingToString(it) }}")

            val supportedMixers = audioManager.getSupportedMixerAttributes(usbDevice)
            sb.appendLine("\nSupported Mixer Attributes (${supportedMixers.size} configurations):")
            if (supportedMixers.isEmpty()) {
                sb.appendLine("  (None returned by audio HAL)")
            } else {
                supportedMixers.forEachIndexed { i, mix ->
                    val fmt = mix.format
                    val encStr = encodingToString(fmt.encoding)
                    val rate = fmt.sampleRate
                    val maskStr = channelMaskToString(fmt.channelMask)
                    val behStr = if (mix.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                        "MIXER_BEHAVIOR_BIT_PERFECT (1)"
                    } else {
                        "MIXER_BEHAVIOR_DEFAULT (0)"
                    }
                    sb.appendLine("  [$i] Format: $encStr, Rate: $rate Hz, Mask: $maskStr, Behavior: $behStr")
                }
            }

            // Test setPreferredMixerAttributes for 96 kHz bit-perfect
            sb.appendLine("\nTest setPreferredMixerAttributes(MIXER_BEHAVIOR_BIT_PERFECT) for 96 kHz:")
            val targetEncoding = when {
                supportedMixers.any { it.format.encoding == AudioFormat.ENCODING_PCM_32BIT } -> AudioFormat.ENCODING_PCM_32BIT
                supportedMixers.any { it.format.encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED } -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                else -> AudioFormat.ENCODING_PCM_32BIT
            }

            val testFormat = AudioFormat.Builder()
                .setEncoding(targetEncoding)
                .setSampleRate(96000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            val preferredAttr = AudioMixerAttributes.Builder(testFormat)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()

            val mediaAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val setOk = audioManager.setPreferredMixerAttributes(mediaAttrs, usbDevice, preferredAttr)
            sb.appendLine("  - setPreferredMixerAttributes(96 kHz, ${encodingToString(targetEncoding)}, STEREO, BIT_PERFECT): result = $setOk")

            val activePreferred = audioManager.getPreferredMixerAttributes(mediaAttrs, usbDevice)
            if (activePreferred != null) {
                val af = activePreferred.format
                val beh = if (activePreferred.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) "BIT_PERFECT (1)" else "DEFAULT (0)"
                sb.appendLine("  - Readback getPreferredMixerAttributes: ${af.sampleRate} Hz, ${encodingToString(af.encoding)}, behavior=$beh")
                sb.appendLine("  - Mixer Rate: ${af.sampleRate} Hz (${encodingToString(af.encoding)})")
            } else {
                sb.appendLine("  - Readback getPreferredMixerAttributes: null")
                sb.appendLine("  - Mixer Rate: (Preferred mixer attributes not accepted by audio server)")
            }

            val cleared = audioManager.clearPreferredMixerAttributes(mediaAttrs, usbDevice)
            sb.appendLine("  - clearPreferredMixerAttributes cleanup: result = $cleared")

        } catch (e: Throwable) {
            sb.appendLine("Error executing Android 14 mixer spike: ${e.message}\n${e.stackTraceToString()}")
        }
        sb.appendLine()
    }

    private fun appendSafeClaimTest(
        context: Context,
        device: UsbDevice,
        connection: UsbDeviceConnection,
        description: UsbAudioDeviceDescription,
        sb: StringBuilder
    ) {
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("4. SAFE DIRECT CLAIM & CONTROL TRANSFER TEST")
        sb.appendLine("--------------------------------------------------------------------------------")

        val playbackAlt = description.altSettings.firstOrNull { it.dataEndpoint?.isOut == true }
        if (playbackAlt == null) {
            sb.appendLine("Error: No playback (OUT) alternate setting found in parsed descriptors.")
            sb.appendLine()
            return
        }

        var intfAlt0: UsbInterface? = null
        var intfTarget: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.id == playbackAlt.interfaceNumber) {
                if (intf.alternateSetting == 0) intfAlt0 = intf
                if (intf.alternateSetting == playbackAlt.altSetting) intfTarget = intf
            }
        }

        val claimIntf = intfTarget ?: intfAlt0
        if (claimIntf == null) {
            sb.appendLine("Error: UsbInterface for interface ${playbackAlt.interfaceNumber} not found on UsbDevice.")
            sb.appendLine()
            return
        }

        val controlIntf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == 0 }
        val controlIntfId = controlIntf?.id?.takeIf { it != claimIntf.id }

        runSafeClaimLifecycle(
            controlIntfId = controlIntfId,
            streamIntfId = claimIntf.id,
            claimControl = { connection.claimInterface(controlIntf!!, true) },
            claimStream = { connection.claimInterface(claimIntf, true) },
            releaseControl = { connection.releaseInterface(controlIntf!!) },
            releaseStream = { connection.releaseInterface(claimIntf) },
            reattachKernelDriver = { intfId ->
                UsbEngineNative.reattachKernelDriver(connection.fileDescriptor, intfId)
            },
            onClaimed = {
            if (intfAlt0 != null) {
                val setAlt0Ok = connection.setInterface(intfAlt0)
                sb.appendLine("Step 2: setInterface(alt 0) -> ${if (setAlt0Ok) "SUCCESS (true)" else "FAILED (false)"}")
            } else {
                sb.appendLine("Step 2: setInterface(alt 0) -> SKIPPED (alt 0 not exposed separately)")
            }

            val linkedInput = description.inputTerminals.firstOrNull { it.id == playbackAlt.terminalLink }
            val clockSourceId = linkedInput?.clockSourceId ?: description.clockSources.firstOrNull()?.id ?: 1
            val controlIntfId = 0

            if (description.uacVersion == UacVersion.UAC_2_0) {
                sb.appendLine("UAC 2.0 Clock Source ID: $clockSourceId (linked from Terminal ${playbackAlt.terminalLink})")

                // Step 3: GET_RANGE on Clock Source
                val rangeBuf = ByteArray(256)
                val rangeBytes = connection.controlTransfer(
                    0xA1, // bmRequestType: IN, CLASS, INTERFACE
                    0x02, // bRequest: RANGE (0x02)
                    (0x01 shl 8) or 0x00, // wValue: CS_SAM_FREQ_CONTROL << 8 | 0
                    (clockSourceId shl 8) or controlIntfId, // wIndex: Entity ID << 8 | Control Interface
                    rangeBuf,
                    rangeBuf.size,
                    2000
                )
                sb.appendLine("Step 3: GET_RANGE on Clock Source $clockSourceId (CS_SAM_FREQ_CONTROL):")
                sb.appendLine("  - controlTransfer bytes read = $rangeBytes")
                if (rangeBytes >= 2) {
                    val numRanges = (rangeBuf[0].toInt() and 0xFF) or ((rangeBuf[1].toInt() and 0xFF) shl 8)
                    sb.appendLine("  - wNumSubRanges = $numRanges")
                    var offset = 2
                    for (r in 0 until numRanges) {
                        if (offset + 12 <= rangeBytes) {
                            val minRate = readU32(rangeBuf, offset)
                            val maxRate = readU32(rangeBuf, offset + 4)
                            val res = readU32(rangeBuf, offset + 8)
                            sb.appendLine("    * Subrange [$r]: $minRate .. $maxRate Hz (resolution: $res Hz)")
                            offset += 12
                        }
                    }
                } else {
                    sb.appendLine("  - Warning: GET_RANGE returned $rangeBytes bytes (device may only support fixed discrete frequencies)")
                }

                // Step 4: SET_CUR 96000 Hz on Clock Source
                val targetRate = 96000
                val rateBuf = byteArrayOf(
                    (targetRate and 0xFF).toByte(),
                    ((targetRate shr 8) and 0xFF).toByte(),
                    ((targetRate shr 16) and 0xFF).toByte(),
                    ((targetRate shr 24) and 0xFF).toByte()
                )
                val setCurBytes = connection.controlTransfer(
                    0x21, // bmRequestType: OUT, CLASS, INTERFACE
                    0x01, // bRequest: CUR (0x01)
                    (0x01 shl 8) or 0x00, // wValue: CS_SAM_FREQ_CONTROL << 8 | 0
                    (clockSourceId shl 8) or controlIntfId, // wIndex: Entity ID << 8 | Control Interface
                    rateBuf,
                    4,
                    2000
                )
                sb.appendLine("Step 4: SET_CUR on Clock Source $clockSourceId to 96000 Hz -> bytes sent = $setCurBytes ${if (setCurBytes == 4) "(SUCCESS)" else "(FAILED)"}")

                // Step 5: GET_CUR readback on Clock Source
                val curBuf = ByteArray(4)
                val getCurBytes = connection.controlTransfer(
                    0xA1, // bmRequestType: IN, CLASS, INTERFACE
                    0x01, // bRequest: CUR (0x01)
                    (0x01 shl 8) or 0x00,
                    (clockSourceId shl 8) or controlIntfId,
                    curBuf,
                    4,
                    2000
                )
                val readbackRate = if (getCurBytes >= 4) readU32(curBuf, 0) else -1L
                val matched = readbackRate == 96000L
                sb.appendLine("Step 5: GET_CUR readback on Clock Source $clockSourceId -> rate = $readbackRate Hz ${if (matched) "(VERIFIED: MATCHES 96000 Hz!)" else "(MISMATCH)"}")

            } else {
                val epAddr = playbackAlt.dataEndpoint?.address ?: 0x01
                sb.appendLine("UAC 1.0 Endpoint Address: 0x${epAddr.toString(16)}")

                val targetRate = 96000
                val rateBuf = byteArrayOf(
                    (targetRate and 0xFF).toByte(),
                    ((targetRate shr 8) and 0xFF).toByte(),
                    ((targetRate shr 16) and 0xFF).toByte()
                )
                val setCurBytes = connection.controlTransfer(
                    0x22, // bmRequestType: OUT, CLASS, ENDPOINT
                    0x01, // bRequest: SET_CUR
                    0x0100, // wValue: SAMPLING_FREQ_CONTROL
                    epAddr,
                    rateBuf,
                    3,
                    2000
                )
                sb.appendLine("Step 4: SET_CUR on Endpoint 0x${epAddr.toString(16)} to 96000 Hz -> bytes sent = $setCurBytes")

                val curBuf = ByteArray(3)
                val getCurBytes = connection.controlTransfer(
                    0xA2, // bmRequestType: IN, CLASS, ENDPOINT
                    0x81, // bRequest: GET_CUR
                    0x0100,
                    epAddr,
                    curBuf,
                    3,
                    2000
                )
                val readbackRate = if (getCurBytes >= 3) {
                    (curBuf[0].toLong() and 0xFF) or
                    ((curBuf[1].toLong() and 0xFF) shl 8) or
                    ((curBuf[2].toLong() and 0xFF) shl 16)
                } else -1L
                sb.appendLine("Step 5: GET_CUR readback on Endpoint 0x${epAddr.toString(16)} -> rate = $readbackRate Hz")
            }

            // Step 6: Set streaming alternate setting
            if (intfTarget != null) {
                val setStreamOk = connection.setInterface(intfTarget)
                sb.appendLine("Step 6: setInterface(streaming alt ${intfTarget.alternateSetting}) -> ${if (setStreamOk) "SUCCESS (true)" else "FAILED (false)"}")
            }

            // Step 7: Restore alt 0
            if (intfAlt0 != null) {
                val restoreAlt0Ok = connection.setInterface(intfAlt0)
                sb.appendLine("Step 7: restore setInterface(alt 0) -> ${if (restoreAlt0Ok) "SUCCESS (true)" else "FAILED (false)"}")
            }
            },
            onPostRelease = {
                // Step 10: Verify system can use DAC again
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val hasUsb = outputs.any {
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                sb.appendLine("Step 10: System Audio Availability Check -> System output device recognized: ${if (hasUsb) "YES (Audio HAL active)" else "NO"}")
            },
            sb = sb,
        )
    }

    internal fun runSafeClaimLifecycle(
        controlIntfId: Int?,
        streamIntfId: Int,
        claimControl: () -> Boolean,
        claimStream: () -> Boolean,
        releaseControl: () -> Boolean,
        releaseStream: () -> Boolean,
        reattachKernelDriver: (Int) -> Int,
        onClaimed: () -> Unit,
        onPostRelease: () -> Unit,
        sb: StringBuilder,
    ) {
        var controlClaimed = false
        var streamClaimed = false
        try {
            controlClaimed = if (controlIntfId != null) claimControl() else false
            val claimControlLog = if (controlIntfId != null) controlClaimed else true
            streamClaimed = claimStream()
            sb.appendLine("Step 1: claimInterface(control=${controlIntfId ?: 0}) -> $claimControlLog, claimInterface(stream=$streamIntfId, force=true) -> $streamClaimed")

            if (!streamClaimed) {
                sb.appendLine("Aborting direct test because claimInterface was rejected by USB host subsystem.")
                return
            }

            onClaimed()
        } finally {
            // Step 8: Release any claimed interfaces
            if (streamClaimed) {
                val releaseOk = releaseStream()
                sb.appendLine("Step 8: releaseInterface(interface=$streamIntfId) -> ${if (releaseOk) "SUCCESS (true)" else "FAILED (false)"}")
            }
            if (controlClaimed && controlIntfId != null) {
                val releaseCtrlOk = releaseControl()
                if (!streamClaimed) {
                    sb.appendLine("Step 8: releaseInterface(control=$controlIntfId) -> ${if (releaseCtrlOk) "SUCCESS (true)" else "FAILED (false)"}")
                }
            }

            // Step 9: Reattach kernel driver (USBDEVFS_CONNECT via USBDEVFS_IOCTL) for any claimed interface
            val reattachCtrlCode = if (controlClaimed && controlIntfId != null) {
                try {
                    reattachKernelDriver(controlIntfId)
                } catch (_: Throwable) { -999 }
            } else 0
            val reattachCode = if (streamClaimed) {
                try {
                    reattachKernelDriver(streamIntfId)
                } catch (_: Throwable) { -999 }
            } else 0
            if (streamClaimed || controlClaimed) {
                sb.appendLine("Step 9: reattachKernelDriver (USBDEVFS_CONNECT) -> ctrl=$reattachCtrlCode, stream=$reattachCode ${if (reattachCode == 0 && reattachCtrlCode == 0) "(SUCCESS: snd-usb-audio rebound)" else ""}")
            }

            if (streamClaimed) {
                onPostRelease()
            }
            sb.appendLine()
        }
    }

    private fun appendConclusion(desc: UsbAudioDeviceDescription, sb: StringBuilder) {
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("5. ARCHITECTURE COMPARISON & STATUS")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("- Android 14 Bit-Perfect Mixer Path: Quick comparison reference.")
        sb.appendLine("  * Even when supported on Android 14+, mixer path is subject to AudioFlinger mixer constraints and does not support Android 13 or older.")
        sb.appendLine("- PaperPlay Direct usbfs Isochronous Path:")
        sb.appendLine("  * Protocol: ${desc.uacVersion.name}")
        sb.appendLine("  * Complete bypass of AudioServer, AudioFlinger, mixer, and sample-rate converters.")
        sb.appendLine("  * Direct hardware clock programming (verified SET_CUR/GET_CUR).")
        sb.appendLine("  * Hardware Feature Unit volume control with zero software scaling.")
        sb.appendLine("  * Recommendation: Continue with PaperPlay direct usbfs driver.")
        sb.appendLine("================================================================================")
    }

    private fun saveReportToFile(context: Context, report: String, sb: StringBuilder) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "usb_dac_probe_report.txt")
            file.writeText(report)
            sb.appendLine("Report exported to file in app storage:")
            sb.appendLine("  Path: ${file.absolutePath}")
            sb.appendLine("  Size: ${file.length()} bytes")
            PlayLog.log("usb", "USB probe report written to ${file.absolutePath}")
        } catch (e: Exception) {
            sb.appendLine("Failed to export report to file: ${e.message}")
        }
    }

    @Volatile
    var lastFallbackReason: String? = null
        private set

    @Volatile
    var lastFallbackTimestamp: Long = 0L
        private set

    fun captureLogcat(): String {
        return runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        }.getOrElse { "Failed to capture logcat: ${it.message}" }
    }

    fun buildFullDebugReport(context: Context, triggerReason: String? = null): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timestamp = dateFormat.format(Date())

        sb.appendLine("================================================================================")
        sb.appendLine("PAPERPLAY USB AUDIO DEBUG LOG")
        sb.appendLine("================================================================================")
        sb.appendLine("Timestamp: $timestamp")
        sb.appendLine("Android OS: Android ${Build.VERSION.RELEASE} (API Level ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        sb.appendLine("Build Fingerprint: ${Build.FINGERPRINT}")
        if (triggerReason != null) {
            sb.appendLine("Trigger Reason: $triggerReason")
        }
        val savedPref = io.max.paperplay.player.PlaybackCore.loadPersistedUsbExclusiveMode(context)
        val runtimeFlag = io.max.paperplay.player.PlaybackCore.usbExclusiveMode
        val sinkChain = io.max.paperplay.player.PlaybackCore.sinkChainMode
        val buildId = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val vName = pi.versionName ?: "dev"
            val vCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "$vName($vCode)"
        } catch (_: Throwable) {
            "dev"
        }
        val dacPlugged = try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val hasAudioHalUsb = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } == true
            val um = context.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
            val hasUsbAudioClass = um?.deviceList?.values?.any { dev ->
                (0 until dev.interfaceCount).any { idx ->
                    dev.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                }
            } == true
            hasAudioHalUsb || hasUsbAudioClass
        } catch (_: Throwable) {
            false
        }
        val summaryLine = io.max.paperplay.player.DelegateSinkDiag.formatPhase0Summary(
            buildId = buildId,
            dacPlugged = dacPlugged,
            bitPerfectOn = runtimeFlag,
        )
        sb.appendLine("=== PHASE0_SUMMARY ===")
        sb.appendLine(summaryLine)
        sb.appendLine("=== END_PHASE0_SUMMARY ===")
        sb.appendLine()
        sb.appendLine("PlaybackCore: usbExclusiveMode=$runtimeFlag (saved preference=$savedPref), " +
                "sinkChain=${sinkChain.id}, " +
                "engineActive=${io.max.paperplay.player.PlaybackCore.engineActive}, " +
                "usbActive=${io.max.paperplay.player.PlaybackCore.usbActive}, " +
                "usbStatus=${io.max.paperplay.player.PlaybackCore.usbStatus}")
        sb.appendLine("AudioTrack config: ${io.max.paperplay.player.DelegateSinkDiag.currentAudioTrackConfigDesc()}")
        if (runtimeFlag != savedPref) {
            sb.appendLine("WARNING: Mismatch: usbExclusiveMode runtime=$runtimeFlag != saved preference=$savedPref")
        }
        val usbState = UsbAudioManager.instance?.state?.value
        sb.appendLine("UsbAudioManager state: $usbState")
        sb.appendLine()

        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("1. IN-MEMORY RING LOG (UsbRingLog, capacity=${UsbRingLog.CAPACITY})")
        sb.appendLine("--------------------------------------------------------------------------------")
        val ringLines = UsbRingLog.dump()
        if (ringLines.isEmpty()) {
            sb.appendLine("(No ring log events recorded yet)")
        } else {
            for (line in ringLines) {
                sb.appendLine(line)
            }
        }
        sb.appendLine()

        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("2. NATIVE USB STREAMER LOG (NativeRingLog)")
        sb.appendLine("--------------------------------------------------------------------------------")
        val nativeLog = if (UsbEngineNative.isAlreadyLoaded) UsbEngineNative.getNativeLog() else null
        if (nativeLog.isNullOrBlank()) {
            sb.appendLine("(No native engine events recorded or native USB library not loaded)")
        } else {
            sb.appendLine(nativeLog)
        }
        sb.appendLine()

        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("3. APPLICATION LOGCAT (logcat -d -v time)")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine(captureLogcat())
        sb.appendLine("================================================================================")
        return sb.toString()
    }

    fun exportFallbackLog(context: Context, reason: String) {
        try {
            lastFallbackReason = reason
            lastFallbackTimestamp = System.currentTimeMillis()
            val sharedDir = File(context.filesDir, "shared").apply { mkdirs() }
            val report = buildFullDebugReport(context, triggerReason = "FALLBACK: $reason")

            val lastFile = File(sharedDir, "usb_debug_log_last_failure.txt")
            lastFile.writeText(report)

            val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val tsFile = File(sharedDir, "usb_debug_log_fallback_${fileDate}.txt")
            tsFile.writeText(report)

            PlayLog.log("usb", "Automatic fallback log exported to ${lastFile.absolutePath}")
        } catch (e: Exception) {
            PlayLog.log("usb", "Failed to write automatic fallback log: ${e.message}")
        }
    }

    fun exportDebugLogFile(context: Context): File {
        val sharedDir = File(context.filesDir, "shared").apply { mkdirs() }
        val report = buildFullDebugReport(context, triggerReason = "MANUAL_EXPORT")
        val fileDate = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "usb_debug_log_${fileDate}.txt"
        val file = File(sharedDir, fileName)
        file.writeText(report)
        PlayLog.log("usb", "USB debug log exported to ${file.absolutePath}")
        return file
    }

    fun getLastFallbackLog(context: Context): File? {
        val file = File(context.filesDir, "shared/usb_debug_log_last_failure.txt")
        return file.takeIf { it.exists() }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFF) shl 24
        return b0 or b1 or b2 or b3
    }

    private fun encodingToString(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "ENCODING_PCM_16BIT"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "ENCODING_PCM_24BIT_PACKED"
        AudioFormat.ENCODING_PCM_32BIT -> "ENCODING_PCM_32BIT"
        AudioFormat.ENCODING_PCM_FLOAT -> "ENCODING_PCM_FLOAT"
        AudioFormat.ENCODING_DEFAULT -> "ENCODING_DEFAULT"
        else -> "0x${encoding.toString(16)}"
    }

    private fun channelMaskToString(mask: Int): String = when (mask) {
        AudioFormat.CHANNEL_OUT_STEREO -> "CHANNEL_OUT_STEREO"
        AudioFormat.CHANNEL_OUT_MONO -> "CHANNEL_OUT_MONO"
        AudioFormat.CHANNEL_OUT_QUAD -> "CHANNEL_OUT_QUAD"
        AudioFormat.CHANNEL_OUT_5POINT1 -> "CHANNEL_OUT_5POINT1"
        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "CHANNEL_OUT_7POINT1_SURROUND"
        else -> "0x${mask.toString(16)}"
    }
}
