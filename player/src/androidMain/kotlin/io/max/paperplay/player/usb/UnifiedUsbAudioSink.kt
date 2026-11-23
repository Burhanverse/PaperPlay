package io.max.paperplay.player.usb

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.BitPerfectRouter
import io.max.paperplay.player.MIME_AUDIO_DOP
import io.max.paperplay.player.OutputRoute
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import io.max.paperplay.player.RouteInput
import java.nio.ByteBuffer
import kotlin.math.abs

/** Native [CoarseUsbSessionApi] implementation delegating directly to [UsbEngineNative]. */
internal object NativeCoarseUsbSessionApi : CoarseUsbSessionApi {
    override val available: Boolean get() = UsbEngineNative.available

    override fun open(
        fd: Int,
        controlIface: Int,
        streamingIface: Int,
        isUac2: Boolean,
        clockSourceId: Int,
        hasHardwareVolume: Boolean,
        featureUnitId: Int,
    ): Long = UsbEngineNative.sessionOpen(
        fd, controlIface, streamingIface, isUac2, clockSourceId, hasHardwareVolume, featureUnitId,
    )

    override fun reconfigure(
        handle: Long,
        endpoint: Int,
        streamingIface: Int,
        targetAlt: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int,
        epAttributes: Int,
    ): Int = UsbEngineNative.sessionReconfigure(
        handle, endpoint, streamingIface, targetAlt, sampleRate, bitDepth, channels, subslotSize, isHighSpeed, isDop, feedbackEp, epAttributes,
    )

    override fun reconfigure(
        handle: Long,
        endpoint: Int,
        streamingIface: Int,
        targetAlt: Int,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        subslotSize: Int,
        isHighSpeed: Boolean,
        isDop: Boolean,
        feedbackEp: Int,
    ): Int = UsbEngineNative.sessionReconfigure(
        handle, endpoint, streamingIface, targetAlt, sampleRate, bitDepth, channels, subslotSize, isHighSpeed, isDop, feedbackEp, 0x01,
    )

    override fun applyVolume(
        handle: Long,
        featureUnitId: Int,
        controlIface: Int,
        channel: Int,
        volumeRaw: Int,
        mute: Boolean,
    ): Int = UsbEngineNative.sessionApplyVolume(handle, featureUnitId, controlIface, channel, volumeRaw, mute)

    override fun start(handle: Long): Int = UsbEngineNative.sessionStart(handle)
    override fun pauseGrace(handle: Long): Int = UsbEngineNative.sessionPauseGrace(handle)
    override fun stop(handle: Long): Int = UsbEngineNative.sessionStop(handle)
    override fun writePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int =
        UsbEngineNative.sessionWritePcm(handle, buffer, offset, length)
    override fun setMuteRamp(handle: Long, muted: Boolean): Int = UsbEngineNative.sessionSetMuteRamp(handle, muted)
    override fun flush(handle: Long): Int = UsbEngineNative.sessionFlush(handle)
    override fun escalateCushionAndResync(handle: Long): Int = UsbEngineNative.sessionEscalateAndResync(handle)
    override fun getStats(handle: Long, outStats: LongArray): Int = UsbEngineNative.sessionGetStats(handle, outStats)
    override fun close(handle: Long) = UsbEngineNative.sessionClose(handle)
}

/**
 * Unified Bit-perfect Android [AudioSink] backed by Stage 2 coarse JNI sessions,
 * [BitPerfectRouter], [UsbVolumeCurve], and [UsbCoarseSinkController].
 *
 * Thread safety: NEVER touches ExoPlayer or calls Player methods from any sink callback;
 * reads only `@Volatile` mirrors on [PlaybackCore].
 */
