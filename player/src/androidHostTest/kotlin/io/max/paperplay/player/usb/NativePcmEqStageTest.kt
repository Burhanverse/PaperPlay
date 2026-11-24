package io.max.paperplay.player.usb

import androidx.media3.common.C
import io.max.paperplay.player.NativePcmEqStage
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.player.PcmEqNativeApi
import io.max.paperplay.player.PeqChannel
import io.max.paperplay.player.PeqFilterType
import io.max.paperplay.player.PlaybackCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativePcmEqStageTest {

    private val savedPeq = PlaybackCore.parametricEq
    private val savedPeqOn = PlaybackCore.parametricEnabled
    private val savedAllowUsbEq = PlaybackCore.allowEqInUsbExclusive
    private val savedUsbEqActive = PlaybackCore.usbEqActive

    @AfterTest
    fun restoreState() {
        PlaybackCore.parametricEq = savedPeq
        PlaybackCore.parametricEnabled = savedPeqOn
        PlaybackCore.allowEqInUsbExclusive = savedAllowUsbEq
        PlaybackCore.usbEqActive = savedUsbEqActive
    }

    private class FakeEqNativeApi : PcmEqNativeApi {
        var nextHandle = 100L
        var processCalls = 0
        var lastKind = -1
        var setPeqCalls = 0

        override val available: Boolean = true
        override fun create(rate: Int, channels: Int): Long = nextHandle++
        override fun destroy(handle: Long) {}
        override fun reconfigure(handle: Long, rate: Int, channels: Int) {}
        override fun setParametricEq(
            handle: Long,
            enabled: Boolean,
            preampDb: Float,
            autoPreamp: Boolean,
            limiterEnabled: Boolean,
            bands: FloatArray,
        ) {
            setPeqCalls++
        }
        override fun setDry(handle: Long, on: Boolean) {}
        override fun processPcmInPlace(handle: Long, buf: ByteBuffer, offset: Int, length: Int, kind: Int): Int {
            processCalls++
            lastKind = kind
            // Simulate deterministic +6 dB / gain shaping by flipping/doubling 16-bit LE samples in-place.
            var pos = offset
            while (pos + 1 < offset + length) {
                val lo = buf.get(pos).toInt() and 0xFF
                val hi = buf.get(pos + 1).toInt()
                val sample = (hi shl 8) or lo
                val boosted = (sample * 2).coerceIn(-32768, 32767)
                buf.put(pos, (boosted and 0xFF).toByte())
                buf.put(pos + 1, ((boosted ushr 8) and 0xFF).toByte())
                pos += 2
            }
            return length / 2
        }
        override fun reset(handle: Long) {}
    }

    private fun activeProfile(): ParametricEqProfile = ParametricEqProfile(
        id = "test-boost",
        name = "Test Boost",
        preampDb = 0f,
        autoPreamp = false,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(
                id = 1,
                enabled = true,
                type = PeqFilterType.PK,
                channel = PeqChannel.STEREO,
                freqHz = 1000f,
                gainDb = 6f,
                q = 1.41f,
            ),
        ),
    )

    @Test
    fun `read-only direct MediaCodec buffer returns processed direct scratch instead of discarding EQ`() {
        val fakeApi = FakeEqNativeApi()
        val stage = NativePcmEqStage(fakeApi)
        stage.configure(sampleRate = 96000, channelCount = 2, pcmEncoding = C.ENCODING_PCM_16BIT)

        PlaybackCore.parametricEq = activeProfile()
        PlaybackCore.parametricEnabled = true
        PlaybackCore.allowEqInUsbExclusive = true

        val backing = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
        backing.putShort(1000).putShort(-1000).putShort(2500).putShort(-2500)
        backing.flip()
        val originalBytes = ByteArray(8).also { backing.duplicate().get(it) }

        val readOnlyMediaCodecBuf = backing.asReadOnlyBuffer()
        assertTrue(readOnlyMediaCodecBuf.isReadOnly)

        val out = stage.processToDirectBufferIfActive(readOnlyMediaCodecBuf, isDoP = false)
        assertNotNull(out, "Expected processed direct buffer for read-only input")
        assertFalse(out.isReadOnly)
        assertEquals(0, out.position())
        assertEquals(8, out.limit())
        assertEquals(1, fakeApi.processCalls)
        assertEquals(0, fakeApi.lastKind)
        assertTrue(PlaybackCore.usbEqActive)

        // Returned direct buffer contains processed samples (doubled), NOT untouched original PCM.
        val outOrder = out.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000.toShort(), outOrder.getShort())
        assertEquals((-2000).toShort(), outOrder.getShort())
        assertEquals(5000.toShort(), outOrder.getShort())
        assertEquals((-5000).toShort(), outOrder.getShort())

        // Caller's read-only buffer remains unconsumed & untouched until sink transfers from `out`.
        assertEquals(0, readOnlyMediaCodecBuf.position())
        assertEquals(8, readOnlyMediaCodecBuf.limit())
        val afterOriginal = ByteArray(8).also { readOnlyMediaCodecBuf.duplicate().get(it) }
        assertContentEquals(originalBytes, afterOriginal)

        // In-place helper refuses read-only buffer cleanly instead of lying true.
        assertFalse(stage.processInPlaceIfActive(readOnlyMediaCodecBuf))
    }

    @Test
    fun `bypassed or disallowed or DoP returns null with zero native processing calls`() {
        val fakeApi = FakeEqNativeApi()
        val stage = NativePcmEqStage(fakeApi)
        stage.configure(sampleRate = 96000, channelCount = 2, pcmEncoding = C.ENCODING_PCM_16BIT)

        val src = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1234).putShort(-1234).putShort(567).putShort(-567).apply { flip() }
            .asReadOnlyBuffer()

        PlaybackCore.parametricEq = activeProfile()
        PlaybackCore.parametricEnabled = true
        PlaybackCore.allowEqInUsbExclusive = false
        assertNull(stage.processToDirectBufferIfActive(src, isDoP = false))
        assertFalse(PlaybackCore.usbEqActive)

        PlaybackCore.allowEqInUsbExclusive = true
        PlaybackCore.parametricEnabled = false
        assertNull(stage.processToDirectBufferIfActive(src, isDoP = false))
        assertFalse(PlaybackCore.usbEqActive)

        PlaybackCore.parametricEnabled = true
        assertNull(stage.processToDirectBufferIfActive(src, isDoP = true))
        assertFalse(PlaybackCore.usbEqActive)

        PlaybackCore.parametricEq = ParametricEqProfile.flat10()
        assertNull(stage.processToDirectBufferIfActive(src, isDoP = false))
        assertEquals(0, fakeApi.processCalls)
    }

    @Test
    fun `repeated configure with identical format does not re-upload parametric EQ bands`() {
        val fakeApi = FakeEqNativeApi()
        val stage = NativePcmEqStage(fakeApi)
        PlaybackCore.parametricEq = activeProfile()
        PlaybackCore.parametricEnabled = true
        PlaybackCore.allowEqInUsbExclusive = true

        val buf = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(100).putShort(-100).putShort(200).putShort(-200).apply { flip() }

        repeat(10) {
            stage.configure(sampleRate = 96000, channelCount = 2, pcmEncoding = C.ENCODING_PCM_16BIT)
            buf.position(0)
            stage.processToDirectBufferIfActive(buf, isDoP = false)
        }

        assertEquals(1, fakeApi.setPeqCalls, "Identical configure() calls must not re-upload PEQ bands per buffer")
        assertEquals(10, fakeApi.processCalls)
    }
}
