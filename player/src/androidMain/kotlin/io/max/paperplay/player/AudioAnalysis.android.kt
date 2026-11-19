package io.max.paperplay.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

/**
 * Decodes three 20-second windows of a file (at a fifth, half and four fifths of the way) with the platform decoder, mixes them to
 * mono and hands them to the Rust engine. Songs under a minute are read from the start.
 */
actual object AudioAnalysis {
    private const val WINDOW_SEC = 20
    private const val FEATURES = 45

    actual val version: Int = if (EngineNative.available) runCatching { EngineNative.featureVersion() }.getOrDefault(0) else 0

    actual fun analyze(path: String): FloatArray? {
        if (version == 0) return null
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val perWindow = rate * WINDOW_SEC
            val starts = if (durUs > 75_000_000L) listOf(0.2, 0.5, 0.8).map { (durUs * it).toLong() } else listOf(0L)
            val want = if (starts.size == 1) rate * 60 else perWindow * starts.size
            val pcm = FloatArray(want)
            var filled = 0
            for (startUs in starts) {
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                c.flush()
                val limit = minOf(want, filled + if (starts.size == 1) want else perWindow)
                filled = decodeInto(c, ex, pcm, filled, limit)
            }
            if (filled < rate * 3) return null
            val out = FloatArray(FEATURES)
            return if (EngineNative.analyze(pcm, filled, rate, out)) out else null
        } catch (e: Exception) {
            PlayLog.log("analysis", "failed for $path: $e")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
        }
    }

    /** Decodes from the extractor's position into [pcm] from [from] until [limit] or the end; returns the new fill. */
    private fun decodeInto(c: MediaCodec, ex: MediaExtractor, pcm: FloatArray, from: Int, limit: Int): Int {
        var filled = from
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var idle = 0
        while (filled < limit && idle < 200) {
            if (!inputDone) {
                val i = c.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val buf = c.getInputBuffer(i)!!
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0) {
                        c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = c.dequeueOutputBuffer(info, 10_000)
            if (o < 0) { idle++; continue }
            idle = 0
            val of = c.getOutputFormat(o)
            val ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val enc = if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) of.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            val bytes = when (enc) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; else -> 2 }
            val frames = info.size / (bytes * ch)
            for (f in 0 until frames) {
                if (filled >= limit) break
                var sum = 0f
                for (k in 0 until ch) {
                    sum += when (enc) {
                        AudioFormat.ENCODING_PCM_FLOAT -> buf.float
                        AudioFormat.ENCODING_PCM_32BIT -> buf.int / 2147483648f
                        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            val b0 = buf.get().toInt() and 0xFF; val b1 = buf.get().toInt() and 0xFF; val b2 = buf.get().toInt()
                            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                        }
                        else -> buf.short / 32768f
                    }
                }
                pcm[filled++] = sum / ch
            }
            c.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
        return filled
    }
}
