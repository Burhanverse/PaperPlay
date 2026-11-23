package io.max.paperplay.core.backup

/** Text written to [path] in 1 MB pieces, so a file of tens of MB is never held in memory whole. Call [close] at the end. */
class FileAppender(private val path: String) : Appendable {
    private val buf = StringBuilder()

    init { Fs.deleteRecursively(path) }

    private fun flushIfBig(): Appendable { if (buf.length > 1 shl 20) flush(); return this }
    private fun flush() { if (buf.isNotEmpty()) { Fs.append(path, buf.toString()); buf.setLength(0) } }

    override fun append(value: CharSequence?): Appendable { buf.append(value); return flushIfBig() }
    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable { buf.append(value, startIndex, endIndex); return flushIfBig() }
    override fun append(value: Char): Appendable { buf.append(value); return this }

    fun close() = flush()
}
