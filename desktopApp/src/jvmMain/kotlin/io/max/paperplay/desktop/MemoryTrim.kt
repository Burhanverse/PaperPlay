package io.max.paperplay.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Gives idle memory back to Windows. Called when the window has been hidden or minimized for a while: the heap is
 * compacted, then the pages the process is not using are released and come back on demand. Nothing is lost.
 */
object MemoryTrim {
    private interface Kernel32 : Library {
        fun GetCurrentProcess(): Pointer
        fun SetProcessWorkingSetSize(process: Pointer, min: Long, max: Long): Boolean
    }

    private val k32: Kernel32? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) null
        else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    fun trim() {
        System.gc()
        runCatching { k32?.let { it.SetProcessWorkingSetSize(it.GetCurrentProcess(), -1L, -1L) } }
    }
}
