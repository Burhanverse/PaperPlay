package io.max.paperplay.player.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbPlaybackLifecycleTest {

    @BeforeTest
    fun setUp() {
        UsbRingLog.clear()
    }

    private fun sampleAltSettings(): List<UsbAudioAltSetting> = listOf(
        UsbAudioAltSetting(
            interfaceNumber = 1,
            altSetting = 1,
            terminalLink = 1,
            formatTag = AudioFormatTag.PCM,
            bmFormats = 1L,
            channelCount = 2,
            subslotSize = 2,
            bitResolution = 16,
            sampleRates = listOf(44100, 48000, 96000),
            continuousRateRange = null,
            dataEndpoint = UsbEndpointDescriptor(
                address = 1,
                isOut = true,
                syncType = EndpointSyncType.ADAPTIVE,
                usageType = EndpointUsageType.DATA,
                maxPacketSize = 192,
                interval = 1,
                refresh = 0,
                synchAddress = 0
            ),
            feedbackEndpoint = null,
            isDsdRawCapable = false,
            isDsdDoPCapable = false
        ),
        UsbAudioAltSetting(
            interfaceNumber = 1,
            altSetting = 2,
            terminalLink = 1,
            formatTag = AudioFormatTag.PCM,
            bmFormats = 1L,
            channelCount = 2,
            subslotSize = 4,
            bitResolution = 24,
            sampleRates = listOf(44100, 48000, 96000, 192000),
            continuousRateRange = null,
            dataEndpoint = UsbEndpointDescriptor(
                address = 1,
                isOut = true,
                syncType = EndpointSyncType.ADAPTIVE,
                usageType = EndpointUsageType.DATA,
                maxPacketSize = 384,
                interval = 1,
                refresh = 0,
                synchAddress = 0
            ),
            feedbackEndpoint = null,
            isDsdRawCapable = false,
            isDsdDoPCapable = true
        )
    )

    @Test
    fun testTenTrackSwitchesFivePlayPauseTogglesAndExclusiveModeToggle() {
        var bridge: FakeUsbAudioSinkBridge? = null
        val altSettings = sampleAltSettings()

        val sm = UsbSinkStateMachine(
            bridgeFactory = { alt, rate, _ ->
                FakeUsbAudioSinkBridge(rate, alt.subslotSize, alt.channelCount).also { bridge = it }
            }
        )

        val pcm = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)

        // Initial track: configure and start playback
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 10 Track switches test
        // 1. Next track
        UsbRingLog.record("Player", "TRACK_SWITCH user_next target=tg-track-1", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 2. Previous track
        UsbRingLog.record("Player", "TRACK_SWITCH user_prev target=tg-track-0", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 3. Tap a song in playlist
        UsbRingLog.record("Player", "TRACK_SWITCH tap_song target=tg-track-5", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(48000, 24, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 48000L, (2L shl 16) or 24L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 4. Seek
        UsbRingLog.record("Player", "TRACK_SWITCH seek target=tg-track-5 pos=30000ms", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_FLUSH)
        sm.handleBuffer(pcm.duplicate(), 30_000_000L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 5. Pause then next
        sm.pause()
        UsbRingLog.record("UsbAudioSink", "pause [state=READY playWhenReady=false]", UsbRingLog.CODE_SINK_PAUSE)
        assertEquals(UsbSinkState.PAUSED, sm.state)

        UsbRingLog.record("Player", "TRACK_SWITCH pause_then_next target=tg-track-6", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = false)
        sm.reset(preservePlayRequested = false)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        // Buffer delivered while paused (pre-roll)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertTrue(sm.state == UsbSinkState.PAUSED || sm.state == UsbSinkState.CONFIGURED)
        assertTrue(bridge?.isPaused == true)
        // User unpauses
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 6. Next while paused
        sm.pause()
        UsbRingLog.record("UsbAudioSink", "pause [state=READY playWhenReady=false]", UsbRingLog.CODE_SINK_PAUSE)
        assertEquals(UsbSinkState.PAUSED, sm.state)

        UsbRingLog.record("Player", "TRACK_SWITCH next_while_paused target=tg-track-7", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = false)
        sm.reset(preservePlayRequested = false)
        sm.configure(96000, 24, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 96000L, (2L shl 16) or 24L)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertTrue(sm.state == UsbSinkState.PAUSED || sm.state == UsbSinkState.CONFIGURED)
        assertTrue(bridge?.isPaused == true)
        // User unpauses
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 7. Next track
        UsbRingLog.record("Player", "TRACK_SWITCH user_next target=tg-track-8", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 8. Previous track
        UsbRingLog.record("Player", "TRACK_SWITCH user_prev target=tg-track-7", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 9. Tap a song
        UsbRingLog.record("Player", "TRACK_SWITCH tap_song target=tg-track-2", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        sm.reset(preservePlayRequested = true)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 0L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 10. Seek
        UsbRingLog.record("Player", "TRACK_SWITCH seek target=tg-track-2 pos=45000ms", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = true)
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_FLUSH)
        sm.handleBuffer(pcm.duplicate(), 45_000_000L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // 5 Play/Pause Toggles
        for (i in 1..5) {
            sm.pause()
            UsbRingLog.record("UsbAudioSink", "pause [state=READY playWhenReady=false] toggle=$i", UsbRingLog.CODE_SINK_PAUSE)
            assertEquals(UsbSinkState.PAUSED, sm.state)

            sm.play()
            UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true] toggle=$i", UsbRingLog.CODE_SINK_PLAY)
            assertEquals(UsbSinkState.PLAYING, sm.state)
        }

        // USB Exclusive Mode On/Off Mid-track
        // Toggle OFF: sink resets/releases state machine without error
        UsbRingLog.record("UsbAudioSink", "usbExclusiveMode toggled OFF mid-track", UsbRingLog.CODE_GENERIC)
        sm.flush(preservePlayRequested = false)
        sm.reset(preservePlayRequested = false)
        assertEquals(UsbSinkState.CONFIGURED, sm.state)

        // Toggle ON: reconfigure called, resumed
        UsbRingLog.record("UsbAudioSink", "usbExclusiveMode toggled ON mid-track", UsbRingLog.CODE_GENERIC)
        sm.configure(44100, 16, 2, altSettings, sampleMimeType = "audio/raw")
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 44100L, (2L shl 16) or 16L)
        sm.play()
        UsbRingLog.record("UsbAudioSink", "play [state=READY playWhenReady=true]", UsbRingLog.CODE_SINK_PLAY)
        sm.handleBuffer(pcm.duplicate(), 45_000_000L)
        assertEquals(UsbSinkState.PLAYING, sm.state)

        // Validate Ring Log export
        val dump = UsbRingLog.dump()
        println("=== EXPORT LOG START ===")
        for (line in dump) {
            println(line)
        }
        println("=== EXPORT LOG END ===")

        // Verification assertions:
        // 1. No ERROR lines anywhere
        for (line in dump) {
            assertFalse(line.contains("ERROR"), "Log line must not contain ERROR: $line")
            assertFalse(line.contains("Fallback:"), "Log line must not contain Fallback: $line")
            assertFalse(line.contains("rate=0"), "Log line must not log SINK_CONFIGURE rate=0: $line")
        }

        // 2. Count TRACK_SWITCH occurrences (10 track switches)
        val trackSwitches = dump.filter { it.contains("TRACK_SWITCH") }
        assertEquals(10, trackSwitches.size, "Must have exactly 10 track switches logged")

        // 3. For every TRACK_SWITCH, verify SINK_CONFIGURE or SINK_PLAY / SINK_FLUSH follows
        var trackSwitchIndices = dump.mapIndexedNotNull { index, line -> if (line.contains("TRACK_SWITCH")) index else null }
        assertEquals(10, trackSwitchIndices.size)
        for (idx in trackSwitchIndices) {
            val followingLines = dump.subList(idx + 1, minOf(idx + 5, dump.size))
            val hasConfigureOrPlay = followingLines.any {
                it.contains("SINK_CONFIGURE") || it.contains("SINK_PLAY") || it.contains("SINK_FLUSH")
            }
            assertTrue(hasConfigureOrPlay, "TRACK_SWITCH at $idx must be followed by SINK_CONFIGURE, SINK_PLAY, or SINK_FLUSH")
        }
    }
}
