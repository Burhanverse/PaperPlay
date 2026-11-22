package io.max.paperplay.player

import android.content.Context
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.max.paperplay.player.usb.UsbRingLog
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-second and run-level diagnostics for DELEGATE mode across all sink chains
 * (Full, No DSP float on/off, Plain float off/on).
 *
 * Hot-path updates use primitive fields on the playback thread (zero heap allocations
 * per buffer, zero Binder IPC, zero locks). Once per second, formats and records a
 * summary line to [UsbRingLog] and [PlayLog], and feeds [Phase0SummaryFormatter].
 */
@OptIn(UnstableApi::class)
internal object DelegateSinkDiag {
    private const val SAMPLE_TS_INTERVAL_MS = 100L
    private const val EMIT_INTERVAL_MS = 1000L

    /** Enabled across all chains in Stage 0; kept readable for safety tests. */
    @Volatile var enabled: Boolean = true
    val isEnabled: Boolean
        get() = enabled

    @Volatile private var trackRef: WeakReference<AudioTrack>? = null
    @Volatile private var lastTrackConfigDesc: String = "none"

    // Sink-chain configuration (set when TapRenderersFactory builds the chain)
    @Volatile private var configuredChainId: String = SinkChainMode.FULL.id
    @Volatile private var configuredFactoryFloat: Boolean = false
    @Volatile private var configuredSinkFloat: Boolean = true

    // AudioTrack attributes from last initialized track
    @Volatile private var atEncDesc: String = "none"
    @Volatile private var atSampleRateHz: Int = 0
    @Volatile private var atBufferBytes: Int = 0

    // Source track metadata from AnalyticsListener (main thread) & Sink.configure (playback thread)
    @Volatile private var sourceCodecDesc: String = ""
    @Volatile private var sourceBitsFromInput: Int = 0
    @Volatile private var sourceRateFromInput: Int = 0
    @Volatile private var configuredTrackLabel: String = "unknown"

    // Cross-thread counters incremented by AnalyticsListener / Pause
    private val bufferingCount = AtomicLong(0L)
    private val sinkUnderrunCount = AtomicLong(0L)
    private val sinkWriteErrCount = AtomicLong(0L)
    private val lastSinkErrCode = AtomicInteger(0)

    // Coordination between analytics pause/resume events and playback-thread windows
    private val resetWindowOnNextSample = AtomicBoolean(false)
    @Volatile private var latestSampleWallMs: Long = 0L
    @Volatile private var latestSamplePosUs: Long = Long.MIN_VALUE
    @Volatile private var pausedPartialWallMs: Long = 0L
    @Volatile private var pausedPartialPosUs: Long = 0L
    @Volatile private var pausedPartialHeadFrames: Long = 0L
    @Volatile private var pausedPartialUnderruns: Long = 0L

    // Last completed non-empty run snapshot (fallback if paused/reset cleared live state)
    @Volatile private var lastCompletedSnapshot: Phase0SummarySnapshot? = null

    private val timestamp = AudioTimestamp()

    // Playback-thread 1-second rolling window state
    @Volatile private var windowStartMs = 0L
    @Volatile private var lastTsSampleMs = 0L
    @Volatile private var lastPosUs = Long.MIN_VALUE
    @Volatile private var lastHeadFrames = -1L
    @Volatile private var lastUnderrunCount = -1L

    private var handleBufferCalls = 0L
    private var bytesOffered = 0L
    private var bytesAccepted = 0L
    private var falseReturns = 0L
    private var dspTimeNs = 0L
    private var dspCalls = 0L
    private var tsOkCount = 0L
    private var tsFailCount = 0L

    // Playback-thread run-level totals (readable cleanly when paused/exported)
    @Volatile private var runActiveWallMs = 0L
    @Volatile private var runPosAdvanceUs = 0L
    @Volatile private var runHbCalls = 0L
    @Volatile private var runOfferedBytes = 0L
    @Volatile private var runAcceptedBytes = 0L
    @Volatile private var runFalseReturns = 0L
    @Volatile private var runDspTimeNs = 0L
    @Volatile private var runDspCalls = 0L
    @Volatile private var runHeadAdvanceFrames = 0L
    @Volatile private var runAtWriteFull = 0L
    @Volatile private var runAtWriteShort = 0L
    @Volatile private var runAtWriteZero = 0L
    @Volatile private var runAtUnderruns = 0L

