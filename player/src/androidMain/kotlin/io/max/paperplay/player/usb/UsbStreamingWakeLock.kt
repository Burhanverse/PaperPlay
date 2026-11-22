package io.max.paperplay.player.usb

import android.content.Context
import android.os.PowerManager
import io.max.paperplay.player.PlayLog

/**
 * Dedicated ref-counted `PowerManager.PARTIAL_WAKE_LOCK` tied to active USB exclusive streaming.
 * Released strictly on pause timeout, stop, close, route switch, hot-unplug, or service destroy.
 */
internal object UsbStreamingWakeLock {
    private const val TAG = "PaperPlay:UsbIsoAudio"
    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    private val controller = RefCountedWakeLockController(
        onAcquire = { acquirePlatform() },
        onRelease = { releasePlatform() },
    )

    val isHeld: Boolean
        get() = controller.isHeld

    val refCount: Int
        get() = controller.refCount

    fun init(context: Context) {
        synchronized(lock) {
            if (wakeLock != null) return
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                setReferenceCounted(false)
            }
        }
    }

    fun acquire(owner: String) {
        val transitioned = controller.acquire(owner)
        if (transitioned) {
            UsbPowerTelemetry.recordCustom("USB_WAKELOCK_ACQUIRED", "owner=$owner refCount=$refCount")
        }
    }

    fun release(owner: String) {
        val transitioned = controller.release(owner)
        if (transitioned) {
            UsbPowerTelemetry.recordCustom("USB_WAKELOCK_RELEASED", "owner=$owner refCount=$refCount")
        }
    }

    fun releaseAll(reason: String = "cleanup") {
        val transitioned = controller.releaseAll()
        if (transitioned) {
            UsbPowerTelemetry.recordCustom("USB_WAKELOCK_RELEASE_ALL", "reason=$reason")
        }
    }

    private fun acquirePlatform() {
        synchronized(lock) {
            val wl = wakeLock ?: return
            runCatching {
                if (!wl.isHeld) {
                    wl.acquire()
                    PlayLog.log("usb", "UsbStreamingWakeLock: acquired $TAG")
                }
            }.onFailure {
                PlayLog.log("usb", "UsbStreamingWakeLock: acquire failed: ${it.message}")
            }
        }
    }

    private fun releasePlatform() {
        synchronized(lock) {
            val wl = wakeLock ?: return
            runCatching {
                if (wl.isHeld) {
                    wl.release()
                    PlayLog.log("usb", "UsbStreamingWakeLock: released $TAG")
                }
            }.onFailure {
                PlayLog.log("usb", "UsbStreamingWakeLock: release failed: ${it.message}")
            }
        }
    }
}
