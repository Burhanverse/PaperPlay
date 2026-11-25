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
    fun `a sample cut by a full ring is finished first so later samples are not shifted`() {
        // 16-bit source on a 24-bit, 3-byte DAC: the converter runs. The ring takes 7 bytes, then 1, then everything.
        val ring = java.io.ByteArrayOutputStream()
        val room = ArrayDeque(listOf(7, 1))
        val api = object : CoarseUsbSessionApi by FaultingCoarseApi() {
            override fun writePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int): Int {
                val n = minOf(length, room.removeFirstOrNull() ?: length)
                for (k in 0 until n) ring.write(buffer.get(offset + k).toInt())
                return n
            }
        }
        val c = UsbCoarseSinkController(api, { 0L })
        assertTrue(c.openAndConfigure(1, 0, 1, 1, 1, true, 9, true, 2, 44100, 24, 2, 3, true, false, 0, 0, false))
        val src = ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in 1..8) src.putShort((v * 256 + v).toShort())
        src.flip()
        var calls = 0
        while (src.hasRemaining() && calls++ < 20) assertTrue(c.writePcm(src, 16, false))
        val out = ring.toByteArray()
        assertEquals(24, out.size)
        // Every sample sits at a multiple of three bytes, its 16 bits on top.
        for (v in 1..8) assertEquals(listOf(v, v), listOf(out[(v - 1) * 3 + 1].toInt(), out[(v - 1) * 3 + 2].toInt()), "sample $v")
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

    @Test
    fun `per-channel volume applies negative dB to all channels and handle-matched close prevents stale double close`() {
        val volCalls = mutableListOf<Triple<Int, Int, Boolean>>()
        val trackingApi = object : CoarseUsbSessionApi {
            var nextHandle = 500L
            val closed = mutableListOf<Long>()
            override val available: Boolean = true
            override fun open(fd: Int, controlIface: Int, streamingIface: Int, isUac2: Boolean, clockSourceId: Int, hasHardwareVolume: Boolean, featureUnitId: Int) = nextHandle++
            override fun reconfigure(handle: Long, endpoint: Int, streamingIface: Int, targetAlt: Int, sampleRate: Int, bitDepth: Int, channels: Int, subslotSize: Int, isHighSpeed: Boolean, isDop: Boolean, feedbackEp: Int) = 0
            override fun applyVolume(handle: Long, featureUnitId: Int, controlIface: Int, channel: Int, volumeRaw: Int, mute: Boolean): Int {
                volCalls.add(Triple(channel, volumeRaw, mute))
                return volumeRaw and 0xFFFF
            }
            override fun start(handle: Long) = 0
            override fun pauseGrace(handle: Long) = 0
            override fun stop(handle: Long) = 0
            override fun writePcm(handle: Long, buffer: ByteBuffer, offset: Int, length: Int) = length
            override fun setMuteRamp(handle: Long, muted: Boolean) = 0
            override fun flush(handle: Long) = 0
            override fun getStats(handle: Long, outStats: LongArray) = 0
            override fun close(handle: Long) { closed.add(handle) }
        }
        val c = UsbCoarseSinkController(trackingApi, { 0L })
        val lowStepDb = UsbVolumeCurve.computeVolumeDb(index = 2, maxIndex = 16, dacMinDb = -64f, dacMaxDb = 0f, dacResDb = 0.5f)
        val raw = UsbVolumeCurve.dbToRaw(lowStepDb, (-64 * 256).toShort(), 0, 128).toInt()
        assertTrue(raw < -30 * 256, "Expected negative attenuation < -30 dB at index 2/16, got raw=$raw")

        assertTrue(c.openAndConfigure(1, 0, 1, 1, 1, true, 9, true, 2, 48000, 24, 2, 4, true, false, 0, raw, false, volumeChannels = listOf(1, 2)))
        assertEquals(listOf(Triple(1, raw, false), Triple(2, raw, false)), volCalls)

        // Mismatched handle does not close active session
        c.closeIfHandleMatches(999L)
        assertTrue(trackingApi.closed.isEmpty())
        assertEquals(500L, c.handle)

        // Matching handle closes exactly once
        c.closeIfHandleMatches(500L)
        c.closeIfHandleMatches(500L)
        assertEquals(listOf(500L), trackingApi.closed)
        assertEquals(0L, c.handle)
    }

    @Test
    fun `wire converter preserves MSB two-s complement sign for 32-bit vs zero byte3 for 24-in-32`() {
        val conv = UsbWireBufferConverter()
        val fSrc = ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fSrc.putFloat(-0.5f) // -4194304 = 0xFF_C00000 in i24 -> shl 8 = 0xC0000000
        fSrc.flip()
        val out32 = conv.convert(fSrc.duplicate(), 32, isFloat = true, targetSubslotBytes = 4, targetBitDepth = 32)
        assertEquals(0xC0000000.toInt(), out32.getInt())

        val out24 = conv.convert(fSrc.duplicate(), 32, isFloat = true, targetSubslotBytes = 4, targetBitDepth = 24)
        assertEquals(0x00, out24.get(0).toInt() and 0xFF)
        assertEquals(0x00, out24.get(1).toInt() and 0xFF)
        assertEquals(0xC0, out24.get(2).toInt() and 0xFF)
        assertEquals(0x00, out24.get(3).toInt() and 0xFF)

        val i16Src = ByteBuffer.allocateDirect(2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        i16Src.putShort((-16384).toShort()) // -16384 = 0xC000 -> shl 16 = 0xC0000000
        i16Src.flip()
        val i16Out32 = conv.convert(i16Src.duplicate(), 16, isFloat = false, targetSubslotBytes = 4, targetBitDepth = 32)
        assertEquals(0xC0000000.toInt(), i16Out32.getInt())

        val i24Src = ByteBuffer.allocateDirect(3).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        i24Src.put(0x01).put(0x02).put(0x80.toByte()) // -8388095
        i24Src.flip()
        val i24Out32 = conv.convert(i24Src.duplicate(), 24, isFloat = false, targetSubslotBytes = 4, targetBitDepth = 32)
        assertEquals(0x00, i24Out32.get(0).toInt() and 0xFF)
        assertEquals(0x01, i24Out32.get(1).toInt() and 0xFF)
        assertEquals(0x02, i24Out32.get(2).toInt() and 0xFF)
        assertEquals(0x80, i24Out32.get(3).toInt() and 0xFF)
    }

    @Test
    fun `unified controller captures exact wire bytes into UsbPcmDumper across 16bit-2b, 24bit-3b, and 24bit-LSB patterns`() {
        val tempDir = java.nio.file.Files.createTempDirectory("pcm_dump_test").toFile()
        try {
            val api = object : CoarseUsbSessionApi {
                override val available = true
                override fun open(fd: Int, ci: Int, si: Int, u2: Boolean, csid: Int, hwv: Boolean, fu: Int) = 1L
                override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int) = 0
                override fun applyVolume(h: Long, fu: Int, ci: Int, ch: Int, v: Int, m: Boolean) = v and 0xFFFF
                override fun start(h: Long) = 0
                override fun pauseGrace(h: Long) = 0
                override fun stop(h: Long) = 0
                override fun writePcm(h: Long, buf: ByteBuffer, off: Int, len: Int) = len
                override fun setMuteRamp(h: Long, m: Boolean) = 0
                override fun flush(h: Long) = 0
                override fun getStats(h: Long, outStats: LongArray) = 0
                override fun close(h: Long) {}
            }
            val c = UsbCoarseSinkController(api, { 0L })

            // Case 1: 16-bit / 44.1 kHz, 2-byte subslot
            UsbPcmDumper.enable(tempDir, durationSec = 5)
            assertTrue(c.openAndConfigure(1, 0, 1, 1, 1, true, 9, false, 0, 44100, 16, 2, 2, true, false, 0, 0, false))
            UsbPcmDumper.onStreamConfigured(tempDir, 44100, 16, 2, 2)
            val src16 = ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            src16.putShort(1234).putShort((-5678).toShort()).putShort(32767).putShort((-32768).toShort()).flip()
            val expected16 = ByteArray(8).also { src16.duplicate().get(it) }
            assertTrue(c.writePcm(src16, 16, isFloat = false))
            val dump16Path = UsbPcmDumper.dumpFilePath!!
            UsbPcmDumper.finishIfDumping("done16")
            assertEquals(expected16.toList(), java.io.File(dump16Path).readBytes().toList())

            // Case 2: 24-bit / 48 kHz LSB pattern in 3-byte subslot
            UsbPcmDumper.enable(tempDir, durationSec = 5)
            assertTrue(c.openAndConfigure(1, 0, 1, 2, 1, true, 9, false, 0, 48000, 24, 2, 3, true, false, 0, 0, false))
            assertTrue(c.lastConfigureWasInPlace)
            UsbPcmDumper.onStreamConfigured(tempDir, 48000, 24, 2, 3)
            val src24 = ByteBuffer.allocateDirect(6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            src24.put(byteArrayOf(0x01, 0x23, 0x45, 0xAA.toByte(), 0xBB.toByte(), 0x7F)).flip()
            assertTrue(c.writePcm(src24, 24, isFloat = false))
            val dump24Path = UsbPcmDumper.dumpFilePath!!
            UsbPcmDumper.finishIfDumping("done24")
            assertEquals(listOf<Byte>(0x01, 0x23, 0x45, 0xAA.toByte(), 0xBB.toByte(), 0x7F), java.io.File(dump24Path).readBytes().toList())

            // Case 3: 24-bit float / 96 kHz into 32-bit / 4-byte MSB-aligned subslot (alt 3)
            UsbPcmDumper.enable(tempDir, durationSec = 5)
            assertTrue(c.openAndConfigure(1, 0, 1, 3, 1, true, 9, true, 2, 96000, 32, 2, 4, true, false, 0, -512, false))
            assertEquals(-512, c.lastVolumeReadbackRaw)
            UsbPcmDumper.onStreamConfigured(tempDir, 96000, 32, 2, 4)
            val srcF = ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            srcF.putFloat(100.0f / 8388608.0f).putFloat(-100.0f / 8388608.0f).flip()
            assertTrue(c.writePcm(srcF, 32, isFloat = true))
            val dump32Path = UsbPcmDumper.dumpFilePath!!
            UsbPcmDumper.finishIfDumping("done32")
            val raw32 = ByteBuffer.wrap(java.io.File(dump32Path).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            assertEquals(100 shl 8, raw32.getInt())
            assertEquals((-100) shl 8, raw32.getInt())
        } finally {
            UsbPcmDumper.disable()
            tempDir.deleteRecursively()
        }
    }
}
