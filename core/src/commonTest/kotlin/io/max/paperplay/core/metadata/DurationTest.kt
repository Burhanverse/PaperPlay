package io.max.paperplay.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals

class DurationTest {
    private fun source(bytes: ByteArray) = TagSource(bytes.size.toLong()) { off, n ->
        val from = off.toInt().coerceAtMost(bytes.size)
        bytes.copyOfRange(from, (from + n).coerceAtMost(bytes.size))
    }

    @Test fun flacLengthIsSamplesOverRate() {
        // fLaC, a STREAMINFO block header, then 18 bytes: 44100 Hz stereo 16 bit with 44100 * 200 samples.
        val b = ByteArray(8 + 34)
        "fLaC".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        val total = 44100L * 200
        val d = 8
        b[d + 10] = (44100 shr 12).toByte()
        b[d + 11] = (44100 shr 4).toByte()
        b[d + 12] = (((44100 and 0xF) shl 4) or (1 shl 1)).toByte()
        b[d + 13] = ((15 shl 4) or ((total shr 32).toInt() and 0xF)).toByte()
        for (k in 0 until 4) b[d + 14 + k] = (total shr (24 - 8 * k)).toByte()
        assertEquals(200, readDurationSeconds(source(b)))
    }

    @Test fun mp3WithoutAHeaderIsSizeOverBitRate() {
        // MPEG 1 layer 3, 128 kbit/s, 44100 Hz, stereo: 16000 bytes a second, so 160000 bytes is 10 seconds.
        val b = ByteArray(160_000)
        b[0] = 0xFF.toByte(); b[1] = 0xFB.toByte(); b[2] = 0x90.toByte(); b[3] = 0x00
        assertEquals(10, readDurationSeconds(source(b)))
    }

    @Test fun unknownDataIsZero() {
        assertEquals(0, readDurationSeconds(source(ByteArray(100))))
    }
}
