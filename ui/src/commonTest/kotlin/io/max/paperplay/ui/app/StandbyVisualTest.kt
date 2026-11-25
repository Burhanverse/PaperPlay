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
}
