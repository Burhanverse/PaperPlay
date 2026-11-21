package io.max.paperplay.player.usb

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import io.max.paperplay.player.MIME_AUDIO_DOP
import io.max.paperplay.player.PlaybackCore
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class UnifiedUsbAudioSinkContractTest {

    private class FakeDelegateAudioSink(
        var delegateSupportReturn: Int = AudioSink.SINK_FORMAT_UNSUPPORTED,
    ) : AudioSink {
        var supportsCalls = 0
        var supportCalls = 0
        var configureCalls = 0

        override fun setListener(listener: AudioSink.Listener) {}
        override fun supportsFormat(format: Format): Boolean {
            supportsCalls++
            return getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        override fun getFormatSupport(format: Format): Int {
            supportCalls++
            return delegateSupportReturn
        }
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = AudioSink.CURRENT_POSITION_NOT_SET
        override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
            configureCalls++
        }
        override fun configure(config: AudioSink.AudioSinkConfig) {
            configureCalls++
        }
        override fun play() {}
        override fun handleDiscontinuity() {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int) = true
        override fun playToEndOfStream() {}
        override fun isEnded() = false
        override fun hasPendingData() = false
        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
        override fun getSkipSilenceEnabled() = false
        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
        override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT
        override fun setAudioSessionId(audioSessionId: Int) {}
        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
        override fun getAudioTrackBufferSizeUs(): Long = 0L
        override fun enableTunnelingV21() {}
        override fun disableTunneling() {}
        override fun setVolume(volume: Float) {}
        override fun pause() {}
        override fun flush() {}
        override fun reset() {}
        override fun release() {}
    }

    private val unusedSessionApi = object : CoarseUsbSessionApi {
        override val available = false
        override fun open(fd: Int, ci: Int, si: Int, u2: Boolean, csid: Int, hwv: Boolean, fu: Int) = 0L
        override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int) = -1
        override fun applyVolume(h: Long, fu: Int, ci: Int, ch: Int, v: Int, m: Boolean) = -1
        override fun start(h: Long) = -1
        override fun pauseGrace(h: Long) = -1
        override fun stop(h: Long) = -1
        override fun writePcm(h: Long, buf: ByteBuffer, off: Int, len: Int) = -1
        override fun setMuteRamp(h: Long, m: Boolean) = -1
        override fun flush(h: Long) = -1
        override fun getStats(h: Long, outStats: LongArray) = -1
        override fun close(h: Long) {}
    }

    private var savedExclusive = false

    init {
        savedExclusive = PlaybackCore.usbExclusiveMode
    }

    @AfterTest
    fun restorePlaybackCore() {
        PlaybackCore.usbExclusiveMode = savedExclusive
        PlaybackCore.usbActive = false
    }

    private fun pcmFormat(mime: String? = "audio/raw", pcmEncoding: Int, rate: Int = 96000, channels: Int = 2): Format =
        Format.Builder()
            .apply { if (mime != null) setSampleMimeType(mime) }
            .setPcmEncoding(pcmEncoding)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .build()

    @Test
    fun `UnifiedUsbAudioSink returns SINK_FORMAT_SUPPORTED_DIRECTLY == 2 for 16, 24, 32 int PCM, float PCM, and DoP across any rate`() {
        assertEquals(2, AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        PlaybackCore.usbExclusiveMode = true

        val sink = UnifiedUsbAudioSink(context = null, normalSink = FakeDelegateAudioSink(), sessionApi = unusedSessionApi)
        val encodings = listOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )
        for (rate in listOf(8000, 44100, 48000, 96000, 176400, 192000, 384000, 768000, Format.NO_VALUE)) {
            for (enc in encodings) {
                val fmt = pcmFormat("audio/raw", enc, rate)
                assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(fmt))
                assertTrue(sink.supportsFormat(fmt))
                val nullMimeFmt = pcmFormat(null, enc, rate)
                assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(nullMimeFmt))
                assertTrue(sink.supportsFormat(nullMimeFmt))
            }
            val dopFmt = pcmFormat(MIME_AUDIO_DOP, C.ENCODING_PCM_24BIT, rate)
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(dopFmt))
            assertTrue(sink.supportsFormat(dopFmt))
        }
    }

    @Test
    fun `UnifiedUsbAudioSink rejects encoded MIMEs and invalid rates or channels even with Bit-perfect ON`() {
        PlaybackCore.usbExclusiveMode = true
        val sink = UnifiedUsbAudioSink(context = null, normalSink = FakeDelegateAudioSink(), sessionApi = unusedSessionApi)

        for (mime in listOf("audio/flac", "audio/mpeg", "audio/mp4a-latm", "audio/opus")) {
            val encoded = Format.Builder()
                .setSampleMimeType(mime)
                .setSampleRate(48000)
                .setChannelCount(2)
                .build()
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
            assertFalse(sink.supportsFormat(encoded))
        }

        val badRate = pcmFormat("audio/raw", C.ENCODING_PCM_16BIT, rate = 0, channels = 2)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(badRate))
        assertFalse(sink.supportsFormat(badRate))

        val badCh = pcmFormat("audio/raw", C.ENCODING_PCM_24BIT, rate = 48000, channels = 0)
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(badCh))
        assertFalse(sink.supportsFormat(badCh))
    }

    @Test
    fun `supportsFormat and getFormatSupport depend only on static capabilities never on transient sink state`() {
        PlaybackCore.usbExclusiveMode = true
        val delegate = FakeDelegateAudioSink(AudioSink.SINK_FORMAT_UNSUPPORTED)
        val sink = UnifiedUsbAudioSink(context = null, normalSink = delegate, sessionApi = unusedSessionApi)
        val fmt96 = pcmFormat("audio/raw", C.ENCODING_PCM_24BIT, rate = 96000, channels = 2)

        assertFalse(sink.isUsbModeActive)
        assertEquals(2, sink.getFormatSupport(fmt96))
        assertTrue(sink.supportsFormat(fmt96))

        // Transient fallback configure (no DAC attached) must not alter static capability queries.
        sink.configure(AudioSink.AudioSinkConfig.Builder(fmt96).build())
        assertFalse(sink.isUsbModeActive)
        assertEquals(2, sink.getFormatSupport(fmt96))
        assertTrue(sink.supportsFormat(fmt96))

        // Coordinator/lifecycle teardown must still keep static capability queries constant.
        sink.releaseFromCoordinator(0L)
        sink.release()
        assertEquals(2, sink.getFormatSupport(fmt96))
        assertTrue(sink.supportsFormat(fmt96))
        assertEquals(0, delegate.supportCalls)
    }

    @Test
    fun `when Bit-perfect is OFF supportsFormat and getFormatSupport delegate 1-1 to normalSink`() {
        PlaybackCore.usbExclusiveMode = false
        val delegate = FakeDelegateAudioSink(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING)
        val sink = UnifiedUsbAudioSink(context = null, normalSink = delegate, sessionApi = unusedSessionApi)
        val fmt = pcmFormat("audio/raw", C.ENCODING_PCM_24BIT, rate = 96000, channels = 2)

        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(fmt))
        assertTrue(sink.supportsFormat(fmt))
        assertEquals(2, delegate.supportCalls)
        assertEquals(1, delegate.supportsCalls)
    }
}
