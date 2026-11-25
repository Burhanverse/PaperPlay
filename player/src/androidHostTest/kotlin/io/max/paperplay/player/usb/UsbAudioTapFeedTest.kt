package io.max.paperplay.player.usb

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import io.max.paperplay.player.AudioTap
import io.max.paperplay.player.EngineAudioSink
import io.max.paperplay.player.MIME_AUDIO_DOP
import io.max.paperplay.player.PlaybackCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbAudioTapFeedTest {
    private var nowNs = 2_000_000_000_000L

    @BeforeTest
    fun setUp() {
        nowNs = 2_000_000_000_000L
        AudioTap.clockNs = { nowNs }
        AudioTap.reset()
        PlaybackCore.usbExclusiveMode = false
        PlaybackCore.usbActive = false
        PlaybackCore.allowEqInUsbExclusive = false
        PlaybackCore.parametricEnabled = false
        PlaybackCore.parametricEq = null
        PlaybackCore.engineSinkHandlesTap = false
    }

    @AfterTest
    fun tearDown() {
        AudioTap.reset()
        AudioTap.clockNs = { System.nanoTime() }
        PlaybackCore.usbExclusiveMode = false
        PlaybackCore.usbActive = false
        PlaybackCore.engineSinkHandlesTap = false
    }

    private class FakeDelegateSink(var acceptBytes: Int = Int.MAX_VALUE) : AudioSink {
        var posUs = 0L
        override fun setListener(listener: AudioSink.Listener) {}
        override fun supportsFormat(format: Format): Boolean = true
        override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = posUs
        override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {}
        override fun play() {}
        override fun pause() {}
        override fun handleDiscontinuity() {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (acceptBytes <= 0) return false
            val take = minOf(acceptBytes, buffer.remaining())
            buffer.position(buffer.position() + take)
            return !buffer.hasRemaining()
        }
        override fun playToEndOfStream() {}
        override fun isEnded(): Boolean = false
        override fun hasPendingData(): Boolean = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled(): Boolean = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
        override fun getAudioAttributes(): AudioAttributes? = null
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
        override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) {}
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun getAudioTrackBufferSizeUs(): Long = 0L
        override fun flush() {}
        override fun reset() {}
    }

    @Test
    fun engineAudioSinkPassThroughDoesNotFeedTapWhenDelegateRejectsBuffer() {
        val delegate = FakeDelegateSink(acceptBytes = 0)
        val sink = EngineAudioSink(delegate, forceInactive = true)
        val fmt = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(44100)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(fmt).build())

        val out = FloatArray(AudioTap.BANDS)
        AudioTap.sample(out, nowNs) // wanted = true

        val buf = ByteBuffer.allocateDirect(4096).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1024) {
            val s = (0.5 * sin(2 * PI * 440.0 * it / 44100.0) * 32767.0).toInt().toShort()
            buf.putShort(s); buf.putShort(s)
        }
        buf.flip()

        // Backpressure retries: 0 bytes accepted -> 0 samples pushed!
        repeat(5) {
            assertFalse(sink.handleBuffer(buf, 0L, 1))
        }
        assertEquals(0L, AudioTap.pushSampleCount, "rejected buffers must never feed AudioTap")

        // Now accept buffer -> feeds once
        delegate.acceptBytes = Int.MAX_VALUE
        assertTrue(sink.handleBuffer(buf, 0L, 1))
        assertEquals(1024L, AudioTap.pushSampleCount)
        sink.release()
    }

    @Test
    fun usbSinkFeedsTapForPcmWithoutAlteringDacBytesAndMutesForDoP() {
        val captured = mutableListOf<ByteArray>()
        val fakeBridge = object : UsbAudioSinkBridge {
            override var isPrepared = false
            override var isRunning = false
            override val confirmedRate = 48000
            override val confirmedAlt = 1
            override fun prepare(): Boolean { isPrepared = true; return true }
            override fun start(): Boolean { isRunning = true; return true }
            override fun pause() { isRunning = false }
            override fun flush() {}
            override fun stopAndRelease() { isRunning = false; isPrepared = false }
            override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
                val arr = ByteArray(length)
                val ro = buffer.duplicate()
                ro.position(offset)
                ro.get(arr, 0, length)
                captured.add(arr)
                return length
            }
            override fun getStats(): UsbStreamStats = UsbStreamStats(1024, 0, 0, 0, 1048576, isRunning, 48000, !isRunning, false)
            override fun switchRate(newRate: Int): Boolean = true
        }
        val sm = UsbSinkStateMachine(bridgeFactory = { _, _, _ -> fakeBridge })
        val alt = UsbAudioAltSetting(
            interfaceNumber = 1,
            altSetting = 1,
            terminalLink = 1,
            formatTag = AudioFormatTag.PCM,
            bmFormats = 1L,
            channelCount = 2,
            subslotSize = 2,
            bitResolution = 16,
            sampleRates = listOf(48000),
            dataEndpoint = UsbEndpointDescriptor(0x01, true, EndpointSyncType.ASYNCHRONOUS, EndpointUsageType.DATA, 1024, 1),
            isDsdRawCapable = false,
            isDsdDoPCapable = false,
        )
        val sink = UsbAudioSink(
            context = android.app.Application(),
            normalSink = FakeDelegateSink(),
            testStateMachine = sm,
            testAltSettings = listOf(alt),
        )

        PlaybackCore.usbExclusiveMode = true
        val fmt = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(48000)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(fmt).build())

        val out = FloatArray(AudioTap.BANDS)
        AudioTap.sample(out, nowNs) // wanted = true

        val buf = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 2048) {
            val s = (0.5 * sin(2 * PI * 1000.0 * i / 48000.0) * 32767.0).toInt().toShort()
            buf.putShort(s); buf.putShort(s)
        }
        buf.flip()
        val expected = ByteArray(buf.remaining())
        buf.duplicate().get(expected)

        assertTrue(sink.handleBuffer(buf, 0L, 1))
        assertTrue(captured.isNotEmpty())
        assertTrue(expected.contentEquals(captured.first()), "DAC bytes must remain bit-exact")
        assertTrue(AudioTap.pushSampleCount >= 2048L, "USB PCM must feed AudioTap")
        assertTrue(AudioTap.sample(out, nowNs), "USB PCM must produce live spectrum")

        // Verify DoP clears and stays idle
        sm.configure(176400, 24, 2, listOf(alt.copy(bitResolution = 24, subslotSize = 4, sampleRates = listOf(176400), isDsdDoPCapable = true)), isDop = true)
        assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN), 0L, 1))
        assertFalse(AudioTap.sample(out, nowNs), "DoP must keep visualizer idle")
        sink.release()
    }
}
