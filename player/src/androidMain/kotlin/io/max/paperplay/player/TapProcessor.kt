package io.max.paperplay.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps the real sink: converts PCM to float, runs the Rust equalizer ([PlaybackCore.eqGains]) and hands float on.
 * A sink wrapper rather than an AudioProcessor, because Media3 1.11 never configured a custom processor here.
 */
@OptIn(UnstableApi::class)
internal class EngineAudioSink(
    sink: AudioSink,
    private val forceInactive: Boolean = false,
) : ForwardingAudioSink(sink) {
    private var handle = 0L
    private var active = false
    private var enc = C.ENCODING_INVALID
    private var channels = 2
    private var rate = 44100
    private var applied: List<Float>? = null
    private var appliedBass = 0f
    private var appliedExact = false
    private var appliedDry = false
    private var appliedWidth = 1f
    private var appliedCorrection: FloatArray? = null
    private var appliedOptions = 0
    private var pending: ByteBuffer? = null
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var calls = 0

    override fun configure(config: AudioSink.AudioSinkConfig) {
        freeEngine()
        val f = config.format
        val e = f.pcmEncoding
        val disabledByDevOption = forceInactive || PlaybackCore.sinkChainMode.isNoDsp
        active = !disabledByDevOption && !PlaybackCore.usbActive && !PlaybackCore.usbExclusiveMode &&
            EngineNative.available && f.sampleMimeType == MimeTypes.AUDIO_RAW &&
            (e == C.ENCODING_PCM_16BIT || e == C.ENCODING_PCM_24BIT || e == C.ENCODING_PCM_32BIT || e == C.ENCODING_PCM_FLOAT)
        PlaybackCore.engineActive = active
        PlaybackCore.engineIn = if (active) "${when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; else -> "32-bit float" }} PCM, ${f.sampleRate} Hz" else null
        PlaybackCore.engineOut = if (active) "32-bit float, ${f.sampleRate} Hz, ${f.channelCount} ch" else null
        if (!active) { super.configure(config); return }
        enc = e
        channels = f.channelCount
        rate = f.sampleRate
        handle = EngineNative.create(f.sampleRate, f.channelCount)
        applied = null
        appliedBass = 0f
        appliedExact = false
        appliedDry = false
        appliedWidth = 1f
        appliedCorrection = null
        appliedOptions = 0
        val floatFormat = f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
        super.configure(
            AudioSink.AudioSinkConfig.Builder(floatFormat)
                .setMediaPeriodId(config.mediaPeriodId)
                .setOutputChannelMapping(config.outputChannelMapping)
                .setPreferredBufferSizeOverride(config.preferredBufferSizeOverride)
                .setTimeline(config.timeline)
                .build(),
        )
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!active || PlaybackCore.usbActive) {
            if (!PlaybackCore.usbActive && DelegateSinkDiag.isEnabled) {
                val before = buffer.remaining()
                val ok = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                DelegateSinkDiag.recordAudioTrackWrite(before, (before - buffer.remaining()).coerceAtLeast(0), ok)
                return ok
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        var p = pending
        if (p == null) {
            if (DelegateSinkDiag.isEnabled) {
                val t0 = System.nanoTime()
                p = convert(buffer)
                DelegateSinkDiag.recordDspTime(System.nanoTime() - t0)
            } else {
                p = convert(buffer)
            }
            if (p == null) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            pending = p
        }
        val done = if (DelegateSinkDiag.isEnabled) {
            val pBefore = p.remaining()
            val ok = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
            DelegateSinkDiag.recordAudioTrackWrite(pBefore, (pBefore - p.remaining()).coerceAtLeast(0), ok)
            ok
        } else {
            super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
        }
        if (done) pending = null
        return done
    }

    /** Takes all of [src] (moves it to its limit) and returns it as float, equalized. */
    private fun convert(src: ByteBuffer): ByteBuffer? {
        val step = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val samples = src.remaining() / step
        if (samples == 0) return null
        val bytes = samples * 4
        if (out.capacity() < bytes) out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        out.clear()
        val gains = PlaybackCore.eqGains
        if (gains !== applied) { EngineNative.setEq(handle, gains?.toFloatArray() ?: FloatArray(0)); applied = gains }
        val bass = PlaybackCore.bassDb
        if (bass != appliedBass) { EngineNative.setBass(handle, bass); appliedBass = bass }
        val exact = PlaybackCore.exactLevel
        if (exact != appliedExact) { EngineNative.setExact(handle, exact); appliedExact = exact; PlayLog.log("engine", "exact level $exact") }
        val dry = PlaybackCore.dryCompare
        if (dry != appliedDry) { EngineNative.setDry(handle, dry); appliedDry = dry; PlayLog.log("engine", "compare with original $dry") }
        val width = PlaybackCore.width
        if (width != appliedWidth) { EngineNative.setWidth(handle, width); appliedWidth = width; PlayLog.log("engine", "width $width") }
        val corr = PlaybackCore.correction
        if (corr !== appliedCorrection) { EngineNative.setCorrection(handle, corr ?: FloatArray(0)); appliedCorrection = corr; PlayLog.log("engine", "correction ${(corr?.size ?: 0) / 4} filters") }
        val opts = PlaybackCore.options
        if (opts != appliedOptions) { EngineNative.setOptions(handle, opts); appliedOptions = opts; PlayLog.log("engine", "options $opts") }
        tap(src, samples)
        val kind = when (enc) { C.ENCODING_PCM_16BIT -> 0; C.ENCODING_PCM_24BIT -> 1; C.ENCODING_PCM_FLOAT -> 3; else -> 2 }
        // Decoder buffers are direct, so Rust reads them in place; anything else is converted here first.
        if (!src.isDirect || EngineNative.processPcm(handle, src, src.position(), kind, samples, out) != samples) {
            val s = src.duplicate().order(ByteOrder.nativeOrder())
            repeat(samples) {
                out.putFloat(when (enc) {
                    C.ENCODING_PCM_FLOAT -> s.getFloat()
                    C.ENCODING_PCM_16BIT -> s.getShort() / 32768f
                    C.ENCODING_PCM_24BIT -> (((s.get().toInt() and 0xff) or ((s.get().toInt() and 0xff) shl 8) or (s.get().toInt() shl 16)) / 8388608f)
                    else -> s.getInt() / 2147483648f
                })
            }
            EngineNative.process(handle, out, samples)
        }
        src.position(src.position() + samples * step)
        if (calls++ % 20 == 0) {
            val st = PlaybackCore.engineStats?.takeIf { it.size == 7 } ?: FloatArray(7)
            EngineNative.stats(handle, st)
            PlaybackCore.engineStats = st.copyOf()
        }
        out.limit(bytes).position(0)
        return out
    }

    /**
     * Feeds a mono mix of [samples] samples of [src] to [AudioTap], for the standby visualizer. The wrapped sink gets float, and
     * Media3 skips its processors for float, so the tap in [TapProcessor] never sees the audio while the engine is on.
     */
    private fun tap(src: ByteBuffer, samples: Int) {
        val ch = channels.coerceAtLeast(1)
        val s = src.duplicate().order(ByteOrder.nativeOrder())
        repeat(samples / ch) {
            var sum = 0f
            for (c in 0 until ch) sum += when (enc) {
                C.ENCODING_PCM_FLOAT -> s.getFloat()
                C.ENCODING_PCM_16BIT -> s.getShort() / 32768f
                C.ENCODING_PCM_24BIT -> (((s.get().toInt() and 0xff) or ((s.get().toInt() and 0xff) shl 8) or (s.get().toInt() shl 16)) / 8388608f)
                else -> s.getInt() / 2147483648f
            }
            AudioTap.push(rate, sum / ch)
        }
    }

    override fun flush() { pending = null; if (handle != 0L) EngineNative.reset(handle); AudioTap.reset(); super.flush() }
    override fun reset() { freeEngine(); AudioTap.reset(); super.reset() }
    override fun release() { freeEngine(); AudioTap.reset(); super.release() }
    private fun freeEngine() { pending = null; if (handle != 0L) EngineNative.destroy(handle); handle = 0; active = false; PlaybackCore.engineActive = false }
}

/**
 * Passive diagnostic wrapper used ONLY when the developer "Sink chain" option is set to Plain.
 * Bypasses EngineAudioSink, UsbAudioSink, and TapProcessor while recording per-second counters.
 */
@OptIn(UnstableApi::class)
internal class PlainDiagAudioSink(
    sink: AudioSink,
    private val chainId: String = SinkChainMode.PLAIN_FLOAT_OFF.id,
) : ForwardingAudioSink(sink) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        if (DelegateSinkDiag.isEnabled) DelegateSinkDiag.onConfigure(config.format)
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val before = buffer.remaining()
        val ok = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val accepted = (before - buffer.remaining()).coerceAtLeast(0)
        DelegateSinkDiag.recordHandleBuffer(before, accepted, ok)
        DelegateSinkDiag.recordAudioTrackWrite(before, accepted, ok)
        return ok
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val pos = super.getCurrentPositionUs(sourceEnded)
        DelegateSinkDiag.maybeSampleAndEmit(
            chainId = chainId,
            posUs = pos,
            pending = super.hasPendingData(),
            ended = super.isEnded(),
            sourceEnded = sourceEnded,
        )
        return pos
    }

    override fun flush() {
        DelegateSinkDiag.onResetOrFlush()
        super.flush()
    }

    override fun reset() {
        DelegateSinkDiag.onResetOrFlush()
        super.reset()
    }
}

