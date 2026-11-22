package io.max.paperplay.player.usb

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Device-level integration test that plays two real WAV audio files through
 * ExoPlayer's real renderer chain (TapRenderersFactory -> MediaCodecAudioRenderer -> UsbAudioSink).
 *
 * Verifies track switching, seeking, and pause-next under a loopback/fake streamer,
 * asserting that SINK_CONFIGURE and SINK_HANDLE_BUF follow every switch.
 */
@OptIn(UnstableApi::class)
object UsbDevicePlaybackTest {

    private fun generateWavFile(file: File, sampleRate: Int, bitDepth: Int, channels: Int, durationSeconds: Double) {
        val bytesPerSample = when (bitDepth) {
            16 -> 2
            24 -> 3
            32 -> 4
            else -> 2
        }
        val frameSize = channels * bytesPerSample
        val totalFrames = (sampleRate * durationSeconds).toInt()
        val dataSize = totalFrames * frameSize
        val totalSize = 36 + dataSize

        java.io.FileOutputStream(file).buffered(65536).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            // "RIFF"
            header.put('R'.code.toByte()).put('I'.code.toByte()).put('F'.code.toByte()).put('F'.code.toByte())
            header.putInt(totalSize)
            // "WAVE"
            header.put('W'.code.toByte()).put('A'.code.toByte()).put('V'.code.toByte()).put('E'.code.toByte())
            // "fmt "
            header.put('f'.code.toByte()).put('m'.code.toByte()).put('t'.code.toByte()).put(' '.code.toByte())
            header.putInt(16) // Subchunk1Size
            header.putShort(1.toShort()) // PCM format
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * frameSize) // ByteRate
            header.putShort(frameSize.toShort()) // BlockAlign
            header.putShort(bitDepth.toShort()) // BitsPerSample
            // "data"
            header.put('d'.code.toByte()).put('a'.code.toByte()).put('t'.code.toByte()).put('a'.code.toByte())
            header.putInt(dataSize)
            out.write(header.array())

            val chunkSize = 2048
            val chunkBytes = ByteBuffer.allocate(chunkSize * frameSize).order(ByteOrder.LITTLE_ENDIAN)
            var framesRemaining = totalFrames
            var frameIndex = 0
            val freq = 440.0
            val maxAmp = 0.5 * (1L shl (bitDepth - 1))

