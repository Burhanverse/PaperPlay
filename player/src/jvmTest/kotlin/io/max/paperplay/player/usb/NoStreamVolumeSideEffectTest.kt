package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.PrefKeyValueStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoStreamVolumeSideEffectTest {
    private fun findRepoFile(relPath: String): File =
        listOf(File(relPath), File("../$relPath")).first { it.exists() }

    @Test
    fun `unified manager and sink never invoke AudioManager setStreamVolume`() {
        val managerSrc = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UnifiedUsbDacManager.kt").readText()
        val sinkSrc = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UnifiedUsbAudioSink.kt").readText()
        assertFalse(managerSrc.contains("setStreamVolume("), "UnifiedUsbDacManager must never mutate STREAM_MUSIC automatically")
        assertFalse(sinkSrc.contains("setStreamVolume("), "UnifiedUsbAudioSink must never mutate STREAM_MUSIC")
        val legacyReleaseBody = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt")
            .readText()
            .substringAfter("fun onUsbReleased(")
            .substringBefore("fun restoreHardwareVolumeOnRelease")
        assertFalse(legacyReleaseBody.contains("setStreamVolume("), "onUsbReleased must never touch setStreamVolume")
    }

    @Test
    fun `non-user auto volume sync is strictly capped to +1 step while explicit user jumps are honored`() {
        assertEquals(5, clampAutoVolumeStep(currentIndex = 4, targetIndex = 12, isUser = false))
        assertEquals(2, clampAutoVolumeStep(currentIndex = 4, targetIndex = 2, isUser = false))
        assertEquals(12, clampAutoVolumeStep(currentIndex = 4, targetIndex = 12, isUser = true))

        val prefs = object : PrefKeyValueStore {
            val m = mutableMapOf<String, String>()
            override fun get(key: String) = m[key]
            override fun set(key: String, value: String) { m[key] = value }
        }
        val ops = object : UsbSystemOps {
            override fun loadNativeLibrary() {}
            override fun openUsbDevice(deviceId: Int) = true
            override fun requestUsbPermission(deviceId: Int) {}
            override fun pauseGraceNative(handle: Long) = 0
            override fun resumeGraceNative(handle: Long) = 0
            override fun releaseClaimAndReattach(handle: Long) {}
        }
        val sched = object : PauseTimerScheduler {
            override fun schedule(delayMs: Long, action: () -> Unit) = object : PauseTimerScheduler.Handle { override fun cancel() {} }
        }
        val coord = UnifiedUsbCoordinator(prefs, ops, sched, BitPerfectMode.ON)
        assertEquals(4, coord.computeSafeSyncedIndex(4, 15, isUser = false))
        // Auto jump from 4 -> 10 must clamp to 5 (+1 max step)
        assertEquals(5, coord.computeSafeSyncedIndex(10, 15, isUser = false))
        // Explicit user step allows immediate adjustment
        assertEquals(3, coord.computeSafeSyncedIndex(3, 15, isUser = true))
    }
}
