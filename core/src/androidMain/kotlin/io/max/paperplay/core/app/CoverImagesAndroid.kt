package io.max.paperplay.core.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

actual object CoverImages {
    actual fun shrink(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= maxEdge) return null
        // Decode at a power-of-two fraction first so a 3000 px picture is never held at full size, then scale the rest.
        var sample = 1
        while (longest / (sample * 2) >= maxEdge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true) else decoded
        val buf = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, buf)
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        buf.toByteArray()
    }.getOrNull()

    actual fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()
}
