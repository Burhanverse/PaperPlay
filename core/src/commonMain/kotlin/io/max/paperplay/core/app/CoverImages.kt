package io.max.paperplay.core.app

/** The picture work covers need, which differs by platform (Android bitmaps, Java ImageIO). */
expect object CoverImages {
    /**
     * Scales the image in [bytes] down so its longest side is at most [maxEdge] pixels and returns it as JPEG at [quality] (1 to 100).
     * Null when the picture is already that small or cannot be read: the caller then keeps the original bytes.
     */
    fun shrink(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray?

    /** The whole file, or null when it cannot be read. */
    fun readFile(path: String): ByteArray?
}

/** Longest side of a stored cover. The biggest place a cover is drawn is the full player, which needs about this much on a phone. */
const val COVER_MAX_EDGE = 1000
const val COVER_JPEG_QUALITY = 85

/** A name for these exact bytes (64-bit FNV-1a plus the length), so the same picture is stored once however many songs use it. */
fun coverContentName(bytes: ByteArray): String {
    var h = -0x340d631b7bdddcdbL // FNV offset basis
    for (b in bytes) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
    return "c_" + h.toULong().toString(16).padStart(16, '0') + "_" + bytes.size.toString(16)
}
