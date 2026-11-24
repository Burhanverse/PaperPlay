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
        var playCalls = 0
        var handleBufferCalls = 0

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
        override fun play() { playCalls++ }
        override fun handleDiscontinuity() {}
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            handleBufferCalls++
            return true
        }
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

    @Test
    fun `real KZ Acoustics C04 (31b2-0313) probe descriptor negotiates and configures 44-1k-16, 48k-24, 96k-16, and 96k-24 on interface 2 ep 0x04 FU 7`() {
        val kzC04Hex =
            "1201000200000040b23113030300010402010902490104010080310904000000010100000a2401000147000201020c24" +
            "020101020001010000000924060201010102000924030401010002000c24020501010002030000000a24060705010102" +
            "0200092403080203000700090401000001020000090401010101020000072401040101000b2402010102100180bb0009" +
            "058405620001000007250101000000090401020101020000072401040101000b2402010103180180bb00090584059300" +
            "0100000725010100000009040200000102000009040201010102000007240105010100142402010202100444ac0080bb" +
            "008858010077010905040584010100000725010100000009040202010102000007240105010100142402010203180444" +
            "ac0088580180bb0000770109050405460201000007250101000000090403000203000000092111010001224600070583" +
            "0310000107050303100001"
        val raw = ByteArray(kzC04Hex.length / 2) { i -> kzC04Hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val desc = UsbDescriptorParser.parse(raw)
        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        assertTrue(desc.isSupported)

        val reconfigs = mutableListOf<String>()
        val volCalls = mutableListOf<Triple<Int, Int, Int>>()
        val trackingApi = object : CoarseUsbSessionApi {
            override val available = true
            override fun open(fd: Int, ci: Int, si: Int, u2: Boolean, csid: Int, hwv: Boolean, fu: Int): Long {
                assertEquals(0, ci)
                assertEquals(2, si)
                assertFalse(u2)
                assertTrue(hwv)
                assertEquals(7, fu)
                return 313L
            }
            override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int, epAttributes: Int): Int {
                reconfigs.add("if=$si:alt=$alt:ep=0x${ep.toString(16)}:rate=$r:bits=$bd:subslot=$ss:fb=$fb:attr=0x${epAttributes.toString(16)}")
                return 0
            }
            override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int) = 0
            override fun applyVolume(h: Long, fu: Int, ci: Int, ch: Int, v: Int, m: Boolean): Int {
                volCalls.add(Triple(fu, ch, v))
                return v and 0xFFFF
            }
            override fun start(h: Long) = 0
            override fun pauseGrace(h: Long) = 0
            override fun stop(h: Long) = 0
            override fun writePcm(h: Long, buf: ByteBuffer, off: Int, len: Int) = len
            override fun setMuteRamp(h: Long, m: Boolean) = 0
            override fun flush(h: Long) = 0
            override fun getStats(h: Long, outStats: LongArray) = 0
            override fun close(h: Long) {}
        }

        val ctrl = UsbCoarseSinkController(trackingApi, { 0L })
        val matrix = listOf(
            Triple(44100, 16, 1),
            Triple(48000, 24, 2),
            Triple(96000, 16, 1),
            Triple(96000, 24, 2),
        )
        for ((rate, bits, expAlt) in matrix) {
            val pick = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, rate, bits, 2)
            assertTrue(pick is FormatSelectionResult.Success)
            val alt = pick.candidate
            assertEquals(2, alt.interfaceNumber)
            assertEquals(expAlt, alt.altSetting)
            assertEquals(0x04, alt.dataEndpoint?.address)
            val fu = desc.resolvePlaybackFeatureUnit(alt)!!
            assertEquals(7, fu.id)
            val volChannels = if (fu.hasMasterVolume) listOf(0) else listOf(1, 2)

            volCalls.clear()
            val ok = ctrl.openAndConfigure(
                fd = 42,
                controlIface = desc.acInterfaceNumber,
                streamingIface = alt.interfaceNumber,
                targetAlt = alt.altSetting,
                endpoint = alt.dataEndpoint!!.address,
                isUac2 = false,
                clockSourceId = 9,
                hasHwVol = true,
                fuId = fu.id,
                sampleRate = rate,
                bitDepth = alt.bitResolution,
                channels = alt.channelCount,
                subslotSize = alt.subslotSize,
                isHighSpeed = false,
                isDop = false,
                feedbackEp = alt.feedbackEndpoint?.address ?: 0,
                volumeRaw = -1024,
                muted = false,
                volumeChannels = volChannels,
                epAttributes = alt.computeNativeEpAttributes(rate),
            )
            assertTrue(ok)
            assertEquals(rate, ctrl.confirmedRateHz)
            assertEquals(bits, ctrl.confirmedBitDepth)
            assertEquals(expAlt, ctrl.confirmedAlt)
            assertEquals(listOf(Triple(7, 1, -1024), Triple(7, 2, -1024)), volCalls)
        }

        assertEquals(
            listOf(
                "if=2:alt=1:ep=0x4:rate=44100:bits=16:subslot=2:fb=0:attr=0x1",
                "if=2:alt=2:ep=0x4:rate=48000:bits=24:subslot=3:fb=0:attr=0x1",
                "if=2:alt=1:ep=0x4:rate=96000:bits=16:subslot=2:fb=0:attr=0x1",
                "if=2:alt=2:ep=0x4:rate=96000:bits=24:subslot=3:fb=0:attr=0x1",
            ),
            reconfigs,
        )
    }

    @Test
    fun `KZ UAC 1-0 composite descriptor parses audio plus HID and passes epAttributes to coarse session`() {
        val kzHex =
            "090200010401008032" +
            "090400000001010000" +
            "0a240100014700020102" +
            "0c2402010101000203000000" +
            "0a240602010101020203" +
            "092403030203000200" +
            "0c2402040102000101000000" +
            "092406050401030000" +
            "092403060101000500" +
            "090401000001020000" +
            "090401010101020000" +
            "07240101010100" +
            "0e2402010202100280bb00007701" +
            "090501098001010000" +
            "07250101000000" +
            "090401020101020000" +
            "07240101010100" +
            "0b24020102031801007701" +
            "090501094802010000" +
            "07250100000000" +
            "090403000103000000" +
            "092111010001222b00" +
            "0705830310000a"
        val raw = ByteArray(kzHex.length / 2) { i -> kzHex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val desc = UsbDescriptorParser.parse(raw)
        assertEquals(UacVersion.UAC_1_0, desc.uacVersion)
        assertTrue(desc.isSupported)
        assertEquals(2, desc.playbackAltSettings.size)

        val pick = UsbFormatNegotiator.pickBestFormat(desc.playbackAltSettings, 96000, 24, 2)
        assertTrue(pick is FormatSelectionResult.Success)
        val alt2 = pick.candidate
        assertEquals(2, alt2.altSetting)
        assertFalse(alt2.dataEndpoint!!.supportsSampleRateControl)
        val epAttr = alt2.computeNativeEpAttributes(96000)
        assertEquals(0x80, epAttr) // bit 0 = 0 (no rate ctrl), bit 7 = 1 (single discrete 96k)

        var recordedEpAttr = -1
        val trackingApi = object : CoarseUsbSessionApi {
            override val available = true
            override fun open(fd: Int, ci: Int, si: Int, u2: Boolean, csid: Int, hwv: Boolean, fu: Int) = 77L
            override fun reconfigure(h: Long, ep: Int, si: Int, alt: Int, r: Int, bd: Int, ch: Int, ss: Int, hs: Boolean, dop: Boolean, fb: Int, epAttributes: Int): Int {
                recordedEpAttr = epAttributes
                return 0
            }
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
        val ctrl = UsbCoarseSinkController(trackingApi, { 0L })
        val ok = ctrl.openAndConfigure(
            fd = 10,
            controlIface = desc.acInterfaceNumber,
            streamingIface = alt2.interfaceNumber,
            targetAlt = alt2.altSetting,
            endpoint = alt2.dataEndpoint!!.address,
            isUac2 = false,
            clockSourceId = 0,
            hasHwVol = true,
            fuId = 2,
            sampleRate = 96000,
            bitDepth = 24,
            channels = 2,
            subslotSize = 3,
            isHighSpeed = false,
            isDop = false,
            feedbackEp = 0,
            volumeRaw = -512,
            muted = false,
            volumeChannels = listOf(1, 2),
            epAttributes = epAttr,
        )
        assertTrue(ok)
        assertEquals(0x80, recordedEpAttr)
        assertEquals(96000, ctrl.confirmedRateHz)
        assertEquals(2, ctrl.confirmedAlt)
    }

    @Test
    fun `slow or hanging DAC reopen is bounded off playback thread and falls back quickly to normalSink`() {
        PlaybackCore.usbExclusiveMode = true
        val delegate = FakeDelegateAudioSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        var fakeClock = 1_000L
        var runnerCalls = 0
        val sink = UnifiedUsbAudioSink(
            context = null,
            normalSink = delegate,
            sessionApi = unusedSessionApi,
            openWaitBudgetMs = 60L,
            reopenCooldownMs = 2_000L,
            usbOpenRunner = {
                runnerCalls++
                Thread.sleep(500L) // simulates hung usbfs / control transfer
                true
            },
            clockMs = { fakeClock },
        )
        val fmt = pcmFormat("audio/raw", C.ENCODING_PCM_24BIT, rate = 96000, channels = 2)
        sink.configure(AudioSink.AudioSinkConfig.Builder(fmt).build())
        sink.markPendingUsbAttach()

        val startNs = System.nanoTime()
        sink.play()
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue(elapsedMs < 350L, "play() must not block past openWaitBudgetMs (took ${elapsedMs}ms)")
        assertFalse(sink.isUsbModeActive)
        assertEquals(1, runnerCalls)
        assertEquals(1, delegate.playCalls, "Should fall back to normalSink.play() immediately after timeout")

        // Second buffer within cooldown must not block or re-invoke runner
        val buf = ByteBuffer.allocateDirect(64)
        assertTrue(sink.handleBuffer(buf, 0L, 1))
        assertEquals(1, runnerCalls)
        assertEquals(1, delegate.handleBufferCalls)

        sink.release()
    }

    @Test
    fun `fallback in handleBuffer never calls normalSink play while paused even if playerPlayWhenReady is stale true`() {
        PlaybackCore.usbExclusiveMode = true
        PlaybackCore.playerPlayWhenReady = true // stale flag!
        val delegate = FakeDelegateAudioSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
        val sink = UnifiedUsbAudioSink(context = null, normalSink = delegate, sessionApi = unusedSessionApi)
        val fmt = pcmFormat("audio/raw", C.ENCODING_PCM_24BIT, rate = 48000, channels = 2)
        sink.configure(AudioSink.AudioSinkConfig.Builder(fmt).build())
        sink.markPendingUsbAttach()

        // sinkPlaying is false (paused): handleBuffer must NOT trigger normalSink.play() or reopen
        val buf = ByteBuffer.allocateDirect(64)
        assertFalse(sink.handleBuffer(buf, 0L, 1))
        assertEquals(0, delegate.playCalls)
        sink.release()
    }

    @Test
    fun `releasing old UnifiedUsbAudioSink after newer instance exists does not clobber currentInstance or usbActive`() {
        PlaybackCore.usbExclusiveMode = true
        val oldSink = UnifiedUsbAudioSink(context = null, normalSink = FakeDelegateAudioSink(), sessionApi = unusedSessionApi)
        val newSink = UnifiedUsbAudioSink(context = null, normalSink = FakeDelegateAudioSink(), sessionApi = unusedSessionApi)
        PlaybackCore.usbActive = true

        assertTrue(UnifiedUsbAudioSink.currentInstance === newSink)
        oldSink.release()
        assertTrue(UnifiedUsbAudioSink.currentInstance === newSink, "Old sink release must not clear newer currentInstance")
        assertTrue(PlaybackCore.usbActive, "Old sink release must not clear PlaybackCore.usbActive for new sink")

        newSink.release()
        assertEquals(null, UnifiedUsbAudioSink.currentInstance)
        assertFalse(PlaybackCore.usbActive)
    }
}
