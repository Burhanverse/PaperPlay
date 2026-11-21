package io.max.paperplay.player.usb

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UsbAudioSinkFaultInjectionTest {
    private class FaultingCoarseApi(
        var openReturn: Long = 101L,
        var reconfigureReturn: Int = 0,
        var applyVolumeReturn: Int = 0,
        var startReturn: Int = 0,
        var writeReturn: Int? = null,
    ) : CoarseUsbSessionApi {
        var closedHandles = mutableListOf<Long>()
        override val available: Boolean = true
        override fun open(fd: Int, controlIface: Int, streamingIface: Int, isUac2: Boolean, clockSourceId: Int, hasHardwareVolume: Boolean, featureUnitId: Int) = openReturn
        override fun reconfigure(handle: Long, endpoint: Int, streamingIface: Int, targetAlt: Int, sampleRate: Int, bitDepth: Int, channels: Int, subslotSize: Int, isHighSpeed: Boolean, isDop: Boolean, feedbackEp: Int) = reconfigureReturn
        override fun applyVolume(handle: Long, featureUnitId: Int, controlIface: Int, channel: Int, volumeRaw: Int, mute: Boolean) = applyVolumeReturn
        override fun start(handle: Long) = startReturn
        override fun pauseGrace(handle: Long) = 0
        override fun stop(handle: Long) = 0
        override fun writePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int) = writeReturn ?: length
        override fun setMuteRamp(handle: Long, muted: Boolean) = 0
        override fun flush(handle: Long) = 0
        override fun getStats(handle: Long, outStats: LongArray) = 0
        override fun close(handle: Long) { closedHandles.add(handle) }
    }

    @Test
    fun `reconfigure mismatch, volume rejection, and write failure fall back cleanly for PCM without throwing`() {
        var fallbackReason: String? = null
        var now = 0L
        val api = FaultingCoarseApi(reconfigureReturn = -22)
        val c = UsbCoarseSinkController(api, { now }, onFallback = { fallbackReason = it })

        assertFalse(c.openAndConfigure(1, 0, 1, 1, 1, true, 9, true, 2, 48000, 24, 2, 4, true, false, 0, 0, false))
        assertTrue(c.isFallback)
        assertNotNull(fallbackReason)
        assertEquals(listOf(101L), api.closedHandles)

        val api2 = FaultingCoarseApi(startReturn = -11)
        fallbackReason = null
        val c2 = UsbCoarseSinkController(api2, { now }, onFallback = { fallbackReason = it })
        assertFalse(c2.openAndConfigure(1, 0, 1, 1, 1, true, 9, true, 2, 48000, 24, 2, 4, true, false, 0, 0, false))
        assertTrue(c2.isFallback)
        assertEquals(listOf(101L), api2.closedHandles)

        val api3 = FaultingCoarseApi(writeReturn = -19)
        fallbackReason = null
        val c3 = UsbCoarseSinkController(api3, { now }, onFallback = { fallbackReason = it })
        assertTrue(c3.openAndConfigure(1, 0, 1, 1, 1, true, 9, true, 2, 48000, 24, 2, 4, true, false, 0, 0, false))
        val buf = ByteBuffer.allocateDirect(64)
        assertFalse(c3.writePcm(buf, 24, false))
        assertTrue(c3.isFallback)
        assertNotNull(fallbackReason)
    }

    @Test
    fun `DoP failure hard stops instead of falling back to normal mixer`() {
        var fallbacks = 0
        var hardStops = 0
        val api = FaultingCoarseApi(writeReturn = -19)
        val c = UsbCoarseSinkController(
            api = api,
            clockMs = { 0L },
            onFallback = { fallbacks++ },
            onHardStop = { hardStops++ },
        )
        assertTrue(c.openAndConfigure(1, 0, 1, 1, 1, true, 9, false, 0, 176400, 24, 2, 4, true, isDop = true, 0, 0, false))
        val buf = ByteBuffer.allocateDirect(64)
        assertFalse(c.writePcm(buf, 24, false))
        assertTrue(c.isHardStopped)
        assertFalse(c.isFallback)
        assertEquals(1, hardStops)
        assertEquals(0, fallbacks)
    }
}
