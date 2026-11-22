package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.PrefKeyValueStore

/** Represents a USB DAC spotted via Android class/subclass metadata with zero openDevice(). */
data class DiscoveredUsbDacHint(
    val vidPid: String,
    val deviceName: String,
)

/**
 * Tracks one-time class-based discovery hints when Bit-perfect is OFF.
 * Filters strictly to USB_CLASS_AUDIO (1) + AudioStreaming subclass (2) and records shown IDs by "vid:pid".
 */
class UsbDiscoveryHintTracker(private val store: PrefKeyValueStore) {
    companion object {
        const val KEY_SEEN_DEVICES = "bit_perfect_hint_seen_devices"
        const val USB_CLASS_AUDIO = 1
        const val USB_SUBCLASS_AUDIOSTREAMING = 2

        fun formatVidPid(vendorId: Int, productId: Int): String =
            "${(vendorId and 0xFFFF).toString(16).padStart(4, '0')}:${(productId and 0xFFFF).toString(16).padStart(4, '0')}"

        fun hasAudioStreamingInterface(interfaceClassSubclassPairs: List<Pair<Int, Int>>): Boolean =
            interfaceClassSubclassPairs.any { (cls, sub) -> cls == USB_CLASS_AUDIO && sub == USB_SUBCLASS_AUDIOSTREAMING }
    }

    private fun readSeen(): MutableSet<String> =
        store.get(KEY_SEEN_DEVICES)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableSet()

    fun evaluateHint(
        mode: BitPerfectMode,
        vendorId: Int,
        productId: Int,
        deviceName: String?,
        interfaceClassSubclassPairs: List<Pair<Int, Int>>,
    ): DiscoveredUsbDacHint? {
        if (mode != BitPerfectMode.OFF) return null
        if (!hasAudioStreamingInterface(interfaceClassSubclassPairs)) return null
        val key = formatVidPid(vendorId, productId)
        val seen = readSeen()
        if (key in seen) return null
        return DiscoveredUsbDacHint(
            vidPid = key,
            deviceName = deviceName?.takeIf { it.isNotBlank() } ?: "USB DAC ($key)",
        )
    }

    fun markHintShown(vidPid: String) {
        val seen = readSeen()
        if (seen.add(vidPid)) {
            store.set(KEY_SEEN_DEVICES, seen.sorted().joinToString(","))
        }
    }
}
