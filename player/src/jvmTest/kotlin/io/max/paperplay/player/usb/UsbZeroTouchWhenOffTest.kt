package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.PrefKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbZeroTouchWhenOffTest {
    private class MapPrefs : PrefKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `when OFF, scan never opens device, never requests permission, never loads native library, and shows hint once per vid pid`() {
        var opens = 0
        var perms = 0
        var nativeLoads = 0

        val ops = object : UsbSystemOps {
            override fun loadNativeLibrary() { nativeLoads++ }
            override fun openUsbDevice(deviceId: Int): Boolean { opens++; return true }
            override fun requestUsbPermission(deviceId: Int) { perms++ }
            override fun pauseGraceNative(handle: Long): Int = 0
            override fun resumeGraceNative(handle: Long): Int = 0
            override fun releaseClaimAndReattach(handle: Long) {}
        }
        val scheduler = object : PauseTimerScheduler {
            override fun schedule(delayMs: Long, action: () -> Unit): PauseTimerScheduler.Handle =
                object : PauseTimerScheduler.Handle { override fun cancel() {} }
        }

        val coord = UnifiedUsbCoordinator(MapPrefs(), ops, scheduler, BitPerfectMode.OFF)
        val dac = UsbDeviceMeta(
            deviceId = 7,
            vendorId = 0x3302,
            productId = 0x3365,
            productName = "CX31993",
            hasPermission = false,
            interfaceClassSubclassPairs = listOf(1 to 1, 1 to 2, 3 to 0),
        )
        // Non-audio HID device must not trigger hint
        val hid = UsbDeviceMeta(9, 0x1234, 0x5678, "Mouse", false, listOf(3 to 1))

        coord.scanDevices(listOf(hid))
        assertNull(coord.pendingHint.value)

        coord.scanDevices(listOf(dac))
        assertEquals(0, opens)
        assertEquals(0, perms)
        assertEquals(0, nativeLoads)
        assertNotNull(coord.pendingHint.value)
        assertEquals("3302:3365", coord.pendingHint.value?.vidPid)

        coord.dismissHint("3302:3365")
        assertNull(coord.pendingHint.value)

        // Subsequent scan for the same vid:pid does NOT re-show dismissed hint
        coord.scanDevices(listOf(dac))
        assertNull(coord.pendingHint.value)
        assertEquals(0, opens)
        assertEquals(0, perms)
        assertEquals(0, nativeLoads)

        // Another DAC with a different vid:pid DOES show its one-time hint
        val dac2 = dac.copy(deviceId = 8, vendorId = 0x1234, productId = 0xABCD, productName = "Audiocular D07")
        coord.scanDevices(listOf(dac2))
        assertTrue(coord.pendingHint.value?.vidPid == "1234:abcd")
    }
}
