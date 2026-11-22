package io.max.paperplay.player.usb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbVolumeForwardingPlayerTest {
    private fun findRepoFile(relPath: String): File =
        listOf(File(relPath), File("../$relPath")).first { it.exists() }

    @Test
    fun `forwarding player exposes PLAYBACK_TYPE_LOCAL, delegates 1-1 when OFF, and tracks custom listeners`() {
        val src = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbVolumeForwardingPlayer.kt").readText()
        assertTrue(src.contains("DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)"))
        assertFalse(src.contains("PLAYBACK_TYPE_REMOTE"))
        assertTrue(src.contains("if (!isUsbMode()) return super.getDeviceInfo()"))
        assertTrue(src.contains("if (!isUsbMode()) return super.getDeviceVolume()"))
        assertTrue(src.contains("override fun addListener(listener: Player.Listener)"))
        assertTrue(src.contains("override fun removeListener(listener: Player.Listener)"))
        assertTrue(src.contains("l.onDeviceVolumeChanged(vol, muted)"))
        assertTrue(src.contains("l.onDeviceInfoChanged(info)"))
    }
}
