package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.PrefKeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple scheduler seam so production code uses Handler/Executor and tests run deterministically with 0 sleeps. */
interface PauseTimerScheduler {
    interface Handle {
        fun cancel()
    }
    fun schedule(delayMs: Long, action: () -> Unit): Handle
}

/** Platform USB/audio side-effect seam verified directly by ZeroTouch, NoStreamVolumeSideEffect, and Lifecycle tests. */
interface UsbSystemOps {
    fun loadNativeLibrary()
    fun openUsbDevice(deviceId: Int): Boolean
    fun requestUsbPermission(deviceId: Int)
    fun pauseGraceNative(handle: Long): Int
    fun resumeGraceNative(handle: Long): Int
    fun releaseClaimAndReattach(handle: Long)
}

/** Metadata-only representation of an attached USB device (no openDevice required). */
data class UsbDeviceMeta(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val hasPermission: Boolean,
    val interfaceClassSubclassPairs: List<Pair<Int, Int>>,
)

/**
 * Pure lifecycle & safety coordinator for unified Bit-perfect USB playback:
 * - Zero-touch class-only discovery when Bit-perfect is OFF (no openDevice, no permission prompt, no native load).
 * - One-time discovery hint keyed by "vid:pid".
 * - 25 s pause timeout preserving USB claim with 0 streaming threads; instant resume within 25 s;
 *   immediate release on timeout, screen-off while paused, mode off, route change, hot-unplug, or audio focus loss.
 * - Never calls AudioManager.setStreamVolume automatically; caps non-user volume sync <= +1 step.
 */