/** Passes audio through unchanged and feeds a mono mix to [AudioTap]. Inactive for formats it cannot read. */
@OptIn(UnstableApi::class)
internal class TapProcessor : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) inputAudioFormat else AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val src = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate
        val enc = inputAudioFormat.encoding
        val step = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        while (src.remaining() >= step * ch) {
            var sum = 0f
            for (c in 0 until ch) sum += when (enc) {
                C.ENCODING_PCM_FLOAT -> src.getFloat()
                C.ENCODING_PCM_16BIT -> src.getShort() / 32768f
                C.ENCODING_PCM_24BIT -> (((src.get().toInt() and 0xff) or ((src.get().toInt() and 0xff) shl 8) or (src.get().toInt() shl 16)) / 8388608f)
                else -> src.getInt() / 2147483648f
            }
            AudioTap.push(rate, sum / ch)
        }
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() { AudioTap.reset() }
    override fun onReset() { AudioTap.reset() }
}

/**
 * Upstream 1:1 normal Android sink chain (`origin/main`): [EngineAudioSink] wrapping [DefaultAudioSink]
 * with float output enabled and [TapProcessor] for visualizer capture. Zero USB wrappers or diagnostic hooks.
 */
@OptIn(UnstableApi::class)
internal fun buildUpstreamNormalAudioSink(
    context: Context,
    enableAudioOutputPlaybackParams: Boolean,
): AudioSink {
    val defaultSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(true)
        .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
        .setAudioProcessors(arrayOf(TapProcessor()))
        .build()
    return EngineAudioSink(defaultSink)
}

