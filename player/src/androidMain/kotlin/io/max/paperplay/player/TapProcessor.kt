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
internal class EngineAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private var handle = 0L
    private var active = false
    private var enc = C.ENCODING_INVALID
    private var applied: List<Float>? = null
    private var appliedBass = 0f
    private var pending: ByteBuffer? = null
    private var out: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var calls = 0

    override fun configure(config: AudioSink.AudioSinkConfig) {
        freeEngine()
        val f = config.format
        val e = f.pcmEncoding
        active = EngineNative.available && f.sampleMimeType == MimeTypes.AUDIO_RAW &&
            (e == C.ENCODING_PCM_16BIT || e == C.ENCODING_PCM_24BIT || e == C.ENCODING_PCM_32BIT || e == C.ENCODING_PCM_FLOAT)
        PlaybackCore.engineActive = active
        PlaybackCore.engineIn = if (active) "${when (f.pcmEncoding) { C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; else -> "32-bit float" }} PCM, ${f.sampleRate} Hz" else null
        PlaybackCore.engineOut = if (active) "32-bit float, ${f.sampleRate} Hz, ${f.channelCount} ch" else null
        if (!active) { super.configure(config); return }
        enc = e
        handle = EngineNative.create(f.sampleRate, f.channelCount)
        applied = null
        appliedBass = 0f
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
        if (!active) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        var p = pending
        if (p == null) {
            p = convert(buffer) ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            pending = p
        }
        val done = super.handleBuffer(p, presentationTimeUs, encodedAccessUnitCount)
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
        val s = src.duplicate().order(ByteOrder.nativeOrder())
        repeat(samples) {
            out.putFloat(when (enc) {
                C.ENCODING_PCM_FLOAT -> s.getFloat()
                C.ENCODING_PCM_16BIT -> s.getShort() / 32768f
                C.ENCODING_PCM_24BIT -> (((s.get().toInt() and 0xff) or ((s.get().toInt() and 0xff) shl 8) or (s.get().toInt() shl 16)) / 8388608f)
                else -> s.getInt() / 2147483648f
            })
        }
        src.position(src.position() + samples * step)
        val gains = PlaybackCore.eqGains
        if (gains !== applied) { EngineNative.setEq(handle, gains?.toFloatArray() ?: FloatArray(0)); applied = gains }
        val bass = PlaybackCore.bassDb
        if (bass != appliedBass) { EngineNative.setBass(handle, bass); appliedBass = bass }
        EngineNative.process(handle, out, samples)
        if (calls++ % 20 == 0) {
            val st = PlaybackCore.engineStats?.takeIf { it.size == 6 } ?: FloatArray(6)
            EngineNative.stats(handle, st)
            PlaybackCore.engineStats = st.copyOf()
        }
        out.limit(bytes).position(0)
        return out
    }

    override fun flush() { pending = null; if (handle != 0L) EngineNative.reset(handle); super.flush() }
    override fun reset() { freeEngine(); super.reset() }
    private fun freeEngine() { pending = null; if (handle != 0L) EngineNative.destroy(handle); handle = 0; active = false; PlaybackCore.engineActive = false }
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

@OptIn(UnstableApi::class)
internal class TapRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        EngineAudioSink(DefaultAudioSink.Builder(context)
            // 24-bit FLAC decodes to 24-bit PCM; without float output the sink cuts it to 16 bits with no dither, which sounds grainy.
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(TapProcessor()))
            .build())
}
