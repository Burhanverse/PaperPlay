package io.max.paperplay.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvFiltersTest {
    @Test
    fun `a flat curve with no bass needs no filter at all`() {
        assertNull(MpvFilters.plan(List(10) { 0f }, 0f))
        assertNull(MpvFilters.plan(null, 0f))
        assertNull(MpvFilters.graph(null, normalize = false, profile = null))
    }

    @Test
    fun `the fitted response passes through the drawn curve`() {
        val target = listOf(8f, 7f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f)
        val plan = assertNotNull(MpvFilters.plan(target, 0f))
        for (i in 0 until 10) {
            val got = plan.responseDb(MpvFilters.BANDS_HZ[i])
            assertTrue(abs(got - target[i]) < 0.5, "band $i wanted ${target[i]} got $got")
        }
    }

    @Test
    fun `bass alone lifts the low end, leaves the highs, and cuts the inaudible range`() {
        val plan = assertNotNull(MpvFilters.plan(null, 9f))
        assertTrue(plan.responseDb(40.0) > 7.0)
        assertTrue(plan.responseDb(60.0) > 6.0)
        assertTrue(plan.responseDb(10.0) < 0.0, "10 Hz must be cut")
        assertTrue(abs(plan.responseDb(1000.0)) < 0.5)
        assertTrue(abs(plan.responseDb(8000.0)) < 0.2)
    }

    @Test
    fun `the bass half is trimmed so it stays near plus four dB and the rest follows`() {
        val plan = assertNotNull(MpvFilters.plan(listOf(12f, 10f, 5f, 0f, -1f, 0f, 0f, 0f, 0f, 0f), 12f))
        assertTrue(plan.lowTrimDb < -10.0, "low trim ${plan.lowTrimDb}")
        assertTrue(plan.highTrimDb in -10.5..-9.0, "the rest is lowered by at most about 10 dB, got ${plan.highTrimDb}")
    }

    @Test
    fun `a mid boost trims the highs a little and not the bass half`() {
        val plan = assertNotNull(MpvFilters.plan(listOf(0f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f), 0f))
        assertEquals(0.0, plan.lowTrimDb, 1e-9)
        assertTrue(abs(plan.highTrimDb + 0.35 * 6.0) < 0.5, "got ${plan.highTrimDb}")
    }

    @Test
    fun `the graph has the bands, the bass stage, the split and three limiters`() {
        val plan = assertNotNull(MpvFilters.plan(listOf(4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 3f), 6f))
        val g = assertNotNull(MpvFilters.graph(plan, normalize = false, profile = null))
        assertTrue("equalizer=f=32.000:t=q:w=1.41" in g)
        assertTrue("equalizer=f=16000.000:t=q:w=1.41" in g)
        assertTrue("bass=g=6.000:f=100:t=s:w=1" in g)
        assertTrue("highpass=f=22:poles=2" in g)
        assertTrue("acrossover=split=180:order=4th[lo][hi]" in g)
        assertEquals(3, Regex("alimiter=").findAll(g).count())
        assertTrue("amix=inputs=2:normalize=0" in g)
        assertTrue("equalizer=f=1000.000" !in g, "a band at 0 dB is left out")
    }

    @Test
    fun `the af value quotes the whole graph by its byte length`() {
        val graph = "volume=-3.000dB,alimiter=limit=0.98"
        assertEquals("lavfi=graph=%${graph.length}%$graph", MpvFilters.afValue(graph))
        assertEquals("", MpvFilters.afValue(null))
    }

    @Test
    fun `normalise and a profile come first in the chain`() {
        val profile = CorrectionProfile.parse(CorrectionProfile.MOONDROP_CHU_2)
        val plan = assertNotNull(MpvFilters.plan(null, 3f))
        val g = assertNotNull(MpvFilters.graph(plan, normalize = true, profile = profile))
        assertTrue(g.startsWith("dynaudnorm="))
        assertTrue(g.indexOf("firequalizer=") in 1 until g.indexOf("bass="))
    }

    @Test
    fun `a graphic eq line is read, and rubbish is refused`() {
        val points = assertNotNull(CorrectionProfile.parse(CorrectionProfile.MOONDROP_CHU_2))
        assertTrue(points.size > 100)
        assertEquals(20.0, points.first().first)
        assertEquals(-4.0, points.first().second)
        assertNull(CorrectionProfile.parse("hello"))
        assertNull(CorrectionProfile.parse("GraphicEQ: 20 -4; 10 -3; 30 1; 40 1; 50 1; 60 1; 70 1; 80 1")) // frequencies not rising
        assertNull(CorrectionProfile.parse("GraphicEQ: 20 -4; 21 -3")) // too few points
    }

    @Test
    fun `parametric eq text is read into kind, hz, q and gain`() {
        for ((name, text) in CorrectionProfile.EARPHONES) {
            val f = assertNotNull(CorrectionProfile.parseParametric(text), name)
            assertEquals(40, f.size, name)
        }
        val castor = CorrectionProfile.parseParametric(CorrectionProfile.EARPHONES.getValue("KZ Castor Bass (on-on-on-on)"))!!
        assertEquals(listOf(1f, 105f, 0.7f, 4.2f), castor.take(4)) // low shelf
        assertEquals(listOf(0f, 85f, 0.24f, -4.8f), castor.drop(8).take(4)) // peak with a negative gain
        assertEquals(2f, castor[20]) // high shelf
        assertNull(CorrectionProfile.parseParametric("Preamp: -3.0 dB"))
        assertNull(CorrectionProfile.parseParametric("Filter 1: OFF PK Fc 100 Hz Gain 3 dB Q 1"))
    }
}