    val audioTrackProvider: DefaultAudioSink.AudioTrackProvider = object : DefaultAudioSink.AudioTrackProvider {
        override fun getAudioTrack(
            audioTrackConfig: AudioSink.AudioTrackConfig,
            audioAttributes: AudioAttributes,
            audioSessionId: Int,
            context: Context?,
        ): AudioTrack {
            val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                audioTrackConfig,
                audioAttributes,
                audioSessionId,
                context,
            )
            onAudioTrackCreated(track, audioTrackConfig)
            return track
        }
    }

    fun onSinkChainBuilt(chainId: String, factoryFloatOut: Boolean, sinkFloatOut: Boolean) {
        configuredChainId = chainId
        configuredFactoryFloat = factoryFloatOut
        configuredSinkFloat = sinkFloatOut
    }

    fun onAudioTrackCreated(track: AudioTrack, config: AudioSink.AudioTrackConfig) {
        trackRef = WeakReference(track)
        lastHeadFrames = -1L
        lastUnderrunCount = readUnderrunCount(track)
        val enc = when (config.encoding) {
            2 -> "PCM16"
            4 -> "FLOAT"
            21 -> "PCM24"
            22 -> "PCM32"
            else -> "ENC_${config.encoding}"
        }
        atEncDesc = enc
        atSampleRateHz = config.sampleRate
        atBufferBytes = config.bufferSize
        val desc = "enc=$enc rate=${config.sampleRate} chMask=0x${config.channelConfig.toString(16)} buf=${config.bufferSize} offload=${config.offload} tunnel=${config.tunneling}"
        lastTrackConfigDesc = desc
        PlayLog.log("usb", "AudioTrack created [chain=${PlaybackCore.sinkChainMode.id}]: $desc")
        UsbRingLog.record("DelegateDiag", "AudioTrack created [chain=${PlaybackCore.sinkChainMode.id}]: $desc", UsbRingLog.CODE_GENERIC)
    }

    fun recordTrackFormat(format: Format) {
        val mimeCodec = format.sampleMimeType?.substringAfter('/')?.lowercase(Locale.US).orEmpty()
        if (mimeCodec.isNotEmpty() && mimeCodec != "raw") {
            sourceCodecDesc = mimeCodec
        } else if (sourceCodecDesc.isEmpty() && mimeCodec.isNotEmpty()) {
            sourceCodecDesc = mimeCodec
        }
        val bits = pcmEncodingBits(format.pcmEncoding)
        if (bits > 0) sourceBitsFromInput = bits
        if (format.sampleRate > 0) sourceRateFromInput = format.sampleRate
        configuredTrackLabel = buildTrackLabel(
            codec = sourceCodecDesc.ifEmpty { mimeCodec },
            bits = sourceBitsFromInput,
            rateHz = if (format.sampleRate > 0) format.sampleRate else sourceRateFromInput,
        )
    }

    fun onConfigure(format: Format) {
        if (runHbCalls > 0L) {
            lastCompletedSnapshot = snapshotCurrentRun(
                buildId = "session",
                dacPlugged = false,
                bitPerfectOn = false,
            )
        }
        val mimeCodec = format.sampleMimeType?.substringAfter('/')?.lowercase(Locale.US).orEmpty()
        val codec = sourceCodecDesc.ifEmpty { mimeCodec.ifEmpty { "raw" } }
        val cfgBits = pcmEncodingBits(format.pcmEncoding)
        val bits = if (sourceBitsFromInput > 0 && sourceRateFromInput == format.sampleRate) sourceBitsFromInput else cfgBits
        val rateHz = if (format.sampleRate > 0) format.sampleRate else sourceRateFromInput
        configuredTrackLabel = buildTrackLabel(codec, bits, rateHz)

        runActiveWallMs = 0L
        runPosAdvanceUs = 0L
        runHbCalls = 0L
        runOfferedBytes = 0L
        runAcceptedBytes = 0L
        runFalseReturns = 0L
        runDspTimeNs = 0L
        runDspCalls = 0L
        runHeadAdvanceFrames = 0L
        runAtWriteFull = 0L
        runAtWriteShort = 0L
        runAtWriteZero = 0L
        runAtUnderruns = 0L

        bufferingCount.set(0L)
        sinkUnderrunCount.set(0L)
        sinkWriteErrCount.set(0L)
        lastSinkErrCode.set(0)

        pausedPartialWallMs = 0L
        pausedPartialPosUs = 0L
        pausedPartialHeadFrames = 0L
        pausedPartialUnderruns = 0L
        latestSampleWallMs = 0L
        latestSamplePosUs = Long.MIN_VALUE
        resetWindowOnNextSample.set(false)

        onResetOrFlush()
    }

    fun onResetOrFlush() {
        val track = trackRef?.get()
        lastHeadFrames = readUnsignedHeadFrames(track)
        lastUnderrunCount = readUnderrunCount(track)
        lastPosUs = Long.MIN_VALUE
        windowStartMs = 0L
        latestSamplePosUs = Long.MIN_VALUE
        latestSampleWallMs = 0L
        handleBufferCalls = 0L
        bytesOffered = 0L
        bytesAccepted = 0L
        falseReturns = 0L
        dspTimeNs = 0L
        dspCalls = 0L
        tsOkCount = 0L
        tsFailCount = 0L
    }

    fun recordBuffering() {
        bufferingCount.incrementAndGet()
    }

    fun recordSinkUnderrun() {
        sinkUnderrunCount.incrementAndGet()
    }

    fun recordAudioSinkError(error: Exception) {
        sinkWriteErrCount.incrementAndGet()
        val code = when (error) {
            is AudioSink.WriteException -> error.errorCode
            is AudioSink.InitializationException -> -1001
            else -> -1
        }
        lastSinkErrCode.set(code)
    }

    fun onPlaybackPaused() {
        if (resetWindowOnNextSample.compareAndSet(false, true)) {
            val wStart = windowStartMs
            val lWall = latestSampleWallMs
            val pStart = lastPosUs
            val lPos = latestSamplePosUs
            val dt = if (wStart > 0L && lWall > wStart && (lWall - wStart) < 2000L) (lWall - wStart) else 0L
            pausedPartialWallMs = dt
            pausedPartialPosUs = if (dt > 0L && pStart != Long.MIN_VALUE && lPos > pStart) (lPos - pStart) else 0L

            val track = trackRef?.get()
            val curHead = readUnsignedHeadFrames(track)
            val prevHead = lastHeadFrames
            pausedPartialHeadFrames = if (curHead >= 0L && prevHead >= 0L && curHead >= prevHead) (curHead - prevHead) else 0L

            val curUnderruns = readUnderrunCount(track)
            val prevUnderruns = lastUnderrunCount
            pausedPartialUnderruns = if (curUnderruns >= 0L && prevUnderruns >= 0L && curUnderruns >= prevUnderruns) {
                curUnderruns - prevUnderruns
            } else {
                0L
            }
        }
    }

    fun recordHandleBuffer(offeredBytes: Int, acceptedBytes: Int, returnedTrue: Boolean) {
        val offered = offeredBytes.coerceAtLeast(0).toLong()
        val accepted = acceptedBytes.coerceAtLeast(0).toLong()
        handleBufferCalls++
        bytesOffered += offered
        bytesAccepted += accepted
        if (!returnedTrue) {
            falseReturns++
        }

        runHbCalls++
        runOfferedBytes += offered
        runAcceptedBytes += accepted
        if (!returnedTrue) {
            runFalseReturns++
        }
    }

    fun recordAudioTrackWrite(offeredBytes: Int, acceptedBytes: Int, returnedTrue: Boolean) {
        if (offeredBytes <= 0) return
        if (returnedTrue || acceptedBytes >= offeredBytes) {
            runAtWriteFull++
        } else if (acceptedBytes > 0) {
            runAtWriteShort++
        } else {
            runAtWriteZero++
        }
    }

    fun recordDspTime(elapsedNs: Long) {
        if (elapsedNs >= 0L) {
            dspTimeNs += elapsedNs
            dspCalls++
            runDspTimeNs += elapsedNs
            runDspCalls++
        }
    }

    fun maybeSampleAndEmit(
        chainId: String,
        posUs: Long,
        pending: Boolean,
        ended: Boolean,
        sourceEnded: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val track = trackRef?.get()

        if (resetWindowOnNextSample.compareAndSet(true, false)) {
            val pWall = pausedPartialWallMs
            val pPos = pausedPartialPosUs
            val pHead = pausedPartialHeadFrames
            val pUnd = pausedPartialUnderruns
            pausedPartialWallMs = 0L
            pausedPartialPosUs = 0L
            pausedPartialHeadFrames = 0L
            pausedPartialUnderruns = 0L
            if (pWall > 0L) runActiveWallMs += pWall
            if (pPos > 0L) runPosAdvanceUs += pPos
            if (pHead > 0L) runHeadAdvanceFrames += pHead
            if (pUnd > 0L) runAtUnderruns += pUnd

            windowStartMs = now
            lastPosUs = posUs
            lastHeadFrames = readUnsignedHeadFrames(track)
            lastUnderrunCount = readUnderrunCount(track)
            latestSampleWallMs = now
            latestSamplePosUs = posUs
            return
        }

        latestSampleWallMs = now
        if (posUs != Long.MIN_VALUE) {
            latestSamplePosUs = posUs
        }

        if (track != null && now - lastTsSampleMs >= SAMPLE_TS_INTERVAL_MS) {
            lastTsSampleMs = now
            val ok = try {
                track.state == AudioTrack.STATE_INITIALIZED && track.getTimestamp(timestamp)
            } catch (_: Throwable) {
                false
            }
            if (ok) tsOkCount++ else tsFailCount++
        }

        if (windowStartMs == 0L) {
            windowStartMs = now
            lastPosUs = posUs
            lastHeadFrames = readUnsignedHeadFrames(track)
            lastUnderrunCount = readUnderrunCount(track)
            return
        }

        val elapsedMs = now - windowStartMs
        if (elapsedMs < EMIT_INTERVAL_MS) return

        val curHead = readUnsignedHeadFrames(track)
        val headDelta = if (curHead >= 0L && lastHeadFrames >= 0L) {
            curHead - lastHeadFrames
        } else {
            -1L
        }
        val curUnderruns = readUnderrunCount(track)
        val underrunDelta = if (curUnderruns >= 0L && lastUnderrunCount >= 0L && curUnderruns >= lastUnderrunCount) {
            curUnderruns - lastUnderrunCount
        } else {
            0L
        }
        val playState = try {
            track?.playState ?: -1
        } catch (_: Throwable) {
            -1
        }
        val posDeltaUs = if (posUs != Long.MIN_VALUE && lastPosUs != Long.MIN_VALUE) {
            posUs - lastPosUs
        } else {
            0L
        }

        if (handleBufferCalls > 0L || posDeltaUs > 0L || headDelta > 0L) {
            runActiveWallMs += elapsedMs
            if (posDeltaUs > 0L) runPosAdvanceUs += posDeltaUs
            if (headDelta > 0L) runHeadAdvanceFrames += headDelta
            runAtUnderruns += underrunDelta
        }
        pausedPartialWallMs = 0L
        pausedPartialPosUs = 0L
        pausedPartialHeadFrames = 0L
        pausedPartialUnderruns = 0L

        val calls = handleBufferCalls
        val falseRatio = if (calls > 0L) falseReturns.toDouble() / calls.toDouble() else 0.0
        val dspMs = dspTimeNs / 1_000_000.0

        val msg = String.format(
            Locale.US,
            "DELEGATE diag [chain=%s dt=%dms]: pos=%d us (dPos=%d ms) pending=%b ended=%b srcEnd=%b | calls=%d offered=%dB accepted=%dB falseRatio=%.2f(%d/%d) dsp=%.2fms(%d) headDelta=%dfr atUnderruns=%d ts=%d/%d state=%d bufTotal=%d",
            chainId,
            elapsedMs,
            posUs,
            posDeltaUs / 1000L,
            pending,
            ended,
            sourceEnded,
            calls,
            bytesOffered,
            bytesAccepted,
            falseRatio,
            falseReturns,
            calls,
            dspMs,
            dspCalls,
            headDelta,
            underrunDelta,
            tsOkCount,
            tsFailCount,
            playState,
            bufferingCount.get(),
        )
        PlayLog.log("usb", msg)
        UsbRingLog.record("DelegateDiag", msg, UsbRingLog.CODE_GENERIC)

        // Reset per-second window
        windowStartMs = now
        lastPosUs = posUs
        lastHeadFrames = curHead
        lastUnderrunCount = curUnderruns
        handleBufferCalls = 0L
        bytesOffered = 0L
        bytesAccepted = 0L
        falseReturns = 0L
        dspTimeNs = 0L
        dspCalls = 0L
        tsOkCount = 0L
        tsFailCount = 0L
    }

    fun formatPhase0Summary(buildId: String, dacPlugged: Boolean, bitPerfectOn: Boolean): String {
        val snap = if (runHbCalls > 0L) {
            snapshotCurrentRun(buildId, dacPlugged, bitPerfectOn)
        } else {
            lastCompletedSnapshot?.copy(
                build = buildId,
                dacPlugged = dacPlugged,
                bitPerfectOn = bitPerfectOn,
            ) ?: snapshotCurrentRun(buildId, dacPlugged, bitPerfectOn)
        }
        return Phase0SummaryFormatter.format(snap)
    }

    private fun snapshotCurrentRun(buildId: String, dacPlugged: Boolean, bitPerfectOn: Boolean): Phase0SummarySnapshot {
        val extraWallMs: Long
        val extraPosUs: Long
        val extraHead: Long
        val extraUnderruns: Long

        if (resetWindowOnNextSample.get()) {
            extraWallMs = pausedPartialWallMs
            extraPosUs = pausedPartialPosUs
            extraHead = pausedPartialHeadFrames
            extraUnderruns = pausedPartialUnderruns
        } else {
            val wStart = windowStartMs
            val lWall = latestSampleWallMs
            val pStart = lastPosUs
            val lPos = latestSamplePosUs
            extraWallMs = if (wStart > 0L && lWall > wStart && (lWall - wStart) < 2000L) (lWall - wStart) else 0L
            extraPosUs = if (extraWallMs > 0L && pStart != Long.MIN_VALUE && lPos > pStart) (lPos - pStart) else 0L
            val track = trackRef?.get()
            val curHead = readUnsignedHeadFrames(track)
            val prevHead = lastHeadFrames
            extraHead = if (curHead >= 0L && prevHead >= 0L && curHead >= prevHead) (curHead - prevHead) else 0L
            val curUnd = readUnderrunCount(track)
            val prevUnd = lastUnderrunCount
            extraUnderruns = if (curUnd >= 0L && prevUnd >= 0L && curUnd >= prevUnd) (curUnd - prevUnd) else 0L
        }

        return Phase0SummarySnapshot(
            build = buildId,
            chain = configuredChainId,
            dacPlugged = dacPlugged,
            bitPerfectOn = bitPerfectOn,
            durMs = runActiveWallMs + extraWallMs,
            track = configuredTrackLabel,
            posAdvanceUs = runPosAdvanceUs + extraPosUs,
            bufferingCount = bufferingCount.get(),
            hbCalls = runHbCalls,
            offeredBytes = runOfferedBytes,
            acceptedBytes = runAcceptedBytes,
            falseReturns = runFalseReturns,
            dspTimeNs = runDspTimeNs,
            dspCalls = runDspCalls,
            atEnc = atEncDesc,
            atRate = atSampleRateHz,
            atBuf = atBufferBytes,
            atHeadAdvanceFrames = runHeadAdvanceFrames + extraHead,
            atWriteFull = runAtWriteFull,
            atWriteShort = runAtWriteShort,
            atWriteZero = runAtWriteZero,
            atWriteErr = sinkWriteErrCount.get(),
            atLastErr = lastSinkErrCode.get(),
            atUnderruns = runAtUnderruns + extraUnderruns,
            sinkUnderruns = sinkUnderrunCount.get(),
            factoryFloat = configuredFactoryFloat,
            sinkFloat = configuredSinkFloat,
        )
    }

    private fun pcmEncodingBits(enc: Int): Int = when (enc) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    private fun buildTrackLabel(codec: String, bits: Int, rateHz: Int): String {
        val c = codec.ifBlank { "unknown" }
        val b = if (bits > 0) "${bits}b" else "?b"
        val r = if (rateHz > 0) "${rateHz}Hz" else "?Hz"
        return "$c/$b/$r"
    }

    private fun readUnsignedHeadFrames(track: AudioTrack?): Long {
        if (track == null) return -1L
        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) -1L
            else track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readUnderrunCount(track: AudioTrack?): Long {
        if (track == null || Build.VERSION.SDK_INT < 24) return -1L
        return try {
            if (track.state != AudioTrack.STATE_INITIALIZED) -1L
            else track.underrunCount.toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    fun currentAudioTrackConfigDesc(): String = lastTrackConfigDesc
}