class UnifiedUsbCoordinator(
    private val prefs: PrefKeyValueStore,
    private val ops: UsbSystemOps,
    private val scheduler: PauseTimerScheduler,
    initialMode: BitPerfectMode = BitPerfectMode.OFF,
    val pauseGraceTimeoutMs: Long = DEFAULT_PAUSE_GRACE_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PAUSE_GRACE_TIMEOUT_MS = 25_000L
    }

    private val hintTracker = UsbDiscoveryHintTracker(prefs)

    private val _mode = MutableStateFlow(initialMode)
    val mode: StateFlow<BitPerfectMode> = _mode

    private val _status = MutableStateFlow<BitPerfectStatus>(
        if (initialMode == BitPerfectMode.ON) BitPerfectStatus.Standby() else BitPerfectStatus.Off
    )
    val status: StateFlow<BitPerfectStatus> = _status

    private val _pendingHint = MutableStateFlow<DiscoveredUsbDacHint?>(null)
    val pendingHint: StateFlow<DiscoveredUsbDacHint?> = _pendingHint

    @Volatile
    var activeHandle: Long = 0L
        private set
    @Volatile
    var hasActiveClaim: Boolean = false
        private set
    @Volatile
    var isPlaying: Boolean = false
        private set
    @Volatile
    var isStreamingActive: Boolean = false
        private set
    @Volatile
    var currentDeviceId: Int? = null
        private set
    var lastSyncedVolumeIndex: Int? = null
        private set
    var onStatusChanged: (() -> Unit)? = null

    private val claimLock = Any()
    private var pendingPauseTimer: PauseTimerScheduler.Handle? = null

    private fun setStatus(next: BitPerfectStatus) {
        _status.value = next
        onStatusChanged?.invoke()
    }

    fun dismissHint(vidPid: String) {
        hintTracker.markHintShown(vidPid)
        if (_pendingHint.value?.vidPid == vidPid) {
            _pendingHint.value = null
        }
    }

    fun scanDevices(devices: List<UsbDeviceMeta>) {
        if (devices.isEmpty()) {
            _pendingHint.value = null
            if (_mode.value == BitPerfectMode.ON) {
                releaseClaim("USB DAC disconnected")
                setStatus(BitPerfectStatus.Standby(reason = "Waiting for USB DAC"))
            }
            return
        }
        val audioDev = devices.firstOrNull {
            UsbDiscoveryHintTracker.hasAudioStreamingInterface(it.interfaceClassSubclassPairs)
        }
        if (_mode.value == BitPerfectMode.OFF) {
            // Class-based detection only: zero openDevice, zero permission prompt, zero native load!
            if (audioDev != null) {
                val hint = hintTracker.evaluateHint(
                    mode = BitPerfectMode.OFF,
                    vendorId = audioDev.vendorId,
                    productId = audioDev.productId,
                    deviceName = audioDev.productName,
                    interfaceClassSubclassPairs = audioDev.interfaceClassSubclassPairs,
                )
                if (hint != null) {
                    _pendingHint.value = hint
                }
            }
            return
        }

        if (audioDev == null) {
            setStatus(BitPerfectStatus.Standby(reason = "Waiting for USB DAC"))
            return
        }
        currentDeviceId = audioDev.deviceId
        if (!audioDev.hasPermission) {
            setStatus(
                BitPerfectStatus.PermissionRequired(
                    audioDev.productName ?: "USB DAC",
                    UsbDiscoveryHintTracker.formatVidPid(audioDev.vendorId, audioDev.productId),
                )
            )
            ops.requestUsbPermission(audioDev.deviceId)
            return
        }
        ops.loadNativeLibrary()
        if (ops.openUsbDevice(audioDev.deviceId)) {
            hasActiveClaim = true
            setStatus(BitPerfectStatus.Standby(audioDev.productName, "Ready to stream"))
        }
    }

    fun onNativeSessionOpened(handle: Long, deviceId: Int, deviceName: String?) {
        val claimed = synchronized(claimLock) {
            activeHandle = handle
            currentDeviceId = deviceId
            hasActiveClaim = handle != 0L
            hasActiveClaim && _mode.value == BitPerfectMode.ON
        }
        if (claimed) {
            setStatus(BitPerfectStatus.Standby(deviceName, "Ready to stream"))
        }
    }

    fun onNativeSessionClosed(handle: Long) {
        if (handle == 0L) return
        val wasActive = synchronized(claimLock) {
            if (activeHandle == handle) {
                cancelPauseTimer()
                activeHandle = 0L
                hasActiveClaim = false
                isStreamingActive = false
                lastSyncedVolumeIndex = null
                true
            } else {
                false
            }
        }
        if (wasActive) {
            if (_mode.value == BitPerfectMode.ON) {
                setStatus(BitPerfectStatus.Standby(reason = "Session closed"))
            } else {
                onStatusChanged?.invoke()
            }
        }
    }

    fun onModeChanged(newMode: BitPerfectMode) {
        _mode.value = newMode
        if (newMode == BitPerfectMode.OFF) {
            _pendingHint.value = null
            releaseClaim("Bit-perfect turned off")
            setStatus(BitPerfectStatus.Off)
        } else {
            _pendingHint.value?.let { hintTracker.markHintShown(it.vidPid) }
            _pendingHint.value = null
            setStatus(BitPerfectStatus.Standby(reason = "Waiting for USB DAC"))
        }
    }

    fun onStreamingActive(sampleRateHz: Int, bitDepth: Int, deviceName: String?) {
        isPlaying = true
        if (hasActiveClaim && _mode.value == BitPerfectMode.ON) {
            isStreamingActive = true
            setStatus(
                BitPerfectStatus.Active(
                    transportLabel = io.max.paperplay.player.TransportKind.USB_UAC_DIRECT.displayName,
                    sampleRateHz = sampleRateHz,
                    bitDepth = bitDepth,
                    deviceName = deviceName?.takeIf { it.isNotBlank() },
                )
            )
        }
    }

    fun onPlaybackStarted() {
        cancelPauseTimer()
        isPlaying = true
        if (activeHandle != 0L && hasActiveClaim) {
            isStreamingActive = true
        }
    }

    fun onPlaybackPaused() {
        isPlaying = false
        if (!hasActiveClaim || activeHandle == 0L) return
        ops.pauseGraceNative(activeHandle)
        isStreamingActive = false
        cancelPauseTimer()
        pendingPauseTimer = scheduler.schedule(pauseGraceTimeoutMs) {
            pendingPauseTimer = null
            if (!isPlaying && hasActiveClaim) {
                releaseClaim("Pause grace timeout expired")
            }
        }
    }

    fun onPlaybackResumed(): Boolean {
        cancelPauseTimer()
        isPlaying = true
        if (activeHandle != 0L && hasActiveClaim) {
            val rc = ops.resumeGraceNative(activeHandle)
            if (rc == 0) {
                isStreamingActive = true
                return true
            }
            releaseClaim("Resume failed (code=$rc)")
        }
        return false
    }

    fun onScreenOff() {
        if (!isPlaying && hasActiveClaim) {
            cancelPauseTimer()
            releaseClaim("Screen off while paused")
        }
    }

    fun onRouteChanged(nonUsbRouteActive: Boolean) {
        if (nonUsbRouteActive) {
            cancelPauseTimer()
            if (hasActiveClaim) {
                releaseClaim("Output switched away from USB DAC")
            }
            if (_mode.value == BitPerfectMode.ON) {
                setStatus(BitPerfectStatus.Bypassed("Non-USB output active"))
            }
        }
    }

    fun onSpeedBypass(speed: Float) {
        val hadClaim = synchronized(claimLock) {
            cancelPauseTimer()
            val h = activeHandle
            activeHandle = 0L
            hasActiveClaim = false
            isStreamingActive = false
            lastSyncedVolumeIndex = null
            h
        }
        if (hadClaim != 0L) {
            ops.releaseClaimAndReattach(hadClaim)
        }
        if (_mode.value == BitPerfectMode.ON) {
            setStatus(BitPerfectStatus.Bypassed("Speed ${speed}x"))
        } else {
            onStatusChanged?.invoke()
        }
    }

    fun onAudioFocusLost(permanent: Boolean) {
        if (permanent) {
            isPlaying = false
            cancelPauseTimer()
            releaseClaim("Audio focus lost")
        } else if (hasActiveClaim && isPlaying) {
            onPlaybackPaused()
        }
    }

    fun onHotUnplug(deviceId: Int) {
        if (currentDeviceId == null || currentDeviceId == deviceId) {
            isPlaying = false
            cancelPauseTimer()
            releaseClaim("USB DAC unplugged")
            currentDeviceId = null
            if (_mode.value == BitPerfectMode.ON) {
                setStatus(BitPerfectStatus.Standby(reason = "Waiting for USB DAC"))
            }
        }
    }

    /**
     * Hearing-safety guarded volume sync: never calls AudioManager.setStreamVolume,
     * and non-user automatic syncs can never step up by more than +1 index step.
     */
    fun computeSafeSyncedIndex(systemStreamIndex: Int, maxIndex: Int, isUser: Boolean): Int {
        val clamped = systemStreamIndex.coerceIn(0, maxIndex.coerceAtLeast(1))
        val applied = clampAutoVolumeStep(lastSyncedVolumeIndex, clamped, isUser)
        lastSyncedVolumeIndex = applied
        return applied
    }

    fun releaseClaim(reason: String) {
        val h = synchronized(claimLock) {
            cancelPauseTimer()
            val prev = activeHandle
            activeHandle = 0L
            hasActiveClaim = false
            isStreamingActive = false
            lastSyncedVolumeIndex = null
            prev
        }
        if (h != 0L) {
            ops.releaseClaimAndReattach(h)
        }
        if (_mode.value == BitPerfectMode.ON) {
            setStatus(BitPerfectStatus.Standby(reason = reason))
        } else {
            onStatusChanged?.invoke()
        }
    }

    private fun cancelPauseTimer() {
        pendingPauseTimer?.cancel()
        pendingPauseTimer = null
    }
}
