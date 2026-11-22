package io.max.paperplay.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Data
import org.jetbrains.skia.makeFromFileName
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) = Unit

@Composable
actual fun rememberAppForeground(): Boolean {
    val foreground by AppForegroundState.isForegroundFlow.collectAsState()
    return foreground
}

private val isLinuxDesktop by lazy {
    System.getProperty("os.name", "").contains("linux", ignoreCase = true)
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
actual fun androidx.compose.ui.Modifier.mouseBackButton(onBack: () -> Unit): androidx.compose.ui.Modifier =
    this.onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, androidx.compose.ui.input.pointer.PointerEventPass.Initial) {
        // On Linux X11, OpenJDK shifts buttons > 5 down by 2: horizontal scroll left (X11 button 6, e.g. touchpad drift)
        // arrives as AWT button 4 (PointerButton.Back), while the physical mouse Back button (X11 button 8) is AWT button 6 (index 5).
        val backButton = if (isLinuxDesktop) androidx.compose.ui.input.pointer.PointerButton(5) else androidx.compose.ui.input.pointer.PointerButton.Back
        if (it.button == backButton) onBack()
    }

private val thumbDir by lazy { File(System.getProperty("user.home"), ".paperplay/thumbs").apply { mkdirs() } }

/** Where the decoded-and-shrunk copy of a local file lives; changes with the file's time and size, so edits are never stale. */
private fun thumbFile(path: String, maxSide: Int): File? {
    val f = File(path.removePrefix("file://"))
    if (!f.isFile) return null
    val sha = java.security.MessageDigest.getInstance("SHA-1").digest("${f.absolutePath}|${f.lastModified()}|${f.length()}|$maxSide".toByteArray())
    return File(thumbDir, sha.joinToString("") { "%02x".format(it) } + if (f.extension.equals("png", true)) ".png" else ".jpg")
}

/** Shrinks to [maxSide] with mipmapped filtering so small covers do not shimmer; returns the image itself when already small. */
private fun shrink(img: org.jetbrains.skia.Image, maxSide: Int): org.jetbrains.skia.Image {
    val big = maxOf(img.width, img.height)
    if (big <= maxSide) return img
    val k = maxSide.toFloat() / big
    val w = maxOf(1, (img.width * k).toInt()); val h = maxOf(1, (img.height * k).toInt())
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
    surface.canvas.drawImageRect(
        img, org.jetbrains.skia.Rect.makeWH(img.width.toFloat(), img.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
        org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.LINEAR), null, true,
    )
    return surface.makeImageSnapshot()
}

/**
 * Decodes at no more than [maxSide] pixels. Full-size covers were being decoded for every 56dp thumbnail, which filled memory and
 * kept the UI thread busy; shrunk copies of local files are kept on disk so a cold start reads small files instead.
 */
actual fun decodeImageFile(path: String, maxSide: Int): ImageBitmap? = runCatching {
    val remote = path.startsWith("http://") || path.startsWith("https://")
    val thumb = if (remote) null else thumbFile(path, maxSide)
    if (thumb != null && thumb.length() > 0) {
        runCatching {
            val thumbData = org.jetbrains.skia.Data.makeFromFileName(thumb.absolutePath)
            org.jetbrains.skia.Image.makeFromEncoded(thumbData.bytes).toComposeImageBitmap()
        }.getOrNull()?.let { return@runCatching it }
    }
    val data = if (remote) {
        val conn = (URI.create(path).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "PaperPlay/0.1.0")
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val bytes = conn.inputStream.use { it.readBytes() }
            org.jetbrains.skia.Data.makeFromBytes(bytes)
        } finally {
            conn.disconnect()
        }
    } else {
        val local = File(path.removePrefix("file://"))
        if (!local.isFile) return@runCatching null
        org.jetbrains.skia.Data.makeFromFileName(local.absolutePath)
    }
    val rawImg = org.jetbrains.skia.Image.makeFromEncoded(data.bytes)
    var small = rawImg
    try {
        small = shrink(rawImg, maxSide)
    } finally {
        if (rawImg !== small) {
            rawImg.close()
        }
    }
    if (thumb != null) runCatching {
        val png = thumb.extension == "png"
        small.encodeToData(if (png) org.jetbrains.skia.EncodedImageFormat.PNG else org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes?.let {
            val tmp = File(thumb.path + ".tmp")
            tmp.writeBytes(it)
            tmp.renameTo(thumb)
        }
    }
    small.toComposeImageBitmap()
}.getOrNull()

actual fun shrinkImageBytes(bytes: ByteArray, maxSide: Int): ByteArray? = runCatching {
    val img = org.jetbrains.skia.Image.makeFromEncoded(bytes)
    if (maxOf(img.width, img.height) <= maxSide) { img.close(); return@runCatching null }
    val small = shrink(img, maxSide)
    try { small.encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 90)?.bytes } finally { if (small !== img) small.close(); img.close() }
}.getOrNull()

actual fun localTimeLabel(epochMs: Long): String =
    java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm:ss"))

actual fun currentHour(): Int = java.time.LocalTime.now().hour

actual val isPhone: Boolean = false

actual fun nowMoment(): io.max.paperplay.core.recs.Moment {
    val t = java.time.LocalDateTime.now()
    return io.max.paperplay.core.recs.Moment(System.currentTimeMillis(), t.year, t.monthValue, t.dayOfMonth, t.hour)
}
