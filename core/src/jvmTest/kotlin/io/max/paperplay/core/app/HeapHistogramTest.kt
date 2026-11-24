package io.max.paperplay.core.app

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeapHistogramTest {
    class Marker(@Suppress("unused") val a: Long)

    @Test
    fun countsTheObjectsInARealSnapshot() {
        val keep = List(12_345) { Marker(it.toLong()) }
        val dir = java.nio.file.Files.createTempDirectory("heap").toFile()
        val f = File(dir, "a.hprof")
        ManagementFactory.newPlatformMXBeanProxy(
            ManagementFactory.getPlatformMBeanServer(), "com.sun.management:type=HotSpotDiagnostic", com.sun.management.HotSpotDiagnosticMXBean::class.java,
        ).dumpHeap(f.path, true)
        val text = HeapHistogram.of(f, 100_000)
        assertTrue(text.lines().any { it.endsWith("  12345  io.max.paperplay.core.app.HeapHistogramTest\$Marker") }, text.take(500))
        assertEquals(12_345, keep.size)
        // A file cut short (the process died while writing) still gives what it held.
        java.io.RandomAccessFile(f, "rw").use { it.setLength(it.length() / 2) }
        assertTrue(HeapHistogram.of(f).startsWith("heap "))
        dir.deleteRecursively()
    }
}
