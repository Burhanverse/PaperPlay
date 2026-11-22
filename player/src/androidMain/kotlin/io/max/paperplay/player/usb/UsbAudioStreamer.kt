package io.max.paperplay.player.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import io.max.paperplay.player.PlayLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbClaimException(message: String) : Exception(message)

/**
 * Coordinates USB Audio streaming between Android's UsbDeviceConnection and the native Rust streamer.
 *
 * Sequence:
 * 1. Obtains the open Linux file descriptor from UsbDeviceConnection (no Kotlin claimInterface).
 * 2. Passes the fd directly to Rust rusb (open_device_with_fd).
 * 3. Rust detaches kernel drivers (snd-usb-audio) and claims all Audio class interfaces (0, 1, and 2 if present).
 * 4. Rust selects alt 0, programs clock/sample-rate, verifies with GET_CUR, and selects target alt setting.
 * 5. Allocates native streamer and ring buffer (<= 1 MB) without starting URB streaming loop.
 * 6. start(): Launches high-priority real-time native isochronous streaming thread once primed.
 * 7. Keeps the session across tracks; releases interfaces and reattaches drivers on native destroy.
 */
class UsbAudioStreamer(
    val device: UsbDevice,
    val connection: UsbDeviceConnection,
    val audioStreamingInterface: UsbInterface,
    val alt0Interface: UsbInterface?,
    targetAltInterface: UsbInterface,
    val audioEndpoint: UsbEndpoint,
    val feedbackEndpoint: UsbEndpoint?,
    val isUac2: Boolean,
    val clockSourceId: Int,
    val controlInterfaceId: Int,
    sampleRate: Int,
    bitDepth: Int,
    channels: Int,
    subslotSize: Int,
    val isHighSpeed: Boolean,
    isDop: Boolean,
    private val engineBridge: UsbEngineBridge = UsbEngineNative
) : UsbAudioSinkBridge {
    var targetAltInterface: UsbInterface = targetAltInterface
        private set
    var sampleRate: Int = sampleRate
        private set
    var bitDepth: Int = bitDepth
        private set
    var channels: Int = channels
        private set
    var subslotSize: Int = subslotSize
        private set
    var isDop: Boolean = isDop
        private set

    @Volatile
    private var _nativeConfirmedRate: Int = 0
    override val confirmedRate: Int get() = _nativeConfirmedRate

    @Volatile
    private var _nativeConfirmedAlt: Int = -1
    override val confirmedAlt: Int get() = _nativeConfirmedAlt

    @Volatile
    private var nativeHandle: Long = 0L
    val handle: Long get() = nativeHandle
    private val statsBuffers = ThreadLocal.withInitial { LongArray(9) }

    override var isPrepared: Boolean = false
        private set

    @Volatile
    private var _isRunning: Boolean = false
    override val isRunning: Boolean
        get() = nativeHandle != 0L && _isRunning

    @Volatile
    private var _isPaused: Boolean = false
    override val isPaused: Boolean
        get() = _isPaused

    override fun prepare(): Boolean = synchronized(globalStreamerLock) {
        if (isPrepared && nativeHandle != 0L) return true
        val current = currentPreparedStreamer
        if (current != null && current !== this && current.isPrepared && current.nativeHandle != 0L) {
            if (current.device.deviceId == device.deviceId &&
                current.sampleRate == sampleRate &&
                current.bitDepth == bitDepth &&
                current.channels == channels &&
                current.subslotSize == subslotSize &&
                current.targetAltInterface.alternateSetting == targetAltInterface.alternateSetting &&
                current.isDop == isDop
            ) {
                PlayLog.log("usb", "UsbAudioStreamer.prepare: reusing already prepared session for device ${device.deviceId} ($sampleRate Hz, alt ${targetAltInterface.alternateSetting})")
                UsbRingLog.record("UsbAudioStreamer", "Reusing prepared session: $sampleRate Hz, alt ${targetAltInterface.alternateSetting}", UsbRingLog.CODE_GENERIC)
                nativeHandle = current.nativeHandle
                _nativeConfirmedRate = current._nativeConfirmedRate
                _nativeConfirmedAlt = current._nativeConfirmedAlt
                isPrepared = true
                currentPreparedStreamer = this
                return true
            } else {
                PlayLog.log("usb", "UsbAudioStreamer.prepare: stopping previous session with different parameters")
                current.stopAndRelease()
            }
        }

        if (!engineBridge.available) {
            PlayLog.log("usb", "Native USB engine library not available")
            UsbRingLog.record("UsbAudioStreamer", "libengine not available", UsbRingLog.CODE_ERROR)
            return false
        }

        val fd = connection.fileDescriptor
        val fbEp = feedbackEndpoint?.address ?: 0

        nativeHandle = engineBridge.create(
            fd = fd,
            endpoint = audioEndpoint.address,
            streamingIface = audioStreamingInterface.id,
            targetAlt = targetAltInterface.alternateSetting,
            isUac2 = isUac2,
            clockSourceId = clockSourceId,
            controlIface = controlInterfaceId,
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            channels = channels,
            subslotSize = subslotSize,
            isHighSpeed = isHighSpeed,
            isDop = isDop,
            feedbackEp = fbEp
        )

        if (nativeHandle == 0L) {
            PlayLog.log("usb", "Failed to create and configure native USB streamer instance")
            UsbRingLog.record("UsbAudioStreamer", "Failed to create native USB streamer instance", UsbRingLog.CODE_ERROR)
            return false
        }

        // Read back rate and alt from native side to verify DAC state
        val readRate = engineBridge.getCurrentRate(nativeHandle)
        val readAlt = engineBridge.getCurrentAlt(nativeHandle)
        _nativeConfirmedRate = if (readRate > 0) readRate else sampleRate
        _nativeConfirmedAlt = if (readAlt >= 0) readAlt else targetAltInterface.alternateSetting

        if (readRate > 0 && readRate != sampleRate) {
            PlayLog.log("usb", "Rate mismatch in prepare: requested $sampleRate Hz, DAC readback $readRate Hz; recreating session")
            UsbRingLog.record("UsbAudioStreamer", "Rate mismatch in prepare: req=$sampleRate read=$readRate", UsbRingLog.CODE_ERROR)
            engineBridge.stop(nativeHandle)
            engineBridge.destroy(nativeHandle)
            nativeHandle = 0L
            isPrepared = false
            return false
        }
        if (readAlt >= 0 && readAlt != targetAltInterface.alternateSetting) {
            PlayLog.log("usb", "Alt mismatch in prepare: requested alt ${targetAltInterface.alternateSetting}, DAC readback alt $readAlt; recreating session")
            UsbRingLog.record("UsbAudioStreamer", "Alt mismatch in prepare: req=${targetAltInterface.alternateSetting} read=$readAlt", UsbRingLog.CODE_ERROR)
            engineBridge.stop(nativeHandle)
            engineBridge.destroy(nativeHandle)
            nativeHandle = 0L
            isPrepared = false
            return false
        }

        isPrepared = true
        currentPreparedStreamer = this
        PlayLog.log("usb", "USB audio session prepared: $sampleRate Hz, ${bitDepth}-bit, $channels ch, subslot $subslotSize, alt ${targetAltInterface.alternateSetting} (confirmed: rate=$_nativeConfirmedRate alt=$_nativeConfirmedAlt)")
        UsbRingLog.record("UsbAudioStreamer", "Session prepared: $sampleRate Hz, ${bitDepth}-bit, alt ${targetAltInterface.alternateSetting} (confirmed: $_nativeConfirmedRate Hz)", UsbRingLog.CODE_GENERIC)

        // Invalidate volume cache so initial volume is always sent to the freshly claimed session
        UsbAudioManager.instance?.hardwareVolume?.invalidateVolumeCache()
        // Apply initial hardware volume immediately before unmuting / streaming
        UsbAudioManager.instance?.forceApplyHardwareVolume()
        return true
    }

    @Synchronized
    override fun start(): Boolean {
        if (!isPrepared) {
            val prep = try {
                prepare()
            } catch (t: Throwable) {
                PlayLog.log("usb", "prepare() failed in start(): ${t.message}")
                false
            }
            if (!prep) return false
        }
        if (isRunning) return true

        val h = nativeHandle
        if (h == 0L) return false

        // Force write and verify current hardware volume before submitting the first URB
        UsbAudioManager.instance?.forceApplyHardwareVolume()

        val ret = engineBridge.start(h)
        if (ret < 0) {
            PlayLog.log("usb", "Failed to start native streaming thread (code $ret)")
            UsbRingLog.record("UsbAudioStreamer", "Failed to start native streaming thread (code $ret)", UsbRingLog.CODE_ERROR)
            return false
        }
        engineBridge.resume(h)

        _isRunning = true
        _isPaused = false
        PlayLog.log("usb", "USB audio streaming started ($sampleRate Hz, ${bitDepth}-bit, $channels ch, subslot $subslotSize, confirmed: rate=$_nativeConfirmedRate alt=$_nativeConfirmedAlt)")
        UsbRingLog.record("UsbAudioStreamer", "Playback started: $sampleRate Hz, ${bitDepth}-bit, alt ${targetAltInterface.alternateSetting} (confirmed: $_nativeConfirmedRate Hz)", UsbRingLog.CODE_SINK_PLAY)
        return true
    }

    @Synchronized
    override fun pause() {
        _isPaused = true
        val h = nativeHandle
        if (h != 0L) {
            engineBridge.pause(h)
        }
    }

    @Synchronized
    override fun resume() {
        _isPaused = false
        val h = nativeHandle
        if (h != 0L) {
            engineBridge.resume(h)
        }
    }

    @Synchronized
    override fun flush() {
        val h = nativeHandle
        if (h != 0L) {
            engineBridge.flush(h)
            UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_FLUSH)
        }
    }

    /**
     * Write PCM or DoP data from direct ByteBuffer into the lock-free ring buffer.
     * Non-blocking, returns number of bytes written.
     */
    override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
        val h = nativeHandle
        if (h == 0L) return -1
        return engineBridge.write(h, buffer, offset, length)
    }

    /**
     * Poll runtime statistics from the native streaming thread (lock-free).
     */
    override fun getStats(): UsbStreamStats? {
        val h = nativeHandle
        if (h == 0L) return null
        val buf = statsBuffers.get() ?: return null
        engineBridge.getStats(h, buf)
        val nativePaused = buf[7] != 0L
        val nativeFlushing = buf[8] != 0L
        return UsbStreamStats(
            framesConsumed = buf[0],
            underruns = buf[1],
            urbErrors = buf[2],
            ringFillBytes = buf[3],
            ringCapacity = buf[4],
            isRunning = buf[5] != 0L,
            sampleRate = buf[6].toInt(),
            isPaused = nativePaused || _isPaused,
            isFlushing = nativeFlushing
        )
    }

    override fun switchRate(newRate: Int): Boolean = synchronized(globalStreamerLock) {
        if (newRate == sampleRate && isPrepared) return true

        val wasRunning = isRunning
        val fbEp = feedbackEndpoint?.address ?: 0

        // Try in-place native reconfigure without reopening the USB device
        if (nativeHandle != 0L) {
            val reconfRes = engineBridge.reconfigure(
                handle = nativeHandle,
                endpoint = audioEndpoint.address,
                streamingIface = audioStreamingInterface.id,
                targetAlt = targetAltInterface.alternateSetting,
                sampleRate = newRate,
                bitDepth = bitDepth,
                channels = channels,
                subslotSize = subslotSize,
                isHighSpeed = isHighSpeed,
                isDop = isDop,
                feedbackEp = fbEp
            )
            if (reconfRes == 0) {
                val readRate = engineBridge.getCurrentRate(nativeHandle)
                val readAlt = engineBridge.getCurrentAlt(nativeHandle)
                if ((readRate <= 0 || readRate == newRate) && (readAlt < 0 || readAlt == targetAltInterface.alternateSetting)) {
                    this.sampleRate = newRate
                    _nativeConfirmedRate = if (readRate > 0) readRate else newRate
                    _nativeConfirmedAlt = if (readAlt >= 0) readAlt else targetAltInterface.alternateSetting
                    _isRunning = false
                    _isPaused = false
                    isPrepared = true
                    currentPreparedStreamer = this
                    UsbAudioManager.instance?.hardwareVolume?.invalidateVolumeCache()
                    UsbAudioManager.instance?.forceApplyHardwareVolume()
                    return if (wasRunning) start() else true
                }
            }
            // In-place reconfigure failed or readback mismatched: fall back to full recreate
            if (isRunning) {
                engineBridge.stop(nativeHandle)
            }
            engineBridge.destroy(nativeHandle)
            nativeHandle = 0L
            isPrepared = false
        }
        _isRunning = false
        _isPaused = false

        val fd = connection.fileDescriptor
        nativeHandle = engineBridge.create(
            fd = fd,
            endpoint = audioEndpoint.address,
            streamingIface = audioStreamingInterface.id,
            targetAlt = targetAltInterface.alternateSetting,
            isUac2 = isUac2,
            clockSourceId = clockSourceId,
            controlIface = controlInterfaceId,
            sampleRate = newRate,
            bitDepth = bitDepth,
            channels = channels,
            subslotSize = subslotSize,
            isHighSpeed = isHighSpeed,
            isDop = isDop,
            feedbackEp = fbEp
        )
        if (nativeHandle == 0L) return false

        this.sampleRate = newRate
        val readRate = engineBridge.getCurrentRate(nativeHandle)
        val readAlt = engineBridge.getCurrentAlt(nativeHandle)
        _nativeConfirmedRate = if (readRate > 0) readRate else newRate
        _nativeConfirmedAlt = if (readAlt >= 0) readAlt else targetAltInterface.alternateSetting

        if ((readRate > 0 && readRate != newRate) || (readAlt >= 0 && readAlt != targetAltInterface.alternateSetting)) {
            engineBridge.stop(nativeHandle)
            engineBridge.destroy(nativeHandle)
            nativeHandle = 0L
            isPrepared = false
            return false
        }

        isPrepared = true
        currentPreparedStreamer = this
        UsbAudioManager.instance?.hardwareVolume?.invalidateVolumeCache()
        UsbAudioManager.instance?.forceApplyHardwareVolume()

        if (wasRunning) {
            return start()
        }
        return true
    }

    override fun reconfigure(altSetting: UsbAudioAltSetting, rate: Int, isDop: Boolean): Boolean = synchronized(globalStreamerLock) {
        if (rate == this.sampleRate &&
            altSetting.altSetting == this.targetAltInterface.alternateSetting &&
            altSetting.bitResolution == this.bitDepth &&
            altSetting.channelCount == this.channels &&
            altSetting.subslotSize == this.subslotSize &&
            isDop == this.isDop &&
            isPrepared &&
            nativeHandle != 0L
        ) {
            return true
        }

        val wasRunning = isRunning
        val fbEp = feedbackEndpoint?.address ?: 0
        val matchedIface = device.findInterface(altSetting.interfaceNumber, altSetting.altSetting) ?: targetAltInterface
        var reusedInPlace = false

        // 1. Prefer in-place native reconfigure without releasing interfaces or reopening the device
        if (nativeHandle != 0L) {
            val reconfRes = engineBridge.reconfigure(
                handle = nativeHandle,
                endpoint = audioEndpoint.address,
                streamingIface = altSetting.interfaceNumber,
                targetAlt = altSetting.altSetting,
                sampleRate = rate,
                bitDepth = altSetting.bitResolution,
                channels = altSetting.channelCount,
                subslotSize = altSetting.subslotSize,
                isHighSpeed = isHighSpeed,
                isDop = isDop,
                feedbackEp = fbEp
            )
            if (reconfRes == 0) {
                val readRate = engineBridge.getCurrentRate(nativeHandle)
                val readAlt = engineBridge.getCurrentAlt(nativeHandle)
                if ((readRate <= 0 || readRate == rate) && (readAlt < 0 || readAlt == altSetting.altSetting)) {
                    reusedInPlace = true
                    _nativeConfirmedRate = if (readRate > 0) readRate else rate
                    _nativeConfirmedAlt = if (readAlt >= 0) readAlt else altSetting.altSetting
                } else {
                    PlayLog.log("usb", "In-place reconfigure readback mismatch: req=($rate Hz, alt ${altSetting.altSetting}) read=($readRate Hz, alt $readAlt); recreating session")
                    UsbRingLog.record("UsbAudioStreamer", "In-place reconfigure mismatch: req=($rate,alt ${altSetting.altSetting}) read=($readRate,alt $readAlt)", UsbRingLog.CODE_ERROR)
                }
            }
            if (!reusedInPlace) {
                if (isRunning) {
                    engineBridge.stop(nativeHandle)
                }
                engineBridge.destroy(nativeHandle)
                nativeHandle = 0L
                isPrepared = false
            }
        }
        _isRunning = false
        _isPaused = false

        // 2. Fallback: recreate full session if in-place reconfigure was not possible or mismatched
        if (!reusedInPlace) {
            val fd = connection.fileDescriptor
            nativeHandle = engineBridge.create(
                fd = fd,
                endpoint = audioEndpoint.address,
                streamingIface = altSetting.interfaceNumber,
                targetAlt = altSetting.altSetting,
                isUac2 = isUac2,
                clockSourceId = clockSourceId,
                controlIface = controlInterfaceId,
                sampleRate = rate,
                bitDepth = altSetting.bitResolution,
                channels = altSetting.channelCount,
                subslotSize = altSetting.subslotSize,
                isHighSpeed = isHighSpeed,
                isDop = isDop,
                feedbackEp = fbEp
            )
            if (nativeHandle == 0L) {
                PlayLog.log("usb", "reconfigure: Failed to create native streamer instance")
                UsbRingLog.record("UsbAudioStreamer", "reconfigure: Failed to create native streamer instance", UsbRingLog.CODE_ERROR)
                return false
            }

            // Read back rate and alt from native side to verify
            val readRate = engineBridge.getCurrentRate(nativeHandle)
            val readAlt = engineBridge.getCurrentAlt(nativeHandle)
            _nativeConfirmedRate = if (readRate > 0) readRate else rate
            _nativeConfirmedAlt = if (readAlt >= 0) readAlt else altSetting.altSetting

            if (readRate > 0 && readRate != rate) {
                PlayLog.log("usb", "Rate mismatch in reconfigure: requested $rate Hz, DAC readback $readRate Hz; aborting session")
                UsbRingLog.record("UsbAudioStreamer", "Rate mismatch in reconfigure: req=$rate read=$readRate", UsbRingLog.CODE_ERROR)
                engineBridge.stop(nativeHandle)
                engineBridge.destroy(nativeHandle)
                nativeHandle = 0L
                isPrepared = false
                return false
            }
            if (readAlt >= 0 && readAlt != altSetting.altSetting) {
                PlayLog.log("usb", "Alt mismatch in reconfigure: requested alt ${altSetting.altSetting}, DAC readback alt $readAlt; aborting session")
                UsbRingLog.record("UsbAudioStreamer", "Alt mismatch in reconfigure: req=${altSetting.altSetting} read=$readAlt", UsbRingLog.CODE_ERROR)
                engineBridge.stop(nativeHandle)
                engineBridge.destroy(nativeHandle)
                nativeHandle = 0L
                isPrepared = false
                return false
            }
        }

        // Update fields to the new active values
        this.sampleRate = rate
        this.bitDepth = altSetting.bitResolution
        this.channels = altSetting.channelCount
        this.subslotSize = altSetting.subslotSize
        this.isDop = isDop
        this.targetAltInterface = matchedIface

        isPrepared = true
        currentPreparedStreamer = this
        val modeTag = if (reusedInPlace) "in-place" else "recreated"
        PlayLog.log("usb", "USB session reconfigured ($modeTag): $rate Hz, alt ${altSetting.altSetting} (confirmed: rate=$_nativeConfirmedRate alt=$_nativeConfirmedAlt)")
        UsbRingLog.record("UsbAudioStreamer", "Session reconfigured ($modeTag): $rate Hz, alt ${altSetting.altSetting} (confirmed: $_nativeConfirmedRate Hz)", UsbRingLog.CODE_GENERIC)

        // Invalidate volume cache and force write + verify before restarting streaming
        UsbAudioManager.instance?.hardwareVolume?.invalidateVolumeCache()
        UsbAudioManager.instance?.forceApplyHardwareVolume()

        if (wasRunning) {
            return start()
        }
        return true
    }

    /**
     * Standalone hardware verification: generates and queues a 440 Hz sine tone (1) or 24-bit/96kHz ramp (2).
     */
    fun playDebugTest(type: Int, durationMs: Int): Int {
        val h = nativeHandle
        if (h == 0L) return -1
        return engineBridge.playDebugTest(h, type, durationMs)
    }

    /**
     * Teardown streaming and destroy native session, returning interface to alt 0 and releasing claims.
     */
    override fun stopAndRelease() = synchronized(globalStreamerLock) {
        val h = nativeHandle
        nativeHandle = 0L
        _isRunning = false
        _isPaused = false
        isPrepared = false
        _nativeConfirmedRate = 0
        _nativeConfirmedAlt = -1
        if (currentPreparedStreamer === this) {
            currentPreparedStreamer = null
        }
        if (h != 0L) {
            // 1. Stop streaming thread first so no isochronous URBs are in flight
            engineBridge.stop(h)
            // 2. Restore DAC feature units (mute + volume) WHILE interface 0 is still claimed,
            //    verified with GET_CUR readback before releasing interfaces.
            try {
                engineBridge.restoreDefaultVolume(h)
            } catch (t: Throwable) {
                PlayLog.log("usb", "Error restoring DAC default volume: ${t.message}")
            }
            // 3. Destroy native session (sets alt 0, releases all interfaces, reattaches kernel driver)
            engineBridge.destroy(h)
            PlayLog.log("usb", "USB audio interfaces released cleanly by native engine")
            UsbRingLog.record("UsbAudioStreamer", "USB audio interfaces released cleanly", UsbRingLog.CODE_GENERIC)
        }
    }

    companion object {
        private val globalStreamerLock = Any()
        @Volatile
        private var currentPreparedStreamer: UsbAudioStreamer? = null

        private fun UsbDevice.findInterface(interfaceId: Int, altSetting: Int): UsbInterface? {
            for (i in 0 until interfaceCount) {
                val iface = getInterface(i)
                if (iface.id == interfaceId && iface.alternateSetting == altSetting) {
                    return iface
                }
            }
            return null
        }

        data class ControlTransferResult(
            val bytesTransferred: Int,
            val errno: Int,
            val errnoName: String,
            val isSuccess: Boolean
        )

        fun errnoName(errno: Int): String = when (errno) {
            0 -> "SUCCESS"
            1 -> "EPERM"
            11 -> "EAGAIN"
            13 -> "EACCES"
            16 -> "EBUSY"
            19 -> "ENODEV"
            22 -> "EINVAL"
            32 -> "EPIPE"
            104 -> "ECONNRESET"
            108 -> "ESHUTDOWN"
            110 -> "ETIMEDOUT"
            else -> "ERRNO_$errno"
        }

        fun executeControlTransfer(
            connection: UsbDeviceConnection,
            bmRequestType: Int,
            bRequest: Int,
            wValue: Int,
            wIndex: Int,
            data: ByteArray,
            length: Int,
            timeoutMs: Int = 1000
        ): ControlTransferResult {
            val fd = connection.fileDescriptor
            var bytesTransferred = -1
            var errno = 0

            if (fd >= 0 && UsbEngineNative.available) {
                val ret = UsbEngineNative.controlTransfer(
                    fd = fd,
                    requestType = bmRequestType,
                    request = bRequest,
                    value = wValue,
                    index = wIndex,
                    buffer = data,
                    length = length,
                    timeoutMs = timeoutMs
                )
                if (ret >= 0) {
                    bytesTransferred = ret
                } else {
                    errno = -ret
                    bytesTransferred = ret
                }
            } else {
                val ret = connection.controlTransfer(bmRequestType, bRequest, wValue, wIndex, data, length, timeoutMs)
                bytesTransferred = ret
                if (ret < 0) {
                    errno = -1
                }
            }

            val errName = errnoName(errno)
            val isSuccess = bytesTransferred >= 0

            val logMsg = "bmReqType=0x%02x bReq=0x%02x wVal=0x%04x wIdx=0x%04x len=%d res=%d%s".format(
                bmRequestType and 0xFF,
                bRequest and 0xFF,
                wValue and 0xFFFF,
                wIndex and 0xFFFF,
                length,
                bytesTransferred,
                if (errno != 0) " (errno=$errno: $errName)" else ""
            )
            UsbRingLog.record("ControlReq", logMsg, UsbRingLog.CODE_CONTROL_REQ)
            PlayLog.log("usb", "Control: $logMsg")

            return ControlTransferResult(
                bytesTransferred = bytesTransferred,
                errno = errno,
                errnoName = errName,
                isSuccess = isSuccess
            )
        }
    }
}
