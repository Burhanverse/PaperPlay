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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        DefaultAudioSink.Builder(context)
            // 24-bit FLAC decodes to 24-bit PCM; without float output the sink cuts it to 16 bits with no dither, which sounds grainy.
            .setEnableFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(TapProcessor()))
            .build()
}
