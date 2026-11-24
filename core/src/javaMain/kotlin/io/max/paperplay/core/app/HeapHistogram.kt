package io.max.paperplay.core.app

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/**
 * Sums up a Java heap snapshot (hprof, from a desktop JVM or Android) as text: how many objects of each class there are and how many
 * bytes they take. The file is read once as a stream, so a snapshot of hundreds of MB costs a few MB of memory. Only class names and
 * counts come out, never the contents of the heap, which hold login secrets.
 */
object HeapHistogram {
    private class Row(var count: Long = 0, var bytes: Long = 0)

    fun of(file: File, top: Int = 40): String {
        val strings = HashMap<Long, String>()
        val classNames = HashMap<Long, Long>()
        val rows = HashMap<Long, Row>()
        val prims = HashMap<Int, Row>()
        var total = 0L
        DataInputStream(file.inputStream().buffered(256 * 1024)).use { d ->
            while (d.readUnsignedByte() != 0) Unit // the format name
            val idSize = d.readInt()
            d.readLong()
            fun id(): Long = if (idSize == 4) d.readInt().toLong() and 0xffffffffL else d.readLong()
            fun u4(): Long = d.readInt().toLong() and 0xffffffffL
            fun skip(n: Long) {
                var left = n
                while (left > 0) {
                    val s = d.skip(left)
                    if (s > 0) left -= s else { d.readByte(); left-- }
                }
            }
            fun size(type: Int): Int = when (type) {
                2 -> idSize
                4, 8 -> 1
                5, 9 -> 2
                6, 10 -> 4
                7, 11 -> 8
                else -> throw IllegalStateException("unknown value type $type")
            }
            fun add(row: Row, bytes: Long) { row.count++; row.bytes += bytes; total += bytes }
            fun heap(length: Long) {
                var left = length
                while (left > 0) {
                    val tag = d.readUnsignedByte()
                    left--
                    when (tag) {
                        0xff, 0x05, 0x07, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x90 -> { skip(idSize.toLong()); left -= idSize }
                        0x01 -> { skip(2L * idSize); left -= 2L * idSize }
                        0x02, 0x03, 0x08, 0x8e -> { skip(idSize + 8L); left -= idSize + 8L }
                        0x04, 0x06 -> { skip(idSize + 4L); left -= idSize + 4L }
                        0xfe -> { skip(4L + idSize); left -= 4L + idSize } // Android: which heap follows
                        0x20 -> {
                            skip(7L * idSize + 8); left -= 7L * idSize + 8
                            val pool = d.readUnsignedShort(); left -= 2
                            repeat(pool) { d.readUnsignedShort(); val n = size(d.readUnsignedByte()); skip(n.toLong()); left -= 3L + n }
                            val statics = d.readUnsignedShort(); left -= 2
                            repeat(statics) { skip(idSize.toLong()); val n = size(d.readUnsignedByte()); skip(n.toLong()); left -= idSize + 1L + n }
                            val fields = d.readUnsignedShort(); left -= 2
                            skip(fields * (idSize + 1L)); left -= fields * (idSize + 1L)
                        }
                        0x21 -> {
                            skip(idSize + 4L)
                            val c = id()
                            val n = u4()
                            skip(n); left -= 2L * idSize + 8 + n
                            add(rows.getOrPut(c) { Row() }, n + 8)
                        }
                        0x22 -> {
                            skip(idSize + 4L)
                            val n = u4()
                            val c = id()
                            skip(n * idSize); left -= 2L * idSize + 8 + n * idSize
                            add(rows.getOrPut(c) { Row() }, n * idSize + 12)
                        }
                        0x23, 0xc3 -> {
                            skip(idSize + 4L)
                            val n = u4()
                            val t = d.readUnsignedByte()
                            val bytes = n * size(t)
                            if (tag == 0x23) { skip(bytes); left -= bytes } // 0xc3 has no contents
                            left -= idSize + 9L
                            add(prims.getOrPut(t) { Row() }, bytes + 12)
                        }
                        else -> throw IllegalStateException("unknown heap record $tag")
                    }
                }
            }
            try {
                while (true) {
                    val tag = d.readUnsignedByte()
                    d.readInt()
                    val length = u4()
                    when (tag) {
                        0x01 -> {
                            val key = id()
                            val b = ByteArray((length - idSize).toInt())
                            d.readFully(b)
                            strings[key] = b.decodeToString()
                        }
                        0x02 -> { d.readInt(); val c = id(); d.readInt(); classNames[c] = id() }
                        0x0c, 0x1c -> heap(length)
                        else -> skip(length)
                    }
                }
            } catch (_: EOFException) {
                // The end of the file, or of what was written before the process died.
            }
        }
        val named = HashMap<String, Row>()
        fun merge(name: String, r: Row) = named.getOrPut(name) { Row() }.let { it.count += r.count; it.bytes += r.bytes }
        for ((c, r) in rows) merge(classNames[c]?.let(strings::get)?.replace('/', '.') ?: "class $c", r)
        val primNames = mapOf(4 to "boolean[]", 5 to "char[]", 6 to "float[]", 7 to "double[]", 8 to "byte[]", 9 to "short[]", 10 to "int[]", 11 to "long[]")
        for ((t, r) in prims) merge(primNames[t] ?: "type $t[]", r)
        return buildString {
            append("heap ").append(total / 1024).append(" KB in ").append(named.values.sumOf { it.count }).append(" objects, biggest classes (KB, objects, class):\n")
            for ((n, r) in named.entries.sortedByDescending { it.value.bytes }.take(top)) append(r.bytes / 1024).append("  ").append(r.count).append("  ").append(n).append('\n')
        }
    }
}
