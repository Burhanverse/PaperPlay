package io.max.paperplay.player.usb

import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import java.util.concurrent.ConcurrentHashMap

/**
 * Called via JNI from the native `UsbIsoAudio` thread to apply Android's official audio
 * thread priority (`Process.setThreadPriority(tid, THREAD_PRIORITY_URGENT_AUDIO)`) and
 * optionally manage an ADPF [PerformanceHintManager.Session] (API 31+, behind flag).
 */
internal object UsbThreadHelper {
    private const val TARGET_WORK_DURATION_NS = 4_000_000L // 4 ms URB period
    private val adpfSessions = ConcurrentHashMap<Int, Any>()

    /**
     * Returns bitmask:
     * - bit 0 (1): `Process.setThreadPriority(tid, THREAD_PRIORITY_URGENT_AUDIO)` succeeded
     * - bit 1 (2): ADPF `PerformanceHintManager.Session` created and active
     */
    @JvmStatic
    fun onNativeAudioThreadStarted(tid: Int): Int {
        var bits = 0
        val prioOk = runCatching {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_AUDIO)
            true
        }.onFailure {
            PlayLog.log("usb", "UsbThreadHelper: setThreadPriority(tid=$tid, URGENT_AUDIO) failed: ${it.message}")
        }.getOrDefault(false)
        if (prioOk) {
            bits = bits or 1
        }

        var adpfCreated = false
        if (PlaybackCore.devUsbAdpfEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ctx = PlaybackCore.appContext
            if (ctx != null) {
                adpfCreated = runCatching {
                    val phm = ctx.getSystemService(PerformanceHintManager::class.java)
                    val session = phm?.createHintSession(intArrayOf(tid), TARGET_WORK_DURATION_NS)
                    if (session != null) {
                        adpfSessions.put(tid, session)?.let { old ->
                            runCatching { (old as PerformanceHintManager.Session).close() }
                        }
                        true
                    } else {
                        false
                    }
                }.onFailure {
                    PlayLog.log("usb", "UsbThreadHelper: ADPF createHintSession(tid=$tid) failed: ${it.message}")
                }.getOrDefault(false)
            }
        }
        if (adpfCreated) {
            bits = bits or 2
        }

        val actualPrio = runCatching { Process.getThreadPriority(tid) }.getOrDefault(999)
        PlayLog.log(
            "usb",
            "UsbThreadHelper: tid=$tid prioOk=$prioOk actualPrio=$actualPrio adpfFlag=${PlaybackCore.devUsbAdpfEnabled} adpfActive=$adpfCreated"
        )
        UsbRingLog.record(
            "UsbThreadHelper",
            "tid=$tid prioOk=$prioOk actualPrio=$actualPrio adpfFlag=${PlaybackCore.devUsbAdpfEnabled} adpfActive=$adpfCreated",
            UsbRingLog.CODE_GENERIC,
        )
        return bits
    }

    @JvmStatic
    fun reportNativeWorkDuration(tid: Int, actualDurationNs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val session = adpfSessions[tid] ?: return
        runCatching {
            (session as PerformanceHintManager.Session).reportActualWorkDuration(actualDurationNs.coerceAtLeast(1L))
        }
    }

    @JvmStatic
    fun onNativeAudioThreadStopped(tid: Int) {
        val removed = adpfSessions.remove(tid) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { (removed as PerformanceHintManager.Session).close() }
        }
        PlayLog.log("usb", "UsbThreadHelper: closed ADPF session for tid=$tid")
    }
}
