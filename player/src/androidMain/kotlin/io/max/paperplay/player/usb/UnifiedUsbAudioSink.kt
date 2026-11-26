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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    private val openWaitBudgetMs: Long = DEFAULT_OPEN_WAIT_BUDGET_MS,
    private val reopenCooldownMs: Long = DEFAULT_REOPEN_COOLDOWN_MS,
    private val usbOpenRunner: (() -> Boolean)? = null,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
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
        const val DEFAULT_OPEN_WAIT_BUDGET_MS = 400L
        const val DEFAULT_REOPEN_COOLDOWN_MS = 2_000L

        @Volatile
        var currentInstance: UnifiedUsbAudioSink? = null
            private set

        /** Earbuds or headphones connected after the DAC: the music goes there, and the DAC is left alone until they are gone. */
        @Volatile
        var otherOutputWins: Boolean = false

        private val usbOpenExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "UsbOpenWorker").apply { isDaemon = true }
        }
    }

    @Volatile
    var isUsbModeActive: Boolean = false
        private set

    @Volatile
    private var normalSinkConfigured: Boolean = false

    @Volatile
    private var releasedWhileIdle: Boolean = false

    @Volatile
    private var pendingUsbAttach: Boolean = false

    @Volatile
    private var lastOpenFailureMs: Long = Long.MIN_VALUE / 2

    @Volatile
    private var inFlightOpenFuture: Future<Boolean>? = null

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
    private var lastTapPtsUs: Long = C.TIME_UNSET
    private var lastTapPtsConsumedBytes: Int = 0

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
            // It only sounds when it was told to play, and that went to the DAC's path.
            if (sinkPlaying) runCatching { super.play() }
            true
        }.getOrDefault(false)
    }

    fun markPendingUsbAttach() {
        pendingUsbAttach = true
        lastOpenFailureMs = Long.MIN_VALUE / 2
    }

    private fun runBoundedOffThreadOpen(task: () -> Boolean): Boolean {
        if (sinkReleased) return false
        val now = clockMs()
        if (now - lastOpenFailureMs < reopenCooldownMs) {
            return false
        }
        val existing = inFlightOpenFuture
        if (existing != null && !existing.isDone) {
            return false
        }
        val future = usbOpenExecutor.submit<Boolean> {
            val ok = runCatching { task() }.getOrDefault(false)
            if (sinkReleased && coarseController.handle != 0L) {
                closeCoarseAndNotify()
                isUsbModeActive = false
                PlaybackCore.usbActive = false
            }
            ok
        }
        inFlightOpenFuture = future
        return try {
            val ok = future.get(openWaitBudgetMs, TimeUnit.MILLISECONDS)
            if (!ok) {
                lastOpenFailureMs = clockMs()
            }
            ok
        } catch (_: TimeoutException) {
            lastOpenFailureMs = clockMs()
            PlayLog.log("usb", "UnifiedUsbAudioSink: USB open timed out after ${openWaitBudgetMs}ms; falling back to normal sink")
            false
        } catch (t: Throwable) {
            lastOpenFailureMs = clockMs()
            PlayLog.log("usb", "UnifiedUsbAudioSink: USB open failed (${t.message}); falling back to normal sink")
            false
        }
    }

    private fun tryReopenAfterIdleRelease(forceBypassReturn: Boolean = false): Boolean {
        val eligible = releasedWhileIdle || pendingUsbAttach || forceBypassReturn
        if (sinkReleased || !eligible || !PlaybackCore.usbExclusiveMode || otherOutputWins) return false
        val cfg = lastConfig ?: return false
        val runner = usbOpenRunner
        val opened = if (runner != null) {
            runBoundedOffThreadOpen(runner)
        } else {
            val mgr = UnifiedUsbDacManager.instance ?: return false
            runBoundedOffThreadOpen {
                if (!mgr.reopenActiveDeviceIfPermitted()) return@runBoundedOffThreadOpen false
                configure(cfg)
                isUsbModeActive
            }
        }
        if (!opened) {
            releasedWhileIdle = false
            pendingUsbAttach = false
            return false
        }
        if (runner != null) {
            configure(cfg)
        }
        if (isUsbModeActive) {
            pendingUsbAttach = false
            releasedWhileIdle = false
            if (forceBypassReturn) {
                bypassedForSpeed = false
                PlayLog.log("usb", "UnifiedUsbAudioSink returned from speed bypass to 1.0x USB (rate=${coarseController.confirmedRateHz}, alt=${coarseController.confirmedAlt})")
                val rate = coarseController.confirmedRateHz
                val depth = coarseController.confirmedBitDepth
                if (rate > 0 && depth > 0) {
                    UnifiedUsbDacManager.instance?.onStreamingActive(rate, depth, null)
                }
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
        // The next song in the same format: the DAC just goes on. Opening it again would throw away what it has not played yet.
        val was = currentFormat
        if (isUsbModeActive && was != null && coarseController.handle != 0L && !coarseController.isFallback && PlaybackCore.usbExclusiveMode &&
            abs(PlaybackCore.speed - 1.0f) <= 0.001f && UsbAudioManager.instance?.state?.value is UsbAudioState.Connected &&
            sameUsbStream(was.sampleRate, was.channelCount, was.pcmEncoding, was.sampleMimeType, config.format.sampleRate, config.format.channelCount, config.format.pcmEncoding, config.format.sampleMimeType)
        ) {
            lastConfig = config
            currentFormat = config.format
            return
        }
        lastConfig = config
        releasedWhileIdle = false
        pendingUsbAttach = false
        val f = config.format
        currentFormat = f
        anchorPositionUs = 0L
        hasAnchorPosition = false
        baseFramesConsumed = 0L
        val isDoP = f.sampleMimeType == MIME_AUDIO_DOP

        if (context != null && PlaybackCore.usbExclusiveMode && !sinkPlaying && !PlaybackCore.playerPlayWhenReady && !PlaybackCore.userPlayIntent) {
            releasedWhileIdle = true
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            super.configure(config)
            normalSinkConfigured = true
            return
        }

        val usbManager = UsbAudioManager.instance
        val otherOutput = otherOutputWins
        if (PlaybackCore.usbExclusiveMode && usbManager != null && !otherOutput) {
            if (usbManager.state.value !is UsbAudioState.Connected) {
                val opened = runBoundedOffThreadOpen {
                    val mgr = UnifiedUsbDacManager.instance
                    if (mgr != null) {
                        mgr.reopenActiveDeviceIfPermitted()
                    } else {
                        val dev = usbManager.enumerateAudioDevices().firstOrNull()
                        if (dev != null && usbManager.openDevice(dev, triggerReconfigure = false)) {
                            usbManager.ensureNativeCapabilitiesProbed()
                            true
                        } else {
                            false
                        }
                    }
                }
                if (!opened && usbManager.state.value !is UsbAudioState.Connected) {
                    isUsbModeActive = false
                    PlaybackCore.usbActive = false
                    if (isDoP) throw AudioSink.ConfigurationException("USB DAC open timed out or failed", f)
                    super.configure(config)
                    normalSinkConfigured = true
                    return
                }
            } else {
                usbManager.ensureNativeCapabilitiesProbed()
            }
        }
        val connected = usbManager?.state?.value as? UsbAudioState.Connected
        val decision = BitPerfectRouter.decide(
            RouteInput(
                mode = if (PlaybackCore.usbExclusiveMode) BitPerfectMode.ON else BitPerfectMode.OFF,
                speed = PlaybackCore.speed,
                usbDacAttached = connected != null,
                usbPermissionGranted = connected != null,
                dacReady = connected != null,
                nonUsbRouteActive = otherOutput,
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
        val selection = UsbFormatNegotiator.pickForSource(altCandidates, f.sampleRate, depth, f.pcmEncoding == C.ENCODING_PCM_FLOAT, f.channelCount)
        val alt = (selection as? FormatSelectionResult.Success)?.candidate
        if (alt == null) {
            val why = (selection as? FormatSelectionResult.Failure)?.reason
            PlayLog.log("usb", "UnifiedUsbAudioSink: the DAC has no setting for ${f.sampleRate} Hz $depth bit ${f.channelCount} ch, Android plays it: $why")
            PlaybackCore.usbStatus = "Not direct: $why"
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
        if (sinkPlaying || PlaybackCore.playerPlayWhenReady) {
            UnifiedUsbDacManager.instance?.onStreamingActive(f.sampleRate, wireDepth, connected.device.productName)
        }
        runCatching {
            super.flush()
            super.reset()
        }
    }

    override fun handleDiscontinuity() {
        if (isUsbModeActive) {
            // Comes when the last buffer of a song was taken, seconds before it is heard (a seek comes through flush). The times of the next
            // song go on from this one's, so there is nothing to start over, and clearing the DAC's buffer cut the end of every song.
            PlayLog.log("usb", "UnifiedUsbAudioSink handleDiscontinuity")
            return
        }
        anchorPositionUs = 0L
        hasAnchorPosition = false
        pendingEqBuf = null
        lastTapPtsUs = C.TIME_UNSET
        lastTapPtsConsumedBytes = 0
        io.max.paperplay.player.AudioTap.reset()
        if (normalSinkConfigured) super.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val f = currentFormat
        if (!isUsbModeActive || f == null) {
            val fromSpeedBypass = bypassedForSpeed && abs(PlaybackCore.speed - 1.0f) <= 0.001f && PlaybackCore.usbExclusiveMode
            if (releasedWhileIdle || pendingUsbAttach || fromSpeedBypass) {
                if (!sinkPlaying) {
                    return false
                }
                if (tryReopenAfterIdleRelease(forceBypassReturn = fromSpeedBypass)) {
                    // Fall through to USB handleBuffer right below
                } else {
                    releasedWhileIdle = false
                    pendingUsbAttach = false
                    bypassedForSpeed = false
                    if (!ensureNormalSinkConfigured()) return false
                    if (sinkPlaying) {
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
            if (sinkPlaying) {
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
        val posBefore = feedBuf.position()
        val beforeRem = feedBuf.remaining()
        val ok = coarseController.writePcm(feedBuf, srcBits, isFloat)
        val afterRem = feedBuf.remaining()
        val consumed = (beforeRem - afterRem).coerceAtLeast(0)
        if (isDoP) {
            io.max.paperplay.player.AudioTap.markNonPcmStream()
        } else if (consumed > 0 && io.max.paperplay.player.AudioTap.wanted) {
            val pcmKind = when (activeFmt.pcmEncoding) {
                C.ENCODING_PCM_16BIT -> io.max.paperplay.player.PcmTapReader.PCM_16
                C.ENCODING_PCM_24BIT -> io.max.paperplay.player.PcmTapReader.PCM_24
                C.ENCODING_PCM_32BIT -> io.max.paperplay.player.PcmTapReader.PCM_32
                C.ENCODING_PCM_FLOAT -> io.max.paperplay.player.PcmTapReader.PCM_FLOAT
                else -> -1
            }
            if (pcmKind >= 0 && activeFmt.sampleRate > 0 && activeFmt.channelCount > 0) {
                val step = when (pcmKind) {
                    io.max.paperplay.player.PcmTapReader.PCM_16 -> 2
                    io.max.paperplay.player.PcmTapReader.PCM_24 -> 3
                    else -> 4
                }
                val pts = if (presentationTimeUs != C.TIME_UNSET && presentationTimeUs >= 0L) {
                    if (presentationTimeUs != lastTapPtsUs) {
                        lastTapPtsUs = presentationTimeUs
                        lastTapPtsConsumedBytes = 0
                    }
                    val frameBytes = step * activeFmt.channelCount
                    val baseFrames = if (frameBytes > 0) lastTapPtsConsumedBytes / frameBytes else 0
                    lastTapPtsConsumedBytes += consumed
                    presentationTimeUs + (baseFrames.toLong() * 1_000_000L) / activeFmt.sampleRate
                } else -1L
                io.max.paperplay.player.PcmTapReader.feedPcmSlice(
                    feedBuf, posBefore, consumed, activeFmt.sampleRate, activeFmt.channelCount, pcmKind, pts, "android-usb-unified",
                )
            }
        }
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
        val posUs = anchorPositionUs + (playedFrames * 1_000_000L) / rate
        io.max.paperplay.player.AudioTap.updatePlaybackClock(posUs, 1f)
        return posUs
    }

    override fun play() {
        sinkPlaying = true
        if (!isUsbModeActive) {
            val fromSpeedBypass = bypassedForSpeed && abs(PlaybackCore.speed - 1.0f) <= 0.001f && PlaybackCore.usbExclusiveMode
            if ((releasedWhileIdle || pendingUsbAttach || fromSpeedBypass) && tryReopenAfterIdleRelease(forceBypassReturn = fromSpeedBypass)) {
                // Re-claimed DAC cleanly; run USB start flow below
            } else {
                releasedWhileIdle = false
                pendingUsbAttach = false
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
        io.max.paperplay.player.AudioTap.onPause()
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
        lastTapPtsUs = C.TIME_UNSET
        lastTapPtsConsumedBytes = 0
        io.max.paperplay.player.AudioTap.reset()
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
        lastTapPtsUs = C.TIME_UNSET
        lastTapPtsConsumedBytes = 0
        io.max.paperplay.player.AudioTap.reset()
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
        val isCurrent = currentInstance === this
        sinkReleased = true
        inFlightOpenFuture?.cancel(false)
        inFlightOpenFuture = null
        sinkPlaying = false
        releasedWhileIdle = false
        pendingUsbAttach = false
        bypassedForSpeed = false
        normalSinkConfigured = false
        isUsbModeActive = false
        pendingEqBuf = null
        lastTapPtsUs = C.TIME_UNSET
        lastTapPtsConsumedBytes = 0
        io.max.paperplay.player.AudioTap.reset()
        usbPcmEq.release()
        val oldHandle = coarseController.handle
        closeCoarseAndNotify()
        if (isCurrent) {
            currentInstance = null
            PlaybackCore.usbActive = false
            if (oldHandle != 0L) {
                UnifiedUsbDacManager.instance?.onSinkFallback("Sink released")
            }
        }
        runCatching { super.release() }
    }

    override fun setVolume(volume: Float) {
        if (!isUsbModeActive) super.setVolume(volume)
    }
}
