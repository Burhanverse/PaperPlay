package io.max.paperplay.player

import com.sun.jna.Pointer
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Parallel silent decoder that feeds [AudioTap] on desktop (`MpvPlayerEngine` and `JvmPlayerEngine`)
 * synchronized to the main player's audible playback position.
 *
 * Only runs while playback is active and [AudioTap.wanted] is true, so CPU cost is zero when Standby is closed.
 * Does not touch speaker output or interfere with WASAPI exclusive bit-perfect playback.
 */
internal class DesktopAudioTapFeeder(private val sourceTag: String) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "desktop-tap-feeder").apply { isDaemon = true }
    }

    @Volatile private var released = false
    @Volatile private var trackMrl: String? = null
    @Volatile private var playing = false
    @Volatile private var mainPosMs = 0L
    @Volatile private var mainPosWallNs = 0L
    @Volatile private var speed = 1f
    @Volatile private var outputDelayMs = 0L

    private var tickTask: ScheduledFuture<*>? = null
    private var shadow: ShadowDecoder? = null
    private var shadowMrl: String? = null
    private var shadowPlaying = false
    private var lastSyncCheckNs = 0L

    fun onTrack(mrl: String?) {
        if (released) return
        val normalized = mrl?.takeIf { it.isNotBlank() }
        if (normalized == trackMrl) return
        trackMrl = normalized
        mainPosMs = 0L
        mainPosWallNs = System.nanoTime()
        AudioTap.reset()
        worker.execute {
            if (released) return@execute
            stopShadow()
            ensureTicking()
        }
    }

    fun onPlaying(isPlaying: Boolean, positionMs: Long, currentSpeed: Float, delayMs: Long = 0L) {
        if (released) return
        val now = System.nanoTime()
        playing = isPlaying
        mainPosMs = positionMs.coerceAtLeast(0L)
        mainPosWallNs = now
        speed = currentSpeed.coerceIn(0.25f, 4f)
        outputDelayMs = delayMs.coerceIn(-500L, 2000L)
        if (isPlaying) {
            AudioTap.setSpeed(speed)
            AudioTap.updatePlaybackClock(audiblePosUs(now), speed, now)
        } else {
            AudioTap.onPause()
        }
        worker.execute {
            if (released) return@execute
            evaluateState()
            ensureTicking()
        }
    }

    fun onPosition(positionMs: Long, currentSpeed: Float, delayMs: Long = 0L) {
        if (released) return
        val now = System.nanoTime()
        mainPosMs = positionMs.coerceAtLeast(0L)
        mainPosWallNs = now
        speed = currentSpeed.coerceIn(0.25f, 4f)
        outputDelayMs = delayMs.coerceIn(-500L, 2000L)
        if (playing) {
            AudioTap.updatePlaybackClock(audiblePosUs(now), speed, now)
        }
    }

    fun onSeek(positionMs: Long) {
        if (released) return
        val now = System.nanoTime()
        val safePos = positionMs.coerceAtLeast(0L)
        mainPosMs = safePos
        mainPosWallNs = now
        AudioTap.reset()
        if (playing) {
            AudioTap.updatePlaybackClock(audiblePosUs(now), speed, now)
        }
        worker.execute {
            if (released) return@execute
            shadow?.seek(safePos)
        }
    }

    fun onSpeed(newSpeed: Float) {
        if (released) return
        speed = newSpeed.coerceIn(0.25f, 4f)
        AudioTap.setSpeed(speed)
        worker.execute {
            if (released) return@execute
            shadow?.setRate(speed)
        }
    }

    fun onPause() {
        if (released) return
        playing = false
        AudioTap.onPause()
        worker.execute {
            if (released) return@execute
            shadow?.pause()
            shadowPlaying = false
        }
    }

    fun onStop() {
        if (released) return
        playing = false
        trackMrl = null
        AudioTap.reset()
        worker.execute {
            if (released) return@execute
            stopShadow()
            stopTicking()
        }
    }

    fun release() {
        if (released) return
        released = true
        playing = false
        trackMrl = null
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            worker.execute {
                try {
                    stopTicking()
                    stopShadow()
                } finally {
                    done.countDown()
                }
            }
            done.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            worker.shutdownNow()
        }
    }

    private fun audiblePosUs(nowNs: Long): Long {
        val elapsedMs = if (playing && mainPosWallNs > 0L) {
            ((nowNs - mainPosWallNs) / 1_000_000.0 * speed).toLong()
        } else 0L
        val posMs = (mainPosMs + elapsedMs - outputDelayMs).coerceAtLeast(0L)
        return posMs * 1_000L
    }

    private fun estimatedMainPosMs(nowNs: Long): Long {
        val elapsedMs = if (playing && mainPosWallNs > 0L) {
            ((nowNs - mainPosWallNs) / 1_000_000.0 * speed).toLong()
        } else 0L
        return (mainPosMs + elapsedMs).coerceAtLeast(0L)
    }

    private fun ensureTicking() {
        if (released) return
        if (trackMrl != null && playing) {
            if (tickTask == null || tickTask?.isDone == true) {
                tickTask = worker.scheduleAtFixedRate(
                    { runCatching { evaluateState() } },
                    100L,
                    150L,
                    TimeUnit.MILLISECONDS,
                )
            }
        } else {
            stopTicking()
        }
    }

    private fun stopTicking() {
        tickTask?.cancel(false)
        tickTask = null
    }

    private fun evaluateState() {
        if (released) return
        val mrl = trackMrl
        val want = playing && mrl != null && AudioTap.wanted
        if (!want) {
            if (shadowPlaying) {
                shadow?.pause()
                shadowPlaying = false
            }
            return
        }

        val now = System.nanoTime()
        AudioTap.updatePlaybackClock(audiblePosUs(now), speed, now)

        val targetMrl = mrl!!
        if (shadow == null || shadowMrl != targetMrl) {
            stopShadow()
            shadow = createShadowDecoder(targetMrl)
            shadowMrl = targetMrl
            shadow?.start(estimatedMainPosMs(now), speed)
            shadowPlaying = true
            lastSyncCheckNs = now
            return
        }

        val s = shadow ?: return
        if (!shadowPlaying) {
            s.resume(estimatedMainPosMs(now), speed)
            shadowPlaying = true
            lastSyncCheckNs = now
            return
        }

        s.poll()

        if (now - lastSyncCheckNs >= 350_000_000L) {
            lastSyncCheckNs = now
            val expectedMs = estimatedMainPosMs(now)
            val actualMs = s.currentPositionMs()
            if (actualMs >= 0L && abs(actualMs - expectedMs) > 120L) {
                s.seek(expectedMs)
            }
        }
    }

    private fun stopShadow() {
        shadow?.release()
        shadow = null
        shadowMrl = null
        shadowPlaying = false
    }

    private fun createShadowDecoder(mrl: String): ShadowDecoder? {
        return try {
            VlcAmemShadow(mrl, sourceTag)
        } catch (_: Throwable) {
            WavShadow.openOrNull(mrl, sourceTag)
        }
    }

    private interface ShadowDecoder {
        fun start(startPosMs: Long, speed: Float)
        fun pause()
        fun resume(posMs: Long, speed: Float)
        fun seek(posMs: Long)
        fun setRate(speed: Float)
        fun currentPositionMs(): Long
        fun poll() {}
        fun release()
    }

    /** Silent libvlc `amem` shadow decoder (44.1 kHz mono S16N). */
    private class VlcAmemShadow(
        private val mrl: String,
        private val sourceTag: String,
    ) : ShadowDecoder {
        private val factory: MediaPlayerFactory
        private val mp: MediaPlayer
        private var scratch = ShortArray(4096)
        @Volatile private var anchorPosMs = 0L
        @Volatile private var samplesSinceAnchor = 0L
        @Volatile private var paused = false

        init {
            val loaderArgs = VlcLoader.init()
            factory = MediaPlayerFactory("--no-video", "--aout=amem", *loaderArgs.toTypedArray())
            mp = factory.mediaPlayers().newMediaPlayer()
            mp.audio().callback(
                "S16N",
                SAMPLE_RATE,
                1,
                object : AudioCallbackAdapter() {
                    override fun play(mediaPlayer: MediaPlayer, samples: Pointer, sampleCount: Int, pts: Long) {
                        if (paused || sampleCount <= 0 || !AudioTap.wanted) return
                        if (sampleCount > scratch.size) {
                            scratch = ShortArray(sampleCount)
                        }
                        samples.read(0, scratch, 0, sampleCount)
                        val ptsUs = (anchorPosMs.coerceAtLeast(0L) * 1_000L) +
                            (samplesSinceAnchor * 1_000_000L) / SAMPLE_RATE
                        AudioTap.pushMonoInt16(SAMPLE_RATE, scratch, sampleCount, ptsUs, sourceTag)
                        samplesSinceAnchor += sampleCount
                    }

                    override fun flush(mediaPlayer: MediaPlayer, pts: Long) {
                        samplesSinceAnchor = 0L
                    }
                },
            )
        }

        override fun start(startPosMs: Long, speed: Float) {
            paused = false
            anchorPosMs = startPosMs.coerceAtLeast(0L)
            samplesSinceAnchor = 0L
            mp.media().play(mrl, ":network-caching=150", ":file-caching=150")
            if (speed != 1f) mp.controls().setRate(speed)
            if (startPosMs > 100L) mp.controls().setTime(startPosMs)
        }

        override fun pause() {
            paused = true
            runCatching { mp.controls().setPause(true) }
        }

        override fun resume(posMs: Long, speed: Float) {
            paused = false
            anchorPosMs = posMs.coerceAtLeast(0L)
            samplesSinceAnchor = 0L
            runCatching {
                mp.controls().setRate(speed)
                mp.controls().setPause(false)
                mp.controls().setTime(posMs)
            }
        }

        override fun seek(posMs: Long) {
            anchorPosMs = posMs.coerceAtLeast(0L)
            samplesSinceAnchor = 0L
            runCatching { mp.controls().setTime(posMs) }
        }

        override fun setRate(speed: Float) {
            runCatching { mp.controls().setRate(speed) }
        }

        override fun currentPositionMs(): Long =
            anchorPosMs + (samplesSinceAnchor * 1_000L) / SAMPLE_RATE

        override fun release() {
            paused = true
            runCatching { mp.controls().stop() }
            runCatching { mp.release() }
            runCatching { factory.release() }
        }

        private companion object {
            const val SAMPLE_RATE = 44100
        }
    }

    /** Clean-room local PCM WAV shadow decoder fallback when libvlc is unavailable. */
    private class WavShadow private constructor(
        private val raf: RandomAccessFile,
        private val dataOffset: Long,
        private val dataBytes: Long,
        private val sampleRate: Int,
        private val channels: Int,
        private val bitsPerSample: Int,
        private val isFloat: Boolean,
        private val sourceTag: String,
    ) : ShadowDecoder {
        private val frameBytes = (bitsPerSample / 8) * channels
        private val totalFrames = dataBytes / frameBytes
        private val buf = ByteArray(4096 * frameBytes.coerceAtLeast(4))
        private val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        private var currentFrame = 0L
        private var lastPollNs = 0L
        private var speed = 1f
        private var paused = false

        override fun start(startPosMs: Long, speed: Float) {
            this.speed = speed
            paused = false
            lastPollNs = System.nanoTime()
            seek(startPosMs)
        }

        override fun pause() {
            paused = true
        }

        override fun resume(posMs: Long, speed: Float) {
            this.speed = speed
            paused = false
            lastPollNs = System.nanoTime()
            seek(posMs)
        }

        override fun seek(posMs: Long) {
            currentFrame = ((posMs.coerceAtLeast(0L) * sampleRate) / 1000L).coerceIn(0L, totalFrames)
            lastPollNs = System.nanoTime()
        }

        override fun setRate(speed: Float) {
            this.speed = speed
        }

        override fun currentPositionMs(): Long = (currentFrame * 1000L) / sampleRate

        override fun poll() {
            if (paused || !AudioTap.wanted || currentFrame >= totalFrames) return
            val now = System.nanoTime()
            val dtSec = ((now - lastPollNs) / 1e9).coerceIn(0.005, 0.25)
            lastPollNs = now
            val wantFrames = ((dtSec * sampleRate * speed).toLong()).coerceIn(1L, 4096L)
            val availFrames = (totalFrames - currentFrame).coerceAtMost(wantFrames).toInt()
            if (availFrames <= 0) return

            val bytePos = dataOffset + currentFrame * frameBytes
            val toRead = minOf(availFrames * frameBytes, buf.size)
            raf.seek(bytePos)
            val read = raf.read(buf, 0, toRead)
            if (read < frameBytes) return
            val readFrames = read / frameBytes
            bb.limit(readFrames * frameBytes).position(0)
            val pcmKind = when {
                isFloat -> PcmTapReader.PCM_FLOAT
                bitsPerSample == 16 -> PcmTapReader.PCM_16
                bitsPerSample == 24 -> PcmTapReader.PCM_24
                else -> PcmTapReader.PCM_32
            }
            val ptsUs = (currentFrame * 1_000_000L) / sampleRate
            PcmTapReader.feedPcmSlice(bb, 0, readFrames * frameBytes, sampleRate, channels, pcmKind, ptsUs, sourceTag)
            currentFrame += readFrames
        }

        override fun release() {
            runCatching { raf.close() }
        }

        companion object {
            fun openOrNull(mrl: String, sourceTag: String): WavShadow? {
                val file = runCatching {
                    if (mrl.startsWith("file:", ignoreCase = true)) File(URI(mrl)) else File(mrl)
                }.getOrNull() ?: return null
                if (!file.isFile) return null
                val raf = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
                return try {
                    val hdr = ByteArray(12)
                    if (raf.read(hdr) != 12) { raf.close(); return null }
                    if (String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") { raf.close(); return null }
                    var fmtTag = 1
                    var ch = 2
                    var rate = 44100
                    var bits = 16
                    var dataOffset = -1L
                    var dataSize = 0L
                    val chunkHdr = ByteArray(8)
                    val chBb = ByteBuffer.wrap(chunkHdr).order(ByteOrder.LITTLE_ENDIAN)
                    while (raf.filePointer + 8 <= raf.length()) {
                        if (raf.read(chunkHdr) != 8) break
                        val id = String(chunkHdr, 0, 4)
                        val size = chBb.getInt(4).toLong() and 0xFFFFFFFFL
                        if (id == "fmt " && size >= 16) {
                            val fmt = ByteArray(16)
                            raf.readFully(fmt)
                            val fb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            fmtTag = fb.getShort(0).toInt() and 0xFFFF
                            ch = fb.getShort(2).toInt() and 0xFFFF
                            rate = fb.getInt(4)
                            bits = fb.getShort(14).toInt() and 0xFFFF
                            if (size > 16) raf.seek(raf.filePointer + size - 16)
                        } else if (id == "data") {
                            dataOffset = raf.filePointer
                            dataSize = minOf(size, raf.length() - dataOffset)
                            break
                        } else {
                            raf.seek(raf.filePointer + size + (size and 1L))
                        }
                    }
                    if (dataOffset < 0 || rate <= 0 || ch <= 0 || (fmtTag != 1 && fmtTag != 3)) {
                        raf.close()
                        return null
                    }
                    WavShadow(raf, dataOffset, dataSize, rate, ch, bits, fmtTag == 3, sourceTag)
                } catch (_: Throwable) {
                    runCatching { raf.close() }
                    null
                }
            }
        }
    }
}