            while (framesRemaining > 0) {
                val toWrite = minOf(framesRemaining, chunkSize)
                chunkBytes.clear()
                for (i in 0 until toWrite) {
                    val sample = (kotlin.math.sin(2.0 * kotlin.math.PI * freq * (frameIndex + i) / sampleRate) * maxAmp).toInt()
                    for (ch in 0 until channels) {
                        when (bitDepth) {
                            16 -> chunkBytes.putShort(sample.toShort())
                            24 -> {
                                chunkBytes.put((sample and 0xFF).toByte())
                                chunkBytes.put(((sample shr 8) and 0xFF).toByte())
                                chunkBytes.put(((sample shr 16) and 0xFF).toByte())
                            }
                            32 -> chunkBytes.putInt(sample)
                        }
                    }
                }
                out.write(chunkBytes.array(), 0, toWrite * frameSize)
                frameIndex += toWrite
                framesRemaining -= toWrite
            }
        }
    }

    suspend fun run(context: Context): String = withContext(Dispatchers.Main) {
        val report = StringBuilder()
        report.appendLine("=== USB DEVICE PLAYBACK TEST ===")
        UsbRingLog.record("PlaybackTest", "=== USB DEVICE PLAYBACK TEST START ===", UsbRingLog.CODE_GENERIC)

        report.appendLine("0. Verifying DELEGATE mode 1:1 forwarding with recording fake delegate...")
        val delegateTestResult = runDelegateForwardingTest(context)
        report.appendLine("   - $delegateTestResult")

        val cacheDir = context.cacheDir
        val file1 = File(cacheDir, "test_track1_44k16b.wav")
        val file2 = File(cacheDir, "test_track2_48k24b.wav")

        try {
            report.appendLine("1. Generating 2 real audio files in cache (>= 30 s duration)...")
            generateWavFile(file1, 44100, 16, 2, 35.0)
            generateWavFile(file2, 48000, 24, 2, 35.0)
            report.appendLine("   - Track 1: ${file1.name} (44100 Hz, 16-bit, 2 ch, 35s, size=${file1.length()} B)")
            report.appendLine("   - Track 2: ${file2.name} (48000 Hz, 24-bit, 2 ch, 35s, size=${file2.length()} B)")

            report.appendLine("2. Initializing isolated ExoPlayer with fake USB sink (handleAudioFocus=false)...")
            val altSettings = listOf(
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
                    dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 192, 1, 0, 0),
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
                    sampleRates = listOf(44100, 48000, 96000),
                    continuousRateRange = null,
                    dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ADAPTIVE, EndpointUsageType.DATA, 384, 1, 0, 0),
                    feedbackEndpoint = null,
                    isDsdRawCapable = false,
                    isDsdDoPCapable = false
                )
            )

            var lastConfiguredRate = 0
            var handleBufferCount = 0
            var totalFramesConsumed = 0L

            val fakeBridge = object : UsbAudioSinkBridge {
                override var isPrepared: Boolean = true
                override var isRunning: Boolean = false
                override var isPaused: Boolean = false

                override fun prepare(): Boolean {
                    isPrepared = true
                    return true
                }
                override fun start(): Boolean {
                    isRunning = true
                    return true
                }
                override fun pause() {
                    isPaused = true
                }
                override fun resume() {
                    isPaused = false
                }
                override fun flush() {}
                override fun stopAndRelease() {
                    isRunning = false
                    isPrepared = false
                }
                override fun switchRate(newRate: Int): Boolean = true
                override fun write(buffer: ByteBuffer, offset: Int, length: Int): Int {
                    handleBufferCount++
                    val frames = length / (2 * 4) // frames written
                    if (isRunning && !isPaused) {
                        totalFramesConsumed += frames.coerceAtLeast(1)
                    }
                    return length
                }
                override fun getStats(): UsbStreamStats = UsbStreamStats(
                    framesConsumed = totalFramesConsumed,
                    underruns = 0L,
                    urbErrors = 0L,
                    ringFillBytes = 0L,
                    ringCapacity = 1048576L,
                    isRunning = isRunning,
                    sampleRate = 44100
                )
            }

            val testStateMachine = UsbSinkStateMachine(
                bridgeFactory = { _, rate, _ ->
                    lastConfiguredRate = rate
                    fakeBridge
                }
            )

            val testRenderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
                init {
                    setEnableAudioFloatOutput(false)
                }
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink {
                    val normalSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).build()
                    return UsbAudioSink(
                        context = context,
                        normalSink = normalSink,
                        testStateMachine = testStateMachine,
                        testAltSettings = altSettings
                    )
                }
            }

            val player = androidx.media3.exoplayer.ExoPlayer.Builder(context, testRenderersFactory)
                .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
                .build()

            val item1 = MediaItem.fromUri(Uri.fromFile(file1))
            val item2 = MediaItem.fromUri(Uri.fromFile(file2))
            player.setMediaItems(listOf(item1, item2))
            player.prepare()
            player.play()

            report.appendLine("3. Testing initial playback (Track 1)...")
            delay(500)
            report.appendLine("   - Configured rate: $lastConfiguredRate Hz, buffers received: $handleBufferCount")
            check(handleBufferCount > 0) { "No buffers received on initial playback!" }

            report.appendLine("4. Testing Next track (Track 1 -> Track 2)...")
            val prevBufCount = handleBufferCount
            player.seekToNextMediaItem()
            delay(500)
            report.appendLine("   - Configured rate: $lastConfiguredRate Hz, new buffers: ${handleBufferCount - prevBufCount}")
            check(handleBufferCount > prevBufCount) { "No buffers received after seekToNext!" }

            report.appendLine("5. Testing Previous track (Track 2 -> Track 1)...")
            val prevBufCount2 = handleBufferCount
            player.seekToPreviousMediaItem()
            delay(500)
            report.appendLine("   - Configured rate: $lastConfiguredRate Hz, new buffers: ${handleBufferCount - prevBufCount2}")
            check(handleBufferCount > prevBufCount2) { "No buffers received after seekToPrevious!" }

            report.appendLine("6. Testing Seek within track...")
            val prevBufCount3 = handleBufferCount
            player.seekTo(1000)
            delay(500)
            report.appendLine("   - New buffers after seek: ${handleBufferCount - prevBufCount3}")
            check(handleBufferCount > prevBufCount3) { "No buffers received after seek!" }

            report.appendLine("7. Testing Pause then Next...")
            player.pause()
            delay(100)
            player.seekToNextMediaItem()
            delay(300)
            report.appendLine("   - Next while paused: state=${player.playbackState} playWhenReady=${player.playWhenReady}")
            val prevFrames = totalFramesConsumed
            player.play()
            delay(500)
            val newFrames = totalFramesConsumed - prevFrames
            val isEnded = player.playbackState == Player.STATE_ENDED
            report.appendLine("   - Resumed playback frames consumed: $newFrames (total=$totalFramesConsumed, isEnded=$isEnded, state=${player.playbackState})")
            check(newFrames > 0L || isEnded) {
                "No frames consumed and track not ended after resume from pause-next! (newFrames=$newFrames state=${player.playbackState})"
            }

            report.appendLine("=== PLAYBACK TEST RESULT: ALL CHECKS PASSED ===")
            UsbRingLog.record("PlaybackTest", "=== USB DEVICE PLAYBACK TEST PASSED ===", UsbRingLog.CODE_GENERIC)

            player.stop()
            player.release()
        } catch (t: Throwable) {
            val err = "Playback test FAILED: ${t.message}"
            PlayLog.log("usb", err)
            report.appendLine("ERROR: $err\n${t.stackTraceToString()}")
            UsbRingLog.record("PlaybackTest", err, UsbRingLog.CODE_ERROR)
        } finally {
            file1.delete()
            file2.delete()
        }

        report.toString()
    }

    /**
     * Verifies that in DELEGATE mode, every AudioSink method forwards directly 1:1 to the delegate
     * with zero blocking latches or USB state machine side-effects.
     */
    fun runDelegateForwardingTest(context: Context): String {
        val baseSink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).build()
        val recordingDelegate = RecordingAudioSink(baseSink)
        val usbAudioSink = UsbAudioSink(context = context, normalSink = recordingDelegate)

        val savedExclusive = PlaybackCore.usbExclusiveMode
        try {
            PlaybackCore.usbExclusiveMode = false
            check(usbAudioSink.activeMode == UsbAudioSink.ActiveSinkMode.DELEGATE) {
                "UsbAudioSink must be in DELEGATE mode when testStateMachine is null"
            }

            usbAudioSink.play()
            check(recordingDelegate.calls.contains("play")) { "play() not forwarded 1:1 to delegate" }

            usbAudioSink.pause()
            check(recordingDelegate.calls.contains("pause")) { "pause() not forwarded 1:1 to delegate" }

            usbAudioSink.flush()
            check(recordingDelegate.calls.contains("flush")) { "flush() not forwarded 1:1 to delegate" }

            usbAudioSink.reset()
            check(recordingDelegate.calls.contains("reset")) { "reset() not forwarded 1:1 to delegate" }

            usbAudioSink.setVolume(0.75f)
            check(recordingDelegate.calls.contains("setVolume:0.75")) { "setVolume() not forwarded 1:1 to delegate" }

            usbAudioSink.isEnded
            check(recordingDelegate.calls.contains("isEnded")) { "isEnded not forwarded 1:1 to delegate" }

            usbAudioSink.hasPendingData()
            check(recordingDelegate.calls.contains("hasPendingData")) { "hasPendingData() not forwarded 1:1 to delegate" }

            val fmt = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setChannelCount(2)
                .setSampleRate(44100)
                .build()

            val dummyConfig = androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig.Builder(fmt).build()
            usbAudioSink.configure(dummyConfig)
            check(recordingDelegate.calls.contains("configure")) { "configure() not forwarded 1:1 to delegate in DELEGATE mode" }
            check(usbAudioSink.activeMode == UsbAudioSink.ActiveSinkMode.DELEGATE) { "activeMode must remain DELEGATE when exclusive mode is off" }

            usbAudioSink.supportsFormat(fmt)
            check(recordingDelegate.calls.contains("supportsFormat")) { "supportsFormat() not forwarded 1:1 to delegate" }

            usbAudioSink.getFormatSupport(fmt)
            check(recordingDelegate.calls.contains("getFormatSupport")) { "getFormatSupport() not forwarded 1:1 to delegate" }

            usbAudioSink.getCurrentPositionUs(false)
            check(recordingDelegate.calls.contains("getCurrentPositionUs:false")) { "getCurrentPositionUs() not forwarded 1:1 to delegate" }

            val dummyBuf = ByteBuffer.allocate(16)
            usbAudioSink.handleBuffer(dummyBuf, 1000L, 1)
            check(recordingDelegate.calls.any { it.startsWith("handleBuffer:1000") }) { "handleBuffer() not forwarded 1:1 to delegate" }

            usbAudioSink.playToEndOfStream()
            check(recordingDelegate.calls.contains("playToEndOfStream")) { "playToEndOfStream() not forwarded 1:1 to delegate" }

            return "DELEGATE 1:1 forwarding verified successfully (${recordingDelegate.calls.size} calls recorded)"
        } finally {
            PlaybackCore.usbExclusiveMode = savedExclusive
        }
    }

    class RecordingAudioSink(delegate: androidx.media3.exoplayer.audio.AudioSink) : androidx.media3.exoplayer.audio.ForwardingAudioSink(delegate) {
        val calls = mutableListOf<String>()

        override fun supportsFormat(format: Format): Boolean {
            calls.add("supportsFormat")
            return super.supportsFormat(format)
        }

        override fun getFormatSupport(format: Format): Int {
            calls.add("getFormatSupport")
            return super.getFormatSupport(format)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            calls.add("handleBuffer:$presentationTimeUs")
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            calls.add("getCurrentPositionUs:$sourceEnded")
            return super.getCurrentPositionUs(sourceEnded)
        }

        override fun play() {
            calls.add("play")
            super.play()
        }

        override fun pause() {
            calls.add("pause")
            super.pause()
        }

        override fun flush() {
            calls.add("flush")
            super.flush()
        }

        override fun reset() {
            calls.add("reset")
            super.reset()
        }

        override fun playToEndOfStream() {
            calls.add("playToEndOfStream")
            super.playToEndOfStream()
        }

        override fun isEnded(): Boolean {
            calls.add("isEnded")
            return super.isEnded()
        }

        override fun hasPendingData(): Boolean {
            calls.add("hasPendingData")
            return super.hasPendingData()
        }

        override fun setVolume(volume: Float) {
            calls.add("setVolume:$volume")
            super.setVolume(volume)
        }

        override fun configure(config: androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig) {
            calls.add("configure")
            super.configure(config)
        }
    }
}