@OptIn(UnstableApi::class)
class UnifiedUsbAudioSink internal constructor(
    private val context: Context?,
    private val normalSink: AudioSink,
    private val sessionApi: CoarseUsbSessionApi = NativeCoarseUsbSessionApi,
) : ForwardingAudioSink(normalSink) {

    constructor(
        context: Context,
        normalSink: AudioSink,
    ) : this(
        context = context,
        normalSink = normalSink,
        sessionApi = NativeCoarseUsbSessionApi,
    )

    companion object {
        @Volatile
        var currentInstance: UnifiedUsbAudioSink? = null
            private set
    }

    @Volatile
    var isUsbModeActive: Boolean = false
        private set

    @Volatile
    private var normalSinkConfigured: Boolean = false

    @Volatile
    private var releasedWhileIdle: Boolean = false

    @Volatile
    private var bypassedForSpeed: Boolean = false

    @Volatile
    private var sinkPlaying: Boolean = false

    @Volatile
    private var sinkReleased: Boolean = false

    val confirmedRateHz: Int get() = coarseController.confirmedRateHz
    val confirmedBitDepth: Int get() = coarseController.confirmedBitDepth
    val confirmedAlt: Int get() = coarseController.confirmedAlt
    val lastConfigureWasInPlace: Boolean get() = coarseController.lastConfigureWasInPlace
    val lastVolumeReadbackRaw: Int get() = coarseController.lastVolumeReadbackRaw
    val lastSubslotBytes: Int get() = coarseController.lastSubslotBytes

    private var lastConfig: AudioSink.AudioSinkConfig? = null
    private var currentFormat: Format? = null
    @Volatile
    private var anchorPositionUs: Long = 0L
    @Volatile
    private var hasAnchorPosition: Boolean = false
    @Volatile
    private var baseFramesConsumed: Long = 0L
    @Volatile
    private var activeFuMeta: UsbFeatureUnit? = null
    @Volatile
    private var activeHwVolRef: UsbHardwareVolume? = null
    private val statsScratch = LongArray(17)
    private val usbPcmEq = io.max.paperplay.player.NativePcmEqStage()
    private var pendingEqBuf: ByteBuffer? = null

    fun readExtendedStats(outStats: LongArray): Boolean =
        isUsbModeActive && coarseController.readStats(outStats)

    val realFaultReconfigures: Int get() = coarseController.realFaultReconfigures
    val cushionEscalationRequests: Int get() = coarseController.cushionEscalationRequests

    init {
        currentInstance = this
    }

    private val coarseController = UsbCoarseSinkController(
        api = sessionApi,
        clockMs = { SystemClock.elapsedRealtime() },
        onFallback = { reason ->
            PlayLog.log("usb", "UnifiedUsbAudioSink fallback: $reason")
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            PlaybackCore.usbStatus = "Fallback: $reason"
            UnifiedUsbDacManager.instance?.onSinkFallback(reason)
        },
        onHardStop = { reason ->
            PlayLog.log("usb", "UnifiedUsbAudioSink DoP hard-stop: $reason")
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            PlaybackCore.usbStatus = "Stopped: $reason"
        },
    )

    fun matchesSessionHandle(handle: Long): Boolean =
        handle != 0L && coarseController.handle == handle

    fun pauseGraceFromCoordinator(): Int =
        if (coarseController.pauseGrace()) 0 else -1

    fun resumeGraceFromCoordinator(): Int =
        if (coarseController.resumeGrace()) 0 else -1

    private fun closeCoarseAndNotify() {
        UsbPcmDumper.finishIfDumping("Stream closed")
        val oldHandle = coarseController.handle
        coarseController.close()
        if (oldHandle != 0L) {
            UnifiedUsbDacManager.instance?.onSessionClosed(oldHandle)
        }
    }

    fun releaseFromCoordinator(expectedHandle: Long) {
        if (coarseController.handle == expectedHandle) {
            UsbPcmDumper.finishIfDumping("Released by coordinator")
            releasedWhileIdle = true
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            coarseController.closeIfHandleMatches(expectedHandle)
            if (expectedHandle != 0L) {
                UnifiedUsbDacManager.instance?.onSessionClosed(expectedHandle)
            }
        }
    }

    private fun ensureNormalSinkConfigured(): Boolean {
        if (normalSinkConfigured) return true
        val cfg = lastConfig ?: return false
        return runCatching {
            super.configure(cfg)
            normalSinkConfigured = true
            true
        }.getOrDefault(false)
    }

    private fun tryReopenAfterIdleRelease(forceBypassReturn: Boolean = false): Boolean {
        val eligible = releasedWhileIdle || forceBypassReturn
        if (sinkReleased || !eligible || !PlaybackCore.usbExclusiveMode) return false
        val cfg = lastConfig ?: return false
        val mgr = UnifiedUsbDacManager.instance ?: return false
        if (!mgr.reopenActiveDeviceIfPermitted()) return false
        configure(cfg)
        if (isUsbModeActive && forceBypassReturn) {
            bypassedForSpeed = false
            PlayLog.log("usb", "UnifiedUsbAudioSink returned from speed bypass to 1.0x USB (rate=${coarseController.confirmedRateHz}, alt=${coarseController.confirmedAlt})")
            val rate = coarseController.confirmedRateHz
            val depth = coarseController.confirmedBitDepth
            if (rate > 0 && depth > 0) {
                mgr.onStreamingActive(rate, depth, null)
            }
        }
        return isUsbModeActive
    }

    fun getStatsSnapshot(): UsbStreamStats? {
        if (!isUsbModeActive) return null
        val snap = LongArray(10)
        if (!coarseController.readStats(snap)) return null
        return UsbStreamStats(
            framesConsumed = snap[0],
            underruns = snap[1],
            urbErrors = snap[2],
            ringFillBytes = snap[3],
            ringCapacity = snap[4],
            isRunning = snap[5] != 0L,
            sampleRate = snap[6].toInt(),
            isPaused = snap[7] != 0L,
            isFlushing = snap[8] != 0L,
        )
    }

    fun syncSystemVolume(isUser: Boolean): Boolean {
        if (!isUsbModeActive) return false
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val rawIdx = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val coord = UnifiedUsbDacManager.instance?.coordinator
        val safeIdx = coord?.computeSafeSyncedIndex(rawIdx, max, isUser = isUser) ?: rawIdx
        val (volRaw, muted) = computeCurveVolumeRaw(safeIdx, max, activeHwVolRef)
        val ok = coarseController.applyLiveVolume(volRaw, muted)
        val readRaw = coarseController.lastVolumeReadbackRaw
        PlayLog.log(
            "usb",
            "UnifiedUsbAudioSink volume sync: idx=$safeIdx/$max (rawIdx=$rawIdx, isUser=$isUser) targetRaw=$volRaw (${volRaw / 256.0f} dB) readbackRaw=$readRaw (${readRaw / 256.0f} dB) muted=$muted ok=$ok"
        )
        return ok
    }

    private fun computeCurveVolumeRaw(
        index: Int,
        maxIndex: Int,
        hwVol: UsbHardwareVolume?,
    ): Pair<Int, Boolean> {
        if (index <= 0) {
            val minRaw = hwVol?.minRaw ?: (-0x4000).toShort()
            return minRaw.toInt() to true
        }
        val minRaw = hwVol?.minRaw ?: (-0x4000).toShort()
        val maxRaw = hwVol?.maxRaw ?: 0.toShort()
        val resRaw = hwVol?.resRaw ?: 0x0080.toShort()
        val minDb = minRaw.toFloat() / 256.0f
        val maxDb = maxRaw.toFloat() / 256.0f
        val resDb = resRaw.toFloat() / 256.0f
        val db = UsbVolumeCurve.computeVolumeDb(index, maxIndex, minDb, maxDb, resDb)
        val raw = UsbVolumeCurve.dbToRaw(db, minRaw, maxRaw, resRaw)
        return raw.toInt() to false
    }

    override fun supportsFormat(format: Format): Boolean =
        if (!PlaybackCore.usbExclusiveMode) super.supportsFormat(format)
        else getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

    override fun getFormatSupport(format: Format): Int {
        if (!PlaybackCore.usbExclusiveMode) {
            return super.getFormatSupport(format)
        }
        val bits = when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
        val isRawOrFloat = format.pcmEncoding in setOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
        return UsbCoarseSinkController.getFormatSupport(
            sampleMimeType = format.sampleMimeType,
            pcmEncodingBits = bits,
            isRawOrFloat = isRawOrFloat,
            channelCount = format.channelCount,
            sampleRate = format.sampleRate,
        )
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        if (sinkReleased) return
        lastConfig = config
        releasedWhileIdle = false
        val f = config.format
        currentFormat = f
        anchorPositionUs = 0L
        hasAnchorPosition = false
        baseFramesConsumed = 0L
        val isDoP = f.sampleMimeType == MIME_AUDIO_DOP

        val usbManager = UsbAudioManager.instance
        if (PlaybackCore.usbExclusiveMode && usbManager != null) {
            if (usbManager.state.value !is UsbAudioState.Connected) {
                val mgr = UnifiedUsbDacManager.instance
                if (mgr != null) {
                    mgr.reopenActiveDeviceIfPermitted()
                } else {
                    val dev = usbManager.enumerateAudioDevices().firstOrNull()
                    if (dev != null) {
                        usbManager.openDevice(dev, triggerReconfigure = false)
                    }
                }
            }
            usbManager.ensureNativeCapabilitiesProbed()
        }
        val connected = usbManager?.state?.value as? UsbAudioState.Connected
        val decision = BitPerfectRouter.decide(
            RouteInput(
                mode = if (PlaybackCore.usbExclusiveMode) BitPerfectMode.ON else BitPerfectMode.OFF,
                speed = PlaybackCore.speed,
                usbDacAttached = connected != null,
                usbPermissionGranted = connected != null,
                dacReady = connected != null,
                isDoP = isDoP,
            )
        )

        if (decision.route !is OutputRoute.UsbUacDirect || connected == null) {
            if (isUsbModeActive) {
                closeCoarseAndNotify()
                isUsbModeActive = false
            }
            if (decision.route is OutputRoute.Normal && abs(PlaybackCore.speed - 1.0f) > 0.001f && PlaybackCore.usbExclusiveMode) {
                bypassedForSpeed = true
                UnifiedUsbDacManager.instance?.onSpeedBypass(PlaybackCore.speed)
            } else {
                bypassedForSpeed = false
            }
            PlaybackCore.usbActive = false
            PlaybackCore.usbStatus = decision.note
            if (decision.route is OutputRoute.HardStop) {
                throw AudioSink.ConfigurationException(decision.note, f)
            }
            super.configure(config)
            normalSinkConfigured = true
            return
        }

        val desc = connected.description
        val depth = when (f.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }
        val altCandidates = if (isDoP) {
            desc.playbackAltSettings.filter { it.isDsdDoPCapable || it.bitResolution >= 24 }
        } else {
            desc.playbackAltSettings
        }
        val selection = UsbFormatNegotiator.pickBestFormat(
            candidates = altCandidates,
            sourceRate = f.sampleRate,
            sourceBitDepth = depth,
            sourceChannels = f.channelCount,
        )
        val alt = (selection as? FormatSelectionResult.Success)?.candidate
        if (alt == null) {
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            if (isDoP) throw AudioSink.ConfigurationException("DAC does not support DoP format", f)
            super.configure(config)
            normalSinkConfigured = true
            return
        }

        val clockId = desc.resolvePlaybackClockSourceId(alt) ?: 9
        val fu = desc.resolvePlaybackFeatureUnit(alt)
        activeFuMeta = fu
        activeHwVolRef = usbManager.hardwareVolume

        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxIdx = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 16).coerceAtLeast(1)
        val sysIdx = (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxIdx / 4).coerceAtLeast(1)).coerceIn(0, maxIdx)
        val coord = UnifiedUsbDacManager.instance?.coordinator
        val safeIdx = coord?.computeSafeSyncedIndex(sysIdx, maxIdx, isUser = false) ?: sysIdx
        val (initialVolRaw, initialMuted) = computeCurveVolumeRaw(safeIdx, maxIdx, usbManager.hardwareVolume)
        val volChannels = when {
            fu == null -> listOf(0)
            fu.hasMasterVolume -> listOf(0)
            fu.hasChannelVolume -> (1..alt.channelCount.coerceAtLeast(1)).toList()
            else -> listOf(0)
        }
        val hasHwVol = desc.hasHardwareVolume && fu != null && (fu.hasMasterVolume || fu.hasChannelVolume)
        val wireDepth = alt.bitResolution
        val epAttr = alt.computeNativeEpAttributes(f.sampleRate)

        val ok = coarseController.openAndConfigure(
            fd = connected.connection.fileDescriptor,
            controlIface = desc.acInterfaceNumber,
            streamingIface = alt.interfaceNumber,
            targetAlt = alt.altSetting,
            endpoint = alt.dataEndpoint?.address ?: 0x01,
            isUac2 = desc.uacVersion == UacVersion.UAC_2_0,
            clockSourceId = clockId,
            hasHwVol = hasHwVol,
            fuId = fu?.id ?: 0,
            sampleRate = f.sampleRate,
            bitDepth = wireDepth,
            channels = alt.channelCount,
            subslotSize = alt.subslotSize,
            isHighSpeed = desc.uacVersion == UacVersion.UAC_2_0 || (alt.dataEndpoint?.maxPacketSize ?: 0) > 1023,
            isDop = isDoP,
            feedbackEp = alt.feedbackEndpoint?.address ?: 0,
            volumeRaw = initialVolRaw,
            muted = initialMuted,
            volumeChannels = volChannels,
            epAttributes = epAttr,
        )
        if (!ok) {
            PlayLog.log(
                "usb",
                "UnifiedUsbAudioSink configure FAILED: uac=${desc.uacVersion} rate=${f.sampleRate} alt=${alt.interfaceNumber}:${alt.altSetting} ep=0x${(alt.dataEndpoint?.address ?: 1).toString(16)} epAttr=0x${epAttr.toString(16)} rateCtrl=${alt.dataEndpoint?.supportsSampleRateControl} reason=${coarseController.lastFailureReason}"
            )
            UsbRingLog.record(
                "UnifiedUsbAudioSink",
                "CONFIGURE_STEP_RESULT: status=FAILED uac=${desc.uacVersion} if=${alt.interfaceNumber}:alt=${alt.altSetting} rate=${f.sampleRate} ch=${alt.channelCount} bits=$wireDepth/${alt.subslotSize}B ep=0x${(alt.dataEndpoint?.address ?: 1).toString(16)} epAttr=0x${epAttr.toString(16)} rateCtrl=${alt.dataEndpoint?.supportsSampleRateControl} reason=${coarseController.lastFailureReason}",
                UsbRingLog.CODE_ERROR,
            )
            context?.let { ctx ->
                UsbAudioProbe.exportFallbackLog(ctx, coarseController.lastFailureReason ?: "UnifiedUsbAudioSink configure failed")
            }
            if (isDoP || coarseController.isHardStopped) {
                throw AudioSink.ConfigurationException(coarseController.lastFailureReason ?: "DoP configure failed", f)
            }
            super.configure(config)
            normalSinkConfigured = true
            return
        }

        bypassedForSpeed = false
        isUsbModeActive = true
        normalSinkConfigured = false
        pendingEqBuf = null
        usbPcmEq.configure(f.sampleRate, f.channelCount, f.pcmEncoding)
        PlaybackCore.usbActive = true
        PlaybackCore.usbStatus = "USB: configured (${f.sampleRate} Hz, alt ${alt.altSetting})"
        PlayLog.log(
            "usb",
            "UnifiedUsbAudioSink configured: uac=${desc.uacVersion}, rate=${f.sampleRate} Hz, srcBits=$depth, wireBits=$wireDepth, subslot=${alt.subslotSize}, alt=${alt.altSetting}, ch=${alt.channelCount}, epAttr=0x${epAttr.toString(16)}, rateCtrl=${alt.dataEndpoint?.supportsSampleRateControl}, inPlace=${coarseController.lastConfigureWasInPlace}, handle=${coarseController.handle}, volRaw=$initialVolRaw (${initialVolRaw / 256.0f} dB), volReadbackRaw=${coarseController.lastVolumeReadbackRaw} (${coarseController.lastVolumeReadbackRaw / 256.0f} dB), muted=$initialMuted"
        )
        UsbRingLog.record(
            "UnifiedUsbAudioSink",
            "CONFIGURE_STEP_RESULT: status=OK uac=${desc.uacVersion} if=${alt.interfaceNumber}:alt=${alt.altSetting} rate=${f.sampleRate} confirmedRate=${coarseController.confirmedRateHz} confirmedAlt=${coarseController.confirmedAlt} ch=${alt.channelCount} bits=$wireDepth/${alt.subslotSize}B ep=0x${(alt.dataEndpoint?.address ?: 1).toString(16)} epAttr=0x${epAttr.toString(16)} rateCtrl=${alt.dataEndpoint?.supportsSampleRateControl} inPlace=${coarseController.lastConfigureWasInPlace} volRaw=$initialVolRaw volReadback=${coarseController.lastVolumeReadbackRaw}",
            UsbRingLog.CODE_SINK_CONFIGURE,
            f.sampleRate.toLong(),
            alt.altSetting.toLong(),
        )
        val cacheDir = context?.cacheDir
        if (UsbPcmDumper.isEnabled && cacheDir != null) {
            UsbPcmDumper.onStreamConfigured(
                cacheDir = cacheDir,
                sampleRate = f.sampleRate,
                bitDepth = wireDepth,
                channels = alt.channelCount,
                subslotSize = alt.subslotSize,
            )
        }
        UnifiedUsbDacManager.instance?.onSessionOpened(coarseController.handle, connected.device.deviceId, connected.device.productName)
        UnifiedUsbDacManager.instance?.onStreamingActive(f.sampleRate, wireDepth, connected.device.productName)
        runCatching {
            super.flush()
            super.reset()
        }
    }

    override fun handleDiscontinuity() {
        anchorPositionUs = 0L
        hasAnchorPosition = false
        pendingEqBuf = null
        if (isUsbModeActive) {
            PlayLog.log("usb", "UnifiedUsbAudioSink handleDiscontinuity")
            usbPcmEq.reset()
            coarseController.onSeekOrFlush()
            if (coarseController.readStats(statsScratch)) {
                baseFramesConsumed = statsScratch[0]
            }
        } else if (normalSinkConfigured) {
            super.handleDiscontinuity()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val f = currentFormat
        if (!isUsbModeActive || f == null) {
            val fromSpeedBypass = bypassedForSpeed && abs(PlaybackCore.speed - 1.0f) <= 0.001f && PlaybackCore.usbExclusiveMode
            if (releasedWhileIdle || fromSpeedBypass) {
                if (!sinkPlaying && !PlaybackCore.playerPlayWhenReady) {
                    return false
                }
                if (tryReopenAfterIdleRelease(forceBypassReturn = fromSpeedBypass)) {
                    // Fall through to USB handleBuffer right below
                } else {
                    releasedWhileIdle = false
                    bypassedForSpeed = false
                    if (!ensureNormalSinkConfigured()) return false
                    val feed = pendingEqBuf ?: buffer
                    val ok = runCatching { super.handleBuffer(feed, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
                    if (ok || !feed.hasRemaining()) {
                        pendingEqBuf = null
                        buffer.position(buffer.limit())
                    }
                    return ok
                }
            } else {
                if (!ensureNormalSinkConfigured()) return false
                val feed = pendingEqBuf ?: buffer
                val ok = runCatching { super.handleBuffer(feed, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
                if (ok || !feed.hasRemaining()) {
                    pendingEqBuf = null
                    buffer.position(buffer.limit())
                }
                return ok
            }
        }
        val activeFmt = currentFormat ?: return false
        if (abs(PlaybackCore.speed - 1.0f) > 0.001f || !PlaybackCore.usbExclusiveMode) {
            val isDoP = activeFmt.sampleMimeType == MIME_AUDIO_DOP
            closeCoarseAndNotify()
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            PlaybackCore.usbEqActive = false
            if (isDoP) {
                pendingEqBuf = null
                PlaybackCore.usbStatus = "Stopped: DoP requires Bit-perfect at 1.0x speed"
                return false
            }
            if (PlaybackCore.usbExclusiveMode && abs(PlaybackCore.speed - 1.0f) > 0.001f) {
                bypassedForSpeed = true
                PlayLog.log("usb", "UnifiedUsbAudioSink speed bypass: speed=${PlaybackCore.speed}x")
                UnifiedUsbDacManager.instance?.onSpeedBypass(PlaybackCore.speed)
                UsbAudioManager.instance?.onUsbReleased(closeConnection = true, rescan = false)
            } else {
                bypassedForSpeed = false
            }
            if (!ensureNormalSinkConfigured()) return false
            if (sinkPlaying || PlaybackCore.playerPlayWhenReady) {
                runCatching { super.play() }
            }
            val feed = pendingEqBuf ?: buffer
            val ok = runCatching { super.handleBuffer(feed, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
            if (ok || !feed.hasRemaining()) {
                pendingEqBuf = null
                buffer.position(buffer.limit())
            }
            return ok
        }

        if (!hasAnchorPosition) {
            anchorPositionUs = presentationTimeUs
            hasAnchorPosition = true
            if (coarseController.readStats(statsScratch)) {
                baseFramesConsumed = statsScratch[0]
            }
        }

        val isDoP = activeFmt.sampleMimeType == MIME_AUDIO_DOP
        var eqBuf = pendingEqBuf
        if (eqBuf == null) {
            eqBuf = usbPcmEq.processToDirectBufferIfActive(buffer, isDoP = isDoP)
            if (eqBuf != null) pendingEqBuf = eqBuf
        }
        val feedBuf = eqBuf ?: buffer

        val isFloat = activeFmt.pcmEncoding == C.ENCODING_PCM_FLOAT
        val srcBits = when (activeFmt.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }
        val beforeRem = feedBuf.remaining()
        val ok = coarseController.writePcm(feedBuf, srcBits, isFloat)
        val afterRem = feedBuf.remaining()
        val consumed = (beforeRem - afterRem).coerceAtLeast(0)
        if (eqBuf != null && consumed > 0) {
            buffer.position((buffer.position() + consumed).coerceAtMost(buffer.limit()))
        }
        if (!ok) {
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            PlaybackCore.usbEqActive = false
            if (coarseController.isHardStopped) {
                pendingEqBuf = null
                return false
            }
            if (!ensureNormalSinkConfigured()) return false
            val fbOk = runCatching { super.handleBuffer(feedBuf, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
            if (fbOk || !feedBuf.hasRemaining()) {
                pendingEqBuf = null
                buffer.position(buffer.limit())
            }
            return fbOk
        }
        val consumedAll = !feedBuf.hasRemaining()
        if (consumedAll) {
            pendingEqBuf = null
            buffer.position(buffer.limit())
        }
        return consumedAll
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isUsbModeActive) {
            return if (normalSinkConfigured) super.getCurrentPositionUs(sourceEnded) else AudioSink.CURRENT_POSITION_NOT_SET
        }
        val rate = coarseController.confirmedRateHz
        if (coarseController.handle == 0L || rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        if (!coarseController.readStats(statsScratch)) return AudioSink.CURRENT_POSITION_NOT_SET
        val playedFrames = (statsScratch[0] - baseFramesConsumed).coerceAtLeast(0L)
        return anchorPositionUs + (playedFrames * 1_000_000L) / rate
    }

    override fun play() {
        sinkPlaying = true
        if (!isUsbModeActive) {
            val fromSpeedBypass = bypassedForSpeed && abs(PlaybackCore.speed - 1.0f) <= 0.001f && PlaybackCore.usbExclusiveMode
            if ((releasedWhileIdle || fromSpeedBypass) && tryReopenAfterIdleRelease(forceBypassReturn = fromSpeedBypass)) {
                // Re-claimed DAC cleanly on playback thread; run USB start flow below
            } else {
                releasedWhileIdle = false
                if (ensureNormalSinkConfigured()) {
                    runCatching { super.play() }
                }
                return
            }
        }
        PlayLog.log("usb", "UnifiedUsbAudioSink play (pausedGrace=${coarseController.isPausedGrace})")
        val mgr = UnifiedUsbDacManager.instance
        if (coarseController.isPausedGrace) {
            if (mgr != null) {
                mgr.onPlaybackResumed()
            } else {
                coarseController.resumeGrace()
            }
        } else {
            mgr?.onPlaybackStarted()
        }
        syncSystemVolume(isUser = false)
        val rate = coarseController.confirmedRateHz
        val depth = coarseController.confirmedBitDepth
        if (rate > 0 && depth > 0) {
            mgr?.onStreamingActive(rate, depth, null)
        }
    }

    override fun pause() {
        sinkPlaying = false
        if (!isUsbModeActive) {
            if (normalSinkConfigured) {
                runCatching { super.pause() }
            }
            return
        }
        PlayLog.log("usb", "UnifiedUsbAudioSink pause")
        val mgr = UnifiedUsbDacManager.instance
        if (mgr != null) {
            mgr.onPlaybackPaused()
        } else {
            coarseController.pauseGrace()
        }
    }

    override fun flush() {
        hasAnchorPosition = false
        pendingEqBuf = null
        if (!isUsbModeActive) {
            if (normalSinkConfigured) runCatching { super.flush() }
            return
        }
        PlayLog.log("usb", "UnifiedUsbAudioSink flush")
        usbPcmEq.reset()
        anchorPositionUs = getCurrentPositionUs(false).takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET } ?: anchorPositionUs
        if (coarseController.readStats(statsScratch)) {
            baseFramesConsumed = statsScratch[0]
        }
        coarseController.onSeekOrFlush()
    }

    override fun reset() {
        anchorPositionUs = 0L
        hasAnchorPosition = false
        baseFramesConsumed = 0L
        pendingEqBuf = null
        if (!isUsbModeActive) {
            if (normalSinkConfigured) runCatching { super.reset() }
            normalSinkConfigured = false
            releasedWhileIdle = false
            return
        }
        PlayLog.log("usb", "UnifiedUsbAudioSink reset")
        usbPcmEq.reset()
        coarseController.onSeekOrFlush()
    }

    override fun playToEndOfStream() {
        if (!isUsbModeActive) {
            if (normalSinkConfigured) runCatching { super.playToEndOfStream() }
            return
        }
        coarseController.playToEndOfStream()
    }

    override fun isEnded(): Boolean =
        if (!isUsbModeActive) (if (normalSinkConfigured) super.isEnded() else false) else coarseController.isEnded()

    override fun hasPendingData(): Boolean =
        if (!isUsbModeActive) (if (normalSinkConfigured) super.hasPendingData() else false) else coarseController.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        PlaybackCore.speed = playbackParameters.speed
        super.setPlaybackParameters(playbackParameters)
    }

    override fun release() {
        PlayLog.log("usb", "UnifiedUsbAudioSink release")
        sinkReleased = true
        if (currentInstance === this) currentInstance = null
        sinkPlaying = false
        releasedWhileIdle = false
        bypassedForSpeed = false
        normalSinkConfigured = false
        isUsbModeActive = false
        PlaybackCore.usbActive = false
        pendingEqBuf = null
        usbPcmEq.release()
        closeCoarseAndNotify()
        UnifiedUsbDacManager.instance?.onSinkFallback("Sink released")
        runCatching { super.release() }
    }

    override fun setVolume(volume: Float) {
        if (!isUsbModeActive) super.setVolume(volume)
    }
}
