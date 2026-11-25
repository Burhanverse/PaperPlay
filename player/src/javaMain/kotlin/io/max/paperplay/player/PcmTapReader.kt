package io.max.paperplay.player

import java.nio.ByteBuffer

/**
 * Lock-free, zero-allocation PCM reader that feeds [AudioTap] from a read-only slice of a [ByteBuffer].
 *
 * Uses absolute index reads (`buf.get(offset)`) in little-endian byte order so it never calls `duplicate()`,
 * never mutates `buf.position()`, `buf.limit()`, `buf.order()`, or any sample byte.
 */
object PcmTapReader {
    const val PCM_16 = 0
    const val PCM_24 = 1
    const val PCM_32 = 2
    const val PCM_FLOAT = 3

    fun feedPcmSlice(
        buf: ByteBuffer,
        startPos: Int,
        byteLength: Int,
        sampleRateHz: Int,
        channels: Int,
        pcmKind: Int,
        ptsUs: Long = -1L,
        sourceTag: String = "",
    ) {
        if (!AudioTap.wanted) return
        if (byteLength <= 0 || sampleRateHz <= 0 || channels <= 0) return

        val sampleBytes = when (pcmKind) {
            PCM_16 -> 2
            PCM_24 -> 3
            PCM_32, PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = sampleBytes * channels
        val maxAvail = (buf.limit() - startPos).coerceAtLeast(0)
        val usableBytes = minOf(byteLength, maxAvail)
        val frames = usableBytes / frameBytes
        if (frames <= 0) return

        val hasPts = ptsUs >= 0L
        var offset = startPos
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val sample = when (pcmKind) {
                    PCM_16 -> {
                        val b0 = buf.get(offset).toInt() and 0xFF
                        val b1 = buf.get(offset + 1).toInt()
                        ((b1 shl 8) or b0).toShort() / 32768f
                    }
                    PCM_24 -> {
                        val b0 = buf.get(offset).toInt() and 0xFF
                        val b1 = buf.get(offset + 1).toInt() and 0xFF
                        val b2 = buf.get(offset + 2).toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
                    }
                    PCM_32 -> {
                        val b0 = buf.get(offset).toInt() and 0xFF
                        val b1 = buf.get(offset + 1).toInt() and 0xFF
                        val b2 = buf.get(offset + 2).toInt() and 0xFF
                        val b3 = buf.get(offset + 3).toInt()
                        ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0) / 2147483648f
                    }
                    else -> {
                        val b0 = buf.get(offset).toInt() and 0xFF
                        val b1 = buf.get(offset + 1).toInt() and 0xFF
                        val b2 = buf.get(offset + 2).toInt() and 0xFF
                        val b3 = buf.get(offset + 3).toInt() and 0xFF
                        Float.fromBits((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0)
                    }
                }
                sum += sample
                offset += sampleBytes
            }
            val framePts = if (hasPts) ptsUs + (f.toLong() * 1_000_000L) / sampleRateHz else -1L
            AudioTap.pushWithPts(sampleRateHz, sum / channels, framePts, sourceTag)
        }
    }
}