@OptIn(UnstableApi::class)
internal class TapRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        if (PlaybackCore.useUnifiedBitPerfect) {
            setEnableAudioFloatOutput(!PlaybackCore.usbExclusiveMode)
        } else {
            // Prefer integer PCM (PCM_24BIT / PCM_32BIT) from MediaCodec decoder for legacy USB streaming
            setEnableAudioFloatOutput(false)
        }
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val chainMode = PlaybackCore.sinkChainMode
        if (PlaybackCore.useUnifiedBitPerfect) {
            if (!PlaybackCore.usbExclusiveMode && chainMode == SinkChainMode.FULL) {
                PlaybackCore.usbActive = false
                io.max.paperplay.player.usb.Api34BitPerfectMixer.clear(context)
                return buildUpstreamNormalAudioSink(context, enableAudioOutputPlaybackParams)
            }
            if (PlaybackCore.devApi34MixerEnabled && PlaybackCore.usbExclusiveMode && android.os.Build.VERSION.SDK_INT >= 34) {
                io.max.paperplay.player.usb.Api34BitPerfectMixer.buildVerifiedSinkOrNull(
                    context = context,
                    enableAudioOutputPlaybackParams = enableAudioOutputPlaybackParams,
                )?.let { verifiedSink ->
                    PlaybackCore.usbActive = false
                    PlaybackCore.engineActive = false
                    return verifiedSink
                }
            }
            val normalSink = buildUpstreamNormalAudioSink(context, enableAudioOutputPlaybackParams)
            return io.max.paperplay.player.usb.UnifiedUsbAudioSink(context, normalSink)
        }
        val sinkFloat = chainMode.sinkFloatOutput
        DelegateSinkDiag.onSinkChainBuilt(chainMode.id, factoryFloatOut = enableFloatOutput, sinkFloatOut = sinkFloat)
        if (chainMode.isPlain) {
            PlaybackCore.engineActive = false
            PlaybackCore.usbActive = false
            val plainDefault = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(sinkFloat)
                .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
                .setAudioTrackProvider(DelegateSinkDiag.audioTrackProvider)
                .build()
            return PlainDiagAudioSink(plainDefault, chainMode.id)
        }
        val defaultSink = DefaultAudioSink.Builder(context)
            // 24-bit FLAC decodes to 24-bit PCM; without float output the sink cuts it to 16 bits with no dither, which sounds grainy.
            .setEnableFloatOutput(sinkFloat)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(TapProcessor()))
            .setAudioTrackProvider(DelegateSinkDiag.audioTrackProvider)
            .build()
        val normalSink = EngineAudioSink(defaultSink, forceInactive = chainMode.isNoDsp)
        return io.max.paperplay.player.usb.UsbAudioSink(context, normalSink)
    }
}
