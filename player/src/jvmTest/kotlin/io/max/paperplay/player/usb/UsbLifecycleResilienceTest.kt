package io.max.paperplay.player.usb

import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.PrefKeyValueStore
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbLifecycleResilienceTest {
    private class FakeTimer : PauseTimerScheduler {
        var lastDelay: Long = -1L
        var pending: (() -> Unit)? = null
        var cancelledCount = 0
        override fun schedule(delayMs: Long, action: () -> Unit): PauseTimerScheduler.Handle {
            lastDelay = delayMs
            pending = action
            return object : PauseTimerScheduler.Handle {
                override fun cancel() {
                    if (pending != null) cancelledCount++
                    pending = null
                }
            }
        }
        fun fire() { pending?.let { val a = it; pending = null; a() } }
    }

    private fun newPrefs() = object : PrefKeyValueStore {
        val m = mutableMapOf<String, String>()
        override fun get(key: String) = m[key]
        override fun set(key: String, value: String) { m[key] = value }
    }

    @Test
    fun `pause keeps claim up to 25s, resume within timeout fades in cleanly, and timeout or screen-off releases`() {
        val calls = mutableListOf<String>()
        val timer = FakeTimer()
        val ops = object : UsbSystemOps {
            override fun loadNativeLibrary() { calls.add("load") }
            override fun openUsbDevice(deviceId: Int): Boolean { calls.add("open:$deviceId"); return true }
            override fun requestUsbPermission(deviceId: Int) { calls.add("perm:$deviceId") }
            override fun pauseGraceNative(handle: Long): Int { calls.add("pauseGrace:$handle"); return 0 }
            override fun resumeGraceNative(handle: Long): Int { calls.add("resumeGrace:$handle"); return 0 }
            override fun releaseClaimAndReattach(handle: Long) { calls.add("release:$handle") }
        }
        val coord = UnifiedUsbCoordinator(newPrefs(), ops, timer, BitPerfectMode.ON)
        coord.onNativeSessionOpened(handle = 55L, deviceId = 1, deviceName = "DAC")
        coord.onPlaybackStarted()

        // Pause halts streaming (pauseGrace) and arms 25s timer while keeping claim
        coord.onPlaybackPaused()
        assertEquals(25_000L, timer.lastDelay)
        assertTrue(coord.hasActiveClaim)
        assertFalse(coord.isStreamingActive)
        assertFalse(calls.any { it.startsWith("release:") })

        // Resume within 25s cancels timer and resumes without re-open
        assertTrue(coord.onPlaybackResumed())
        assertTrue(coord.isStreamingActive)
        assertTrue(calls.contains("resumeGrace:55"))
        assertFalse(calls.any { it.startsWith("release:") })

        // Pause again and expire 25s timeout -> full release + kernel reattach
        coord.onPlaybackPaused()
        timer.fire()
        assertFalse(coord.hasActiveClaim)
        assertTrue(calls.contains("release:55"))

        // Re-open, pause, and screen-off while paused -> immediate release!
        calls.clear()
        coord.onNativeSessionOpened(handle = 77L, deviceId = 1, deviceName = "DAC")
        coord.onPlaybackStarted()
        coord.onPlaybackPaused()
        coord.onScreenOff()
        assertFalse(coord.hasActiveClaim)
        assertTrue(calls.contains("release:77"))
    }

    @Test
    fun `hot-unplug and Bluetooth route change release claim cleanly`() {
        val released = mutableListOf<Long>()
        val timer = FakeTimer()
        val ops = object : UsbSystemOps {
            override fun loadNativeLibrary() {}
            override fun openUsbDevice(deviceId: Int) = true
            override fun requestUsbPermission(deviceId: Int) {}
            override fun pauseGraceNative(handle: Long) = 0
            override fun resumeGraceNative(handle: Long) = 0
            override fun releaseClaimAndReattach(handle: Long) { released.add(handle) }
        }
        val coord = UnifiedUsbCoordinator(newPrefs(), ops, timer, BitPerfectMode.ON)
        coord.onNativeSessionOpened(11L, 1, "DAC")
        coord.onPlaybackStarted()
        coord.onRouteChanged(nonUsbRouteActive = true)
        assertFalse(coord.hasActiveClaim)
        assertEquals(listOf(11L), released)

        coord.onNativeSessionOpened(22L, 2, "DAC")
        coord.onPlaybackStarted()
        coord.onHotUnplug(2)
        assertFalse(coord.hasActiveClaim)
        assertEquals(listOf(11L, 22L), released)
    }

    @Test
    fun `UnderrunWatch triggers in-session reconfigure and ReconfigureEscalator falls back on persistent underruns`() {
        var now = 0L
        var underruns = 0L
        var reconfCount = 0
        var fallbackReason: String? = null
        val api = object : CoarseUsbSessionApi {
            override val available = true
            override fun open(fd: Int, ci: Int, si: Int, u2: Boolean, csid: Int, hwv: Boolean, fu: Int) = 1L
            override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int): Int {
                reconfCount++; return 0
            }
            override fun applyVolume(h: Long, fu: Int, ci: Int, ch: Int, v: Int, m: Boolean) = 0
            override fun start(h: Long) = 0
            override fun pauseGrace(h: Long) = 0
            override fun stop(h: Long) = 0
            override fun writePcm(h: Long, buf: ByteBuffer, off: Int, len: Int) = len
            override fun setMuteRamp(h: Long, m: Boolean) = 0
            override fun flush(h: Long) = 0
            override fun getStats(h: Long, outStats: LongArray): Int {
                outStats[1] = underruns; return 0
            }
            override fun close(h: Long) {}
        }
        val ctrl = UsbCoarseSinkController(api, { now }, onFallback = { fallbackReason = it })
        assertTrue(ctrl.openAndConfigure(1, 0, 1, 1, 1, true, 9, false, 0, 48000, 24, 2, 4, true, false, 0, 0, false))
        reconfCount = 0 // discount initial open reconfigure

        val buf = ByteBuffer.allocateDirect(64)
        // 3 underruns within 1s trigger 1st in-session reconfigure
        repeat(3) {
            underruns++
            now += 100
            buf.clear()
            assertTrue(ctrl.writePcm(buf, 24, false))
        }
        assertEquals(1, reconfCount)
        assertFalse(ctrl.isFallback)

        // Post-seek underrun within 500ms triggers 2nd reconfigure -> escalates to fallback ("Persistent USB underruns")!
        now += 1_000
        ctrl.onSeekOrFlush()
        now += 100
        underruns++
        buf.clear()
        ctrl.writePcm(buf, 24, false)
        assertTrue(ctrl.isFallback)
        assertTrue(fallbackReason?.contains("Persistent USB underruns") == true)
    }
}
