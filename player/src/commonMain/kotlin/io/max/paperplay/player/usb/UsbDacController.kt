package io.max.paperplay.player.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val NoUsbDiscoveryHint = MutableStateFlow<DiscoveredUsbDacHint?>(null)

/**
 * Android-only USB DAC & diagnostic capability contract exposed via [io.max.paperplay.player.PlayerEngine.dacController].
 * Desktop engines return null, keeping USB-specific symbols off the cross-platform PlayerEngine contract.
 */
interface UsbDacController {
    val usbExclusiveStatus: StateFlow<UsbExclusiveStatus?>
    val pendingDiscoveryHint: StateFlow<DiscoveredUsbDacHint?> get() = NoUsbDiscoveryHint
    fun dismissDiscoveryHint(vidPid: String) {}
    fun setUsbExclusive(on: Boolean)
    fun setUseUnifiedBitPerfect(enabled: Boolean) {}
    fun getUseUnifiedBitPerfect(): Boolean = io.max.paperplay.player.USE_UNIFIED_BIT_PERFECT_DEFAULT
    fun setDevApi34Mixer(enabled: Boolean) {}
    fun setSinkChain(mode: String)
    fun getSinkChain(): String
    suspend fun runUsbProbe(): String
    suspend fun runUsbPlaybackTest(): String
    fun requestUsbPermission()
    suspend fun exportUsbDebugLog(): String
    fun triggerUsbPcmDump(enable: Boolean = true): String
    fun getUsbPcmDumpStatus(): String
    fun getUsbPcmDumpPath(): String?
    fun getLastUsbFallbackLogPath(): String?
}
