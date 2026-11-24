package io.max.paperplay.compose

import android.app.Application

/** Starts the crash record before anything else runs, so an error in the playback service or at start-up is caught too. */
class PaperPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        io.max.paperplay.core.eq.AutoEqAndroidContext.init(this)
        io.max.paperplay.player.PlayLog.nowMs = { System.currentTimeMillis() }
        AppEvents.start(this)
        CrashLog.install(this)
        CrashLog.recordPreviousExits(this)
        CrashLog.startLogcat(this)
        CrashLog.startDiagnostics(this)
        io.max.paperplay.core.app.MemoryProbe.sample = {
            val rt = Runtime.getRuntime()
            val mb = 1024.0 * 1024.0
            fun f(x: Long) = "%.1f".format(x / mb)
            val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            "Java heap: ${f(rt.totalMemory() - rt.freeMemory())}/${f(rt.totalMemory())} MB (max ${f(rt.maxMemory())}) | Native heap: ${f(android.os.Debug.getNativeHeapAllocatedSize())} MB | " +
                "PSS: ${"%.1f".format(mi.totalPss / 1024.0)} MB (graphics ${mi.getMemoryStat("summary.graphics")?.toLongOrNull()?.let { "%.1f".format(it / 1024.0) } ?: "?"}) | Threads: ${Thread.activeCount()}"
        }
    }
}
