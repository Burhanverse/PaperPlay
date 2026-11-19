package io.max.paperplay.core.app

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

actual object CoverImages {
    actual fun shrink(bytes: ByteArray, maxEdge: Int, quality: Int): ByteArray? = runCatching {
        val src = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return null
        val scale = maxEdge.toDouble() / longest
        val w = maxOf(1, (src.width * scale).toInt()); val h = maxOf(1, (src.height * scale).toInt())
        // JPEG has no alpha: draw onto RGB (a PNG cover with transparency would otherwise fail to write).
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
        val buf = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(buf).use { ios -> writer.output = ios; writer.write(null, IIOImage(out, null, null), param) }
        writer.dispose()
        buf.toByteArray()
    }.getOrNull()

    actual fun readFile(path: String): ByteArray? = runCatching { File(path).readBytes() }.getOrNull()

    actual fun listFiles(dir: String): List<Pair<String, Long>> =
        File(dir).listFiles().orEmpty().filter { it.isFile }.map { it.path.replace(java.io.File.separatorChar, '/') to it.lastModified() }
}
