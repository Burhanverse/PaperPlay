package io.max.paperplay.player.usb

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.max.paperplay.player.BitPerfectEvaluator
import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.BitPerfectRouter
import io.max.paperplay.player.MIME_AUDIO_DOP
import io.max.paperplay.player.OutputRoute
import io.max.paperplay.player.PcmFormat
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import io.max.paperplay.player.RouteInput
import io.max.paperplay.player.SampleEncoding
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
    ): Int = UsbEngineNative.sessionReconfigure(
        handle, endpoint, streamingIface, targetAlt, sampleRate, bitDepth, channels, subslotSize, isHighSpeed, isDop, feedbackEp,
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
    override fun getStats(handle: Long, outStats: LongArray): Int = UsbEngineNative.sessionGetStats(handle, outStats)
    override fun close(handle: Long) = UsbEngineNative.sessionClose(handle)
}

/**
 * Unified Bit-perfect Android [AudioSink] backed by Stage 2 coarse JNI sessions,
 * [BitPerfectRouter], [BitPerfectEvaluator], and [UsbCoarseSinkController].
 *
 * Thread safety: NEVER touches ExoPlayer or calls Player methods from any sink callback;
 * reads only `@Volatile` mirrors on [PlaybackCore].
 */
@OptIn(UnstableApi::class)
class UnifiedUsbAudioSink(
    private val context: Context,
    private val normalSink: AudioSink,
    private val sessionApi: CoarseUsbSessionApi = NativeCoarseUsbSessionApi,
) : ForwardingAudioSink(normalSink) {

    @Volatile
    var isUsbModeActive: Boolean = false
        private set

    private var lastConfig: AudioSink.AudioSinkConfig? = null
    private var currentFormat: Format? = null
    private var anchorPositionUs: Long = 0L
    private var baseFramesConsumed: Long = 0L
    private val statsScratch = LongArray(10)

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

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

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
        lastConfig = config
        val f = config.format
        currentFormat = f
        val isDoP = f.sampleMimeType == MIME_AUDIO_DOP

        val usbManager = UsbAudioManager.instance
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
                coarseController.close()
                isUsbModeActive = false
            }
            PlaybackCore.usbActive = false
            PlaybackCore.usbStatus = decision.note
            if (decision.route is OutputRoute.HardStop) {
                throw AudioSink.ConfigurationException(decision.note, f)
            }
            super.configure(config)
            return
        }

        val desc = connected.description
        val isFloat = f.pcmEncoding == C.ENCODING_PCM_FLOAT
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
            return
        }

        val clockId = desc.resolvePlaybackClockSourceId(alt) ?: 9
        val fu = desc.resolvePlaybackFeatureUnit(alt)
        val ok = coarseController.openAndConfigure(
            fd = connected.connection.fileDescriptor,
            controlIface = desc.acInterfaceNumber,
            streamingIface = alt.interfaceNumber,
            targetAlt = alt.altSetting,
            endpoint = alt.dataEndpoint?.address ?: 0x01,
            isUac2 = desc.uacVersion == UacVersion.UAC_2_0,
            clockSourceId = clockId,
            hasHwVol = desc.hasHardwareVolume && fu != null,
            fuId = fu?.id ?: 0,
            sampleRate = f.sampleRate,
            bitDepth = depth.coerceAtMost(alt.bitResolution),
            channels = alt.channelCount,
            subslotSize = alt.subslotSize,
            isHighSpeed = desc.uacVersion == UacVersion.UAC_2_0 || (alt.dataEndpoint?.maxPacketSize ?: 0) > 1023,
            isDop = isDoP,
            feedbackEp = alt.feedbackEndpoint?.address ?: 0,
            volumeRaw = usbManager.hardwareVolume?.lastAppliedIndex ?: 0,
            muted = false,
        )
        if (!ok) {
            if (isDoP || coarseController.isHardStopped) {
                throw AudioSink.ConfigurationException(coarseController.lastFailureReason ?: "DoP configure failed", f)
            }
            super.configure(config)
            return
        }

        isUsbModeActive = true
        PlaybackCore.usbActive = true
        PlaybackCore.usbStatus = "USB: configured (${f.sampleRate} Hz, alt ${alt.altSetting})"
        UnifiedUsbDacManager.instance?.onSessionOpened(coarseController.handle, connected.device.deviceId, connected.device.productName)
        runCatching {
            super.flush()
            super.reset()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val f = currentFormat
        if (!isUsbModeActive || f == null) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (abs(PlaybackCore.speed - 1.0f) > 0.001f || !PlaybackCore.usbExclusiveMode) {
            val isDoP = f.sampleMimeType == MIME_AUDIO_DOP
            coarseController.close()
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            if (isDoP) {
                PlaybackCore.usbStatus = "Stopped: DoP requires Bit-perfect at 1.0x speed"
                return false
            }
            lastConfig?.let { runCatching { super.configure(it) } }
            return runCatching { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
        }

        val isFloat = f.pcmEncoding == C.ENCODING_PCM_FLOAT
        val srcBits = when (f.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            else -> 32
        }
        val ok = coarseController.writePcm(buffer, srcBits, isFloat)
        if (!ok) {
            isUsbModeActive = false
            PlaybackCore.usbActive = false
            if (coarseController.isHardStopped) return false
            lastConfig?.let { runCatching { super.configure(it) } }
            return runCatching { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
        }
        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!isUsbModeActive) return super.getCurrentPositionUs(sourceEnded)
        val h = coarseController.handle
        val rate = coarseController.confirmedRateHz
        if (h == 0L || rate <= 0) return AudioSink.CURRENT_POSITION_NOT_SET
        if (sessionApi.getStats(h, statsScratch) < 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val playedFrames = (statsScratch[0] - baseFramesConsumed).coerceAtLeast(0L)
        return anchorPositionUs + (playedFrames * 1_000_000L) / rate
    }

    override fun play() {
        if (!isUsbModeActive) {
            runCatching { super.play() }
            return
        }
        if (coarseController.isPausedGrace) {
            coarseController.resumeGrace()
        }
        UnifiedUsbDacManager.instance?.onPlaybackStarted()
    }

    override fun pause() {
        if (!isUsbModeActive) {
            runCatching { super.pause() }
            return
        }
        coarseController.pauseGrace()
        UnifiedUsbDacManager.instance?.onPlaybackPaused()
    }

    override fun flush() {
        if (!isUsbModeActive) {
            runCatching { super.flush() }
            return
        }
        anchorPositionUs = getCurrentPositionUs(false).takeIf { it != AudioSink.CURRENT_POSITION_NOT_SET } ?: anchorPositionUs
        if (coarseController.handle != 0L && sessionApi.getStats(coarseController.handle, statsScratch) >= 0) {
            baseFramesConsumed = statsScratch[0]
        }
        coarseController.onSeekOrFlush()
    }

    override fun reset() {
        anchorPositionUs = 0L
        baseFramesConsumed = 0L
        if (!isUsbModeActive) {
            runCatching { super.reset() }
            return
        }
        coarseController.onSeekOrFlush()
    }

    override fun release() {
        isUsbModeActive = false
        PlaybackCore.usbActive = false
        coarseController.close()
        runCatching { super.release() }
    }

    override fun setVolume(volume: Float) {
        if (!isUsbModeActive) super.setVolume(volume)
    }
}
