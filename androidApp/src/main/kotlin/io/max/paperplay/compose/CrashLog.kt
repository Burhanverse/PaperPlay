package io.max.paperplay.compose

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records every way the app can end badly, into the same file as the playback log (`playback-log.txt`) so a shared log
 * shows what happened right before:
 *  - an uncaught error on any thread, with the full stack, as soon as the process starts (before any screen);
 *  - on the next start, what Android says about how the last run ended: a crash, a crash in native code (TDLib, the
 *    decoders), an ANR, the system stopping it for memory, or a signal. Those leave no Java error, so this is the only trace.
 */
internal object CrashLog {
    private var installed = false
    private val stamp get() = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US)

    private fun logFile(c: Context) = File(c.filesDir, "playback-log.txt")

    private fun append(c: Context, tag: String, text: String) {
        runCatching { logFile(c).appendText("${stamp.format(Date())}  $tag  $text\n") }
    }

    fun install(c: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val text = "thread ${t.name}: " + e.stackTraceToString()
            append(c, "CRASH", text)
            // The newest crash first, a few kept, for the box on the Log page.
            runCatching {
                val f = File(c.filesDir, "crash.txt")
                val old = if (f.exists()) f.readText() else ""
                f.writeText((stamp.format(Date()) + "  " + text + "\n\n" + old).take(20_000))
            }
            previous?.uncaughtException(t, e)
        }
        append(c, "app", "process started (pid ${android.os.Process.myPid()})")
    }

    /**
     * Copies everything Android logs for this app (every tag and level: the app, TDLib, decoders, the system's own notes about
     * this process, and any crash or native tombstone summary) into `logcat.txt`, newest data last. The system keeps a short
     * history across restarts, so the first start after a crash also picks up the end of the run that crashed. Lines already
     * saved are skipped by their time. At 8 MB the file moves to `logcat-older.txt`.
     */
    fun startLogcat(c: Context) {
        Thread({
            runCatching {
                val out = File(c.filesDir, "logcat.txt")
                val older = File(c.filesDir, "logcat-older.txt")
                val tsFile = File(c.filesDir, "logcat-ts")
                var last = tsFile.takeIf { it.exists() }?.readText()?.trim() ?: ""
                val proc = ProcessBuilder("logcat", "-v", "threadtime").redirectErrorStream(true).start()
                val reader = proc.inputStream.bufferedReader()
                var writer = out.bufferedWriter(Charsets.UTF_8).also { it.close() }
                var size = out.length()
                var lastSaved = System.currentTimeMillis()
                writer = java.io.FileWriter(out, true).buffered()
                while (true) {
                    val line = reader.readLine() ?: break
                    // threadtime lines start "MM-dd HH:mm:ss.SSS"; older ones are already in the file.
                    val ts = if (line.length > 18 && line[2] == '-') line.substring(0, 18) else ""
                    if (ts.isNotEmpty() && ts <= last) continue
                    if (ts.isNotEmpty()) last = ts
                    writer.write(line); writer.write("\n")
                    size += line.length + 1
                    if (!reader.ready()) {
                        writer.flush()
                        if (System.currentTimeMillis() - lastSaved > 2000) { tsFile.writeText(last); lastSaved = System.currentTimeMillis() }
                    }
                    if (size > 8L * 1024 * 1024) {
                        writer.close()
                        older.delete(); out.renameTo(older)
                        writer = java.io.FileWriter(out, true).buffered()
                        size = 0
                    }
                }
            }
        }, "logcat-copy").apply { isDaemon = true; start() }
    }

    /** Writes how earlier runs ended, once each. Needs Android 11; older versions only have the Java crash above. */
    fun recordPreviousExits(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val seenFile = File(c.filesDir, "last-exit-ts")
            val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(c.packageName, 0, 10).filter { it.timestamp > seen }.sortedBy { it.timestamp }
            for (e in exits) {
                val reason = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "CRASH (Java error)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (a crash in native code)"
                    ApplicationExitInfo.REASON_ANR -> "ANR (not responding)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (stopped by the system)"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (swiped away)"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                    else -> "reason ${e.reason}"
                }
                val line = "previous run (pid ${e.pid}, ${e.processName}) ended at ${stamp.format(Date(e.timestamp))}: $reason, status ${e.status}, " +
                    "importance ${e.importance}, memory ${e.pss} KB, ${e.description ?: "no description"}"
                append(c, "EXIT", line)
                if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching {
                        e.traceInputStream?.use { s -> append(c, "EXIT", "trace:\n" + s.readBytes().decodeToString().take(30_000)) }
                    }
                }
                if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || e.reason == ApplicationExitInfo.REASON_CRASH) {
                    runCatching {
                        val f = File(c.filesDir, "crash.txt")
                        val old = if (f.exists()) f.readText() else ""
                        if (!old.contains(line)) f.writeText((line + "\n\n" + old).take(20_000))
                    }
                }
            }
            exits.maxOfOrNull { it.timestamp }?.let { seenFile.writeText(it.toString()) }
        }
    }
}
