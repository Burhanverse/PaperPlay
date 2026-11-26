package io.max.paperplay.ui.app

import io.max.paperplay.player.AudioTap
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandbyVisualTest {
    private var nowNs = 3_000_000_000_000L

    @BeforeTest
    fun setUp() {
        nowNs = 3_000_000_000_000L
        AudioTap.clockNs = { nowNs }
        AudioTap.reset()
    }

    @AfterTest
    fun tearDown() {
        AudioTap.reset()
        AudioTap.clockNs = { System.nanoTime() }
    }

    @Test
    fun idleOrSilentTapGeneratesZeroInventedMotion() {
        val sm = Smoother()
        val initialDrop = sm.drop.copyOf()

        // 1. No audio at all -> 120 frames must leave bars, bass, energy, onset, ring, motionPhase, drops, particles completely still
        repeat(120) {
            nowNs += 16_000_000L
            sm.update(0.016f, nowNs)
        }
        assertFalse(sm.liveSignal)
        assertEquals(0f, sm.energy)
        assertEquals(0f, sm.bass)
        assertEquals(0f, sm.onset)
        assertEquals(0f, sm.ring)
        assertEquals(0f, sm.motionPhase)
        assertTrue(sm.v.all { it == 0f })
        assertTrue(sm.drop.contentEquals(initialDrop))
        assertTrue(sm.pl.all { it <= 0f })

        // 2. Digital silence pushed -> must also stay at zero motion
        repeat(4096) { AudioTap.push(44100, 0f) }
        repeat(60) {
            nowNs += 16_000_000L
            sm.update(0.016f, nowNs)
        }
        assertFalse(sm.liveSignal)
        assertEquals(0f, sm.energy)
        assertEquals(0f, sm.motionPhase)
        assertEquals(0f, sm.ring)
    }

    @Test
    fun liveBassKickMovesBarsTriggersOnsetAndSettlesOnPause() {
        val sm = Smoother()
        sm.update(0.016f, nowNs) // marks AudioTap.wanted = true

        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        for (i in 0 until 4096) {
            val ptsUs = (i * 1_000_000L) / rate
            AudioTap.pushWithPts(rate, 0.7f * sin(2 * PI * 70.0 * i / rate).toFloat(), ptsUs, "ui-test")
        }

        nowNs += 16_000_000L
        sm.update(0.016f, nowNs)
        assertTrue(sm.liveSignal, "live bass kick must activate liveSignal")
        assertTrue(sm.bass > 0.2f, "bass should rise on 70 Hz kick, got ${sm.bass}")
        assertTrue(sm.onset > 0.2f, "onset should trigger on sudden kick, got ${sm.onset}")
        assertTrue(sm.motionPhase > 0f)

        // Pause -> settles cleanly back to zero
        AudioTap.onPause()
        repeat(90) {
            nowNs += 16_000_000L
            sm.update(0.016f, nowNs)
        }
        assertFalse(sm.liveSignal)
        assertTrue(sm.energy < 0.01f, "paused smoother should decay to zero, got ${sm.energy}")
        assertEquals(0f, sm.onset)
    }

    @Test
    fun rhythmFallbackClassIsDeletedFromStandbyVisual() {
        val file = listOf(
            File("src/commonMain/kotlin/io/max/paperplay/ui/app/StandbyVisual.kt"),
            File("ui/src/commonMain/kotlin/io/max/paperplay/ui/app/StandbyVisual.kt"),
        ).first { it.exists() }
        val text = file.readText()
        assertFalse(text.contains("class Rhythm"), "dead Rhythm fallback must remain deleted")
    }

    @Test
    fun styleProfilesDifferentiateCrispVsFluidAndSyncOffsetClamps() {
        val bars = Smoother()
        val orb = Smoother()
        bars.update(0.016f, nowNs, StandbyStyle.Bars)
        orb.update(0.016f, nowNs, StandbyStyle.Orb)

        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        for (i in 0 until 4096) {
            val ptsUs = (i * 1_000_000L) / rate
            AudioTap.pushWithPts(rate, 0.7f * sin(2 * PI * 100.0 * i / rate).toFloat(), ptsUs, "style-test")
        }
        nowNs += 16_000_000L
        bars.update(0.016f, nowNs, StandbyStyle.Bars)
        orb.update(0.016f, nowNs, StandbyStyle.Orb)
        assertTrue(bars.energy > orb.energy, "Bars should react faster than Orb: bars=${bars.energy}, orb=${orb.energy}")

        val prefs = StandbyPrefs({ null }, { _, _ -> })
        prefs.putSyncOffsetMs(350)
        assertEquals(200, prefs.syncOffsetMs)
        assertEquals(200L, AudioTap.calibrationOffsetMs)
        prefs.putSyncOffsetMs(-300)
        assertEquals(-200, prefs.syncOffsetMs)
        assertEquals(-200L, AudioTap.calibrationOffsetMs)
    }

    @Test
    fun smoothingIsFrameRateInvariantAcross60_90_120_144Hz() {
        val rate = 44100
        fun runAtHz(hz: Int): Pair<FloatArray, FloatArray> {
            AudioTap.reset()
            val sm = Smoother()
            sm.update(1f / hz, nowNs, StandbyStyle.Bars)
            AudioTap.updatePlaybackClock(0L, 1f, nowNs)
            for (i in 0 until rate / 2) {
                val ptsUs = (i * 1_000_000L) / rate
                val s = 0.6f * sin(2 * PI * 220.0 * i / rate).toFloat() + 0.35f * sin(2 * PI * 1800.0 * i / rate).toFloat()
                AudioTap.pushWithPts(rate, s, ptsUs, "fps-$hz")
            }
            val stepNs = 1_000_000_000L / hz
            val dt = 1f / hz
            var t = nowNs
            val frames = (0.10f * hz).toInt()
            repeat(frames) {
                t += stepNs
                sm.update(dt, t, StandbyStyle.Bars)
            }
            return sm.v.copyOf() to sm.v64.copyOf()
        }

        val (v60, v64_60) = runAtHz(60)
        val (v90, _) = runAtHz(90)
        val (v120, _) = runAtHz(120)
        val (v144, v64_144) = runAtHz(144)

        for (i in v60.indices) {
            assertTrue(kotlin.math.abs(v60[i] - v90[i]) < 0.03f, "60 vs 90 mismatch at $i: ${v60[i]} vs ${v90[i]}")
            assertTrue(kotlin.math.abs(v60[i] - v120[i]) < 0.03f, "60 vs 120 mismatch at $i: ${v60[i]} vs ${v120[i]}")
            assertTrue(kotlin.math.abs(v60[i] - v144[i]) < 0.03f, "60 vs 144 mismatch at $i: ${v60[i]} vs ${v144[i]}")
        }
        for (i in v64_60.indices) {
            assertTrue(kotlin.math.abs(v64_60[i] - v64_144[i]) < 0.03f, "v64 60 vs 144 mismatch at $i")
        }
    }

    @Test
    fun catmullRom64BandsAndGravityPeaksBehaveCleanlyAndStayStillInSilence() {
        val sm = Smoother()
        // Silence stays strictly 0
        repeat(30) {
            nowNs += 8_333_333L
            sm.update(1f / 120f, nowNs, StandbyStyle.Bars)
        }
        assertTrue(sm.v64.all { it == 0f })
        assertTrue(sm.peak64.all { it == 0f })
        assertEquals(0f, sm.bassPunch)

        // Drive live 440 Hz tone, then pause and verify ballistic peak64 hold + gravity fall
        AudioTap.updatePlaybackClock(0L, 1f, nowNs)
        val rate = 44100
        for (i in 0 until 4096) {
            AudioTap.pushWithPts(rate, 0.7f * sin(2 * PI * 440.0 * i / rate).toFloat(), (i * 1_000_000L) / rate, "catmull")
        }
        nowNs += 16_000_000L
        sm.update(0.016f, nowNs, StandbyStyle.Bars)
        val max64 = sm.v64.max()
        assertTrue(max64 > 0.3f, "Expected v64 to rise, got $max64")
        assertTrue(sm.v64.all { it in 0f..1f })
        assertTrue(sm.peak64.max() >= max64)

        val peakBefore = sm.peak64.max()
        AudioTap.onPause()
        // During 100ms hold, peak64 stays high; after ~250ms it drops under gravity
        repeat(5) {
            nowNs += 16_000_000L
            sm.update(0.016f, nowNs, StandbyStyle.Bars)
        }
        assertTrue(sm.peak64.max() >= peakBefore - 0.01f, "Peak cap should hold briefly")
        repeat(20) {
            nowNs += 16_000_000L
            sm.update(0.016f, nowNs, StandbyStyle.Bars)
        }
        assertTrue(sm.peak64.max() < peakBefore - 0.05f, "Peak cap should fall under gravity")
    }
}
