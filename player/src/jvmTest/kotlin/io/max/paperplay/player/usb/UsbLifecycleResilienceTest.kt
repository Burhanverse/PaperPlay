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
    fun `pure underruns escalate cushion and resync without reconfigure or fallback, while real URB faults reconfigure and fall back`() {
        var now = 0L
        var underruns = 0L
        var urbErrors = 0L
        var reportedRate = 48000L
        var reconfCount = 0
        var escalateCount = 0
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
            override fun escalateCushionAndResync(h: Long): Int {
                escalateCount++; return 0
            }
            override fun getStats(h: Long, outStats: LongArray): Int {
                outStats[1] = underruns
                outStats[2] = urbErrors
                outStats[4] = 1048576L
                outStats[5] = 1L
                outStats[6] = reportedRate
                return 0
            }
            override fun close(h: Long) {}
        }
        val ctrl = UsbCoarseSinkController(api, { now }, onFallback = { fallbackReason = it })
        assertTrue(ctrl.openAndConfigure(1, 0, 1, 1, 1, true, 9, false, 0, 48000, 24, 2, 4, true, false, 0, 0, false))
        reconfCount = 0 // discount initial open reconfigure

        val buf = ByteBuffer.allocateDirect(64)
        // Pure ring underruns escalate cushion & resync, with ZERO reconfigures and ZERO fallbacks
        repeat(6) {
            underruns++
            now += 100
            buf.clear()
            assertTrue(ctrl.writePcm(buf, 24, false))
        }
        assertTrue(escalateCount >= 1)
        assertEquals(0, reconfCount)
        assertFalse(ctrl.isFallback)

        // 1st real URB error triggers in-session reconfigure (not fallback yet)
        now += 500
        urbErrors++
        buf.clear()
        assertTrue(ctrl.writePcm(buf, 24, false))
        assertEquals(1, reconfCount)
        assertFalse(ctrl.isFallback)

        // 2nd real fault (clock mismatch) within 15s window escalates to fallback
        now += 500
        reportedRate = 44100L
        buf.clear()
        ctrl.writePcm(buf, 24, false)
        assertTrue(ctrl.isFallback)
        assertTrue(fallbackReason?.contains("Persistent USB faults") == true)
    }

    @Test
    fun `streaming wakelock is acquired on play, retained across pause grace, and released on pause timeout or close`() {
        val timer = FakeTimer()
        var platformAcquires = 0
        var platformReleases = 0
        val wl = RefCountedWakeLockController(
            onAcquire = { platformAcquires++ },
            onRelease = { platformReleases++ },
        )
        var keepAliveActive = false
        val ops = object : UsbSystemOps {
            override fun loadNativeLibrary() {}
            override fun openUsbDevice(deviceId: Int) = true
            override fun requestUsbPermission(deviceId: Int) {}
            override fun pauseGraceNative(handle: Long) = 0
            override fun resumeGraceNative(handle: Long) = 0
            override fun releaseClaimAndReattach(handle: Long) {}
            override fun acquireStreamingWakeLock(owner: String) { wl.acquire(owner) }
            override fun releaseStreamingWakeLock(owner: String) { wl.release(owner) }
            override fun setSilentKeepAliveActive(active: Boolean) { keepAliveActive = active }
        }
        val coord = UnifiedUsbCoordinator(newPrefs(), ops, timer, BitPerfectMode.ON)
        coord.onNativeSessionOpened(handle = 42L, deviceId = 1, deviceName = "DAC")
        coord.onPlaybackStarted()
        assertTrue(wl.isHeld)
        assertEquals(1, wl.refCount)
        assertEquals(1, platformAcquires)
        assertTrue(keepAliveActive)

        // Idempotent double acquire does not double-acquire platform wakelock
        coord.onStreamingActive(96000, 24, "DAC")
        assertEquals(1, wl.refCount)
        assertEquals(1, platformAcquires)

        // Pause stops keep-alive immediately but holds wakelock during 25s pause grace timer
        coord.onPlaybackPaused()
        assertFalse(keepAliveActive)
        assertTrue(wl.isHeld)

        // Pause timeout fires -> releases claim AND wakelock
        timer.fire()
        assertFalse(wl.isHeld)
        assertEquals(0, wl.refCount)
        assertEquals(1, platformReleases)

        // Verify RefCountedWakeLockController multi-owner ref counting
        wl.acquire("a")
        wl.acquire("b")
        assertEquals(2, wl.refCount)
        assertEquals(2, platformAcquires)
        wl.release("a")
        assertTrue(wl.isHeld)
        assertEquals(1, platformReleases)
        wl.release("b")
        assertFalse(wl.isHeld)
        assertEquals(2, platformReleases)
    }

    @Test
    fun `speed bypass releases claim at 1-5x and reopens cleanly on return to 1-0x`() {
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
        coord.onNativeSessionOpened(handle = 88L, deviceId = 3, deviceName = "Audiocular D07")
        coord.onStreamingActive(96000, 24, "Audiocular D07")
        assertTrue(coord.hasActiveClaim)

        coord.onSpeedBypass(1.5f)
        assertFalse(coord.hasActiveClaim)
        assertEquals(0L, coord.activeHandle)
        assertEquals(listOf(88L), released)
        assertEquals(io.max.paperplay.player.BitPerfectStatus.Bypassed("Speed 1.5x"), coord.status.value)

        coord.onNativeSessionOpened(handle = 99L, deviceId = 3, deviceName = "Audiocular D07")
        coord.onStreamingActive(96000, 24, "Audiocular D07")
        assertTrue(coord.hasActiveClaim)
        assertTrue(coord.status.value is io.max.paperplay.player.BitPerfectStatus.Active)
    }
}
