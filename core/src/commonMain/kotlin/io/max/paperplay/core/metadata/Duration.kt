package io.max.paperplay.core.metadata

/**
 * The length of a song in whole seconds, read from the header of FLAC, MP3, M4A/MP4, Ogg (Vorbis, Opus) and WAV files; 0 when the
 * format is unknown or unreadable. Telegram shows 0:00 for a song that was sent without it.
 */
fun readDurationSeconds(src: TagSource): Int = try {
    durationOf(src).coerceAtLeast(0)
} catch (e: Exception) {
    0
}

private fun durationOf(src: TagSource): Int {
    var start = 0L
    val head = src.read(0, 12)
    if (head.has(0, "ID3") && head.size >= 10) start = 10L + ((head[6].toInt() and 0x7F) shl 21 or ((head[7].toInt() and 0x7F) shl 14) or ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F))
    val magic = if (start == 0L) head else src.read(start, 12)
    return when {
        magic.has(0, "fLaC") -> flac(src, start)
        magic.has(4, "ftyp") -> mp4(src)
        magic.has(0, "OggS") -> ogg(src, start)
        magic.has(0, "RIFF") && magic.has(8, "WAVE") -> wav(src)
        else -> mp3(src, start)
    }
}

private fun ByteArray.has(at: Int, t: String): Boolean {
    if (at < 0 || at + t.length > size) return false
    for (i in t.indices) if (this[at + i] != t[i].code.toByte()) return false
    return true
}

private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
private fun ByteArray.be32(at: Int) = (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
private fun ByteArray.be64(at: Int) = (be32(at) shl 32) or be32(at + 4)
private fun ByteArray.le32(at: Int) = u8(at).toLong() or (u8(at + 1).toLong() shl 8) or (u8(at + 2).toLong() shl 16) or (u8(at + 3).toLong() shl 24)
private fun ByteArray.le64(at: Int) = le32(at) or (le32(at + 4) shl 32)

/** STREAMINFO: a 20-bit sample rate and a 36-bit count of samples. */
private fun flac(src: TagSource, start: Long): Int {
    val d = src.read(start + 8, 18)
    if (d.size < 18) return 0
    val rate = (d.u8(10) shl 12) or (d.u8(11) shl 4) or (d.u8(12) shr 4)
    val total = ((d.u8(13) and 0x0F).toLong() shl 32) or d.be32(14)
    return if (rate > 0) (total / rate).toInt() else 0
}

/** The movie header (mvhd) inside moov holds a timescale and a duration. */
private fun mp4(src: TagSource): Int {
    fun find(from: Long, to: Long, name: String): Pair<Long, Long>? {
        var pos = from
        while (pos + 8 <= to) {
            val h = src.read(pos, 16)
            if (h.size < 8) return null
            var size = h.be32(0)
            var header = 8
            if (size == 1L && h.size >= 16) { size = h.be64(8); header = 16 } else if (size == 0L) size = to - pos
            if (size < header) return null
            if (h.has(4, name)) return (pos + header) to (pos + size)
            pos += size
        }
        return null
    }
    val moov = find(0, src.length, "moov") ?: return 0
    val mvhd = find(moov.first, moov.second, "mvhd") ?: return 0
    val d = src.read(mvhd.first, 32)
    if (d.size < 20) return 0
    val (scale, length) = if (d.u8(0) == 1 && d.size >= 32) d.be32(20) to d.be64(24) else d.be32(12) to d.be32(16)
    return if (scale > 0) (length / scale).toInt() else 0
}

/** The last page's granule position says how many samples there are; the first page says the sample rate. */
private fun ogg(src: TagSource, start: Long): Int {
    val first = src.read(start, 400)
    if (first.size < 40) return 0
    val body = 27 + (first.u8(26))
    val rate: Long
    var preSkip = 0L
    when {
        first.has(body, "OpusHead") && first.size >= body + 12 -> { rate = 48000; preSkip = (first.u8(body + 10) or (first.u8(body + 11) shl 8)).toLong() }
        first.has(body + 1, "vorbis") && first.size >= body + 16 -> rate = first.le32(body + 12)
        else -> return 0
    }
    if (rate <= 0) return 0
    val tailLength = minOf(65536L, src.length - start).toInt()
    val tail = src.read(src.length - tailLength, tailLength)
    var i = tail.size - 14
    while (i >= 0 && !tail.has(i, "OggS")) i--
    if (i < 0) return 0
    return ((tail.le64(i + 6) - preSkip) / rate).toInt()
}

private fun wav(src: TagSource): Int {
    val h = src.read(12, 4096)
    var pos = 0
    var byteRate = 0L
    while (pos + 8 <= h.size) {
        val size = h.le32(pos + 4)
        if (h.has(pos, "fmt ") && pos + 20 <= h.size) byteRate = h.le32(pos + 16)
        if (h.has(pos, "data")) return if (byteRate > 0) (size / byteRate).toInt() else 0
        pos += (8 + size + (size and 1)).toInt()
    }
    return 0
}

private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

/** Layer 3 only: the frame count of a Xing or Info header when there is one, otherwise the size over the bit rate. */
private fun mp3(src: TagSource, start: Long): Int {
    val buf = src.read(start, 8192)
    var i = 0
    while (i + 4 < buf.size && !(buf.u8(i) == 0xFF && (buf.u8(i + 1) and 0xE0) == 0xE0)) i++
    if (i + 4 >= buf.size) return 0
    val version = (buf.u8(i + 1) shr 3) and 3 // 3 is MPEG 1, 2 is MPEG 2, 0 is MPEG 2.5
    val layer = (buf.u8(i + 1) shr 1) and 3 // 1 is layer 3
    if (version == 1 || layer != 1) return 0
    val bitrate = (if (version == 3) MPEG1_L3 else MPEG2_L3).getOrElse(buf.u8(i + 2) shr 4) { 0 }
    val sampleRate = (when (version) { 3 -> intArrayOf(44100, 48000, 32000); 2 -> intArrayOf(22050, 24000, 16000); else -> intArrayOf(11025, 12000, 8000) })
        .getOrElse((buf.u8(i + 2) shr 2) and 3) { 0 }
    if (sampleRate == 0) return 0
    val mono = (buf.u8(i + 3) shr 6) == 3
    val sideInfo = if (version == 3) (if (mono) 17 else 32) else (if (mono) 9 else 17)
    val x = i + 4 + sideInfo
    if (x + 12 <= buf.size && (buf.has(x, "Xing") || buf.has(x, "Info")) && (buf.u8(x + 7) and 1) != 0) {
        val frames = buf.be32(x + 8)
        return (frames * (if (version == 3) 1152 else 576) / sampleRate).toInt()
    }
    if (bitrate == 0) return 0
    return ((src.length - start - i) * 8 / (bitrate * 1000L)).toInt()
}
