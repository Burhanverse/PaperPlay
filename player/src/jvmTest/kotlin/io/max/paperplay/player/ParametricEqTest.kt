package io.max.paperplay.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParametricEqTest {
    @Test
    fun gate2_equalizerApoRealWorldParseAndRoundTrip() {
        // Real-world AutoEQ ParametricEQ.txt (oratory1990 Sennheiser HD 650 style + LP/HP/OFF/comments/channel)
        val apo = """
            # AutoEQ Sennheiser HD 650 (oratory1990)
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.71
            Filter 2: ON PK Fc 210 Hz Gain -2.4 dB Q 0.90
            Filter 3: ON PK Fc 1650 Hz Gain 1.8 dB Q 2.10
            Filter 4: ON HSC Fc 10000 Hz Gain 2.5 dB Q 0.71
            Filter 5: OFF PK Fc 5800 Hz Gain -3.0 dB Q 4.00
            Filter 6: ON HP Fc 20 Hz Q 0.707
            Filter 7: ON LP Fc 19000 Hz Q 0.707
            Channel: L
            Filter 8: ON PK Fc 3000 Hz Gain 1.2 dB Q 3.0
        """.trimIndent()

        val parsed = ParametricEqCodec.parseEqualizerApo(apo, name = "HD 650", source = "oratory1990")
        assertNotNull(parsed, "Failed to parse valid Equalizer APO text")
        assertEquals("HD 650", parsed.name)
        assertEquals(-6.2f, parsed.preampDb, 0.01f)
        assertEquals(8, parsed.bands.size)
        assertEquals(PeqFilterType.LSC, parsed.bands[0].type)
        assertEquals(105f, parsed.bands[0].freqHz, 0.01f)
        assertEquals(5.5f, parsed.bands[0].gainDb, 0.01f)
        assertEquals(0.71f, parsed.bands[0].q, 0.01f)
        assertEquals(false, parsed.bands[4].enabled)
        assertEquals(PeqFilterType.HP, parsed.bands[5].type)
        assertEquals(PeqFilterType.LP, parsed.bands[6].type)
        assertEquals(PeqChannel.LEFT, parsed.bands[7].channel)

        // Round-trip export -> re-parse
        val exported = ParametricEqCodec.exportEqualizerApo(parsed)
        val reparsed = ParametricEqCodec.parseEqualizerApo(exported, name = parsed.name, source = parsed.source)
        assertNotNull(reparsed)
        assertEquals(parsed.preampDb, reparsed.preampDb, 0.01f)
        assertEquals(parsed.bands.size, reparsed.bands.size)
        for (i in parsed.bands.indices) {
            val a = parsed.bands[i]
            val b = reparsed.bands[i]
            assertEquals(a.enabled, b.enabled, "band $i enabled")
            assertEquals(a.type, b.type, "band $i type")
            assertEquals(a.channel, b.channel, "band $i channel")
            assertEquals(a.freqHz, b.freqHz, 0.05f, "band $i freq")
            assertEquals(a.gainDb, b.gainDb, 0.05f, "band $i gain")
            assertEquals(a.q, b.q, 0.01f, "band $i q")
        }
    }

    @Test
    fun gate2_powerampJsonRoundTripPreservesAllFields() {
        val powerampInput = """
            [
              {
                "name": "Moondrop Chu 2 Custom",
                "preamp": -3.4,
                "parametric": true,
                "bands": [
                  { "type": 4, "channels": 0, "frequency": 105, "q": 0.71, "gain": 3.5, "enabled": true },
                  { "type": 3, "channels": 1, "frequency": 1000, "q": 1.41, "gain": 6.0, "enabled": true },
                  { "type": 5, "channels": 2, "frequency": 10000, "q": 0.7, "gain": -2.5, "enabled": false },
                  { "type": 0, "channels": 0, "frequency": 18000, "q": 0.707, "gain": 0, "enabled": true },
                  { "type": 1, "channels": 0, "frequency": 25, "q": 0.707, "gain": 0, "enabled": true },
                  { "type": 2, "channels": 0, "frequency": 2000, "q": 2.0, "gain": 0, "enabled": true }
                ]
              }
            ]
        """.trimIndent()

        val parsed = ParametricEqCodec.parsePowerampJson(powerampInput)
        assertNotNull(parsed)
        assertEquals("Moondrop Chu 2 Custom", parsed.name)
        assertEquals(-3.4f, parsed.preampDb, 0.01f)
        assertEquals(6, parsed.bands.size)
        assertEquals(PeqFilterType.LSC, parsed.bands[0].type)
        assertEquals(PeqFilterType.PK, parsed.bands[1].type)
        assertEquals(PeqChannel.LEFT, parsed.bands[1].channel)
        assertEquals(PeqFilterType.HSC, parsed.bands[2].type)
        assertEquals(PeqChannel.RIGHT, parsed.bands[2].channel)
        assertEquals(false, parsed.bands[2].enabled)
        assertEquals(PeqFilterType.LP, parsed.bands[3].type)
        assertEquals(PeqFilterType.HP, parsed.bands[4].type)
        assertEquals(PeqFilterType.BP, parsed.bands[5].type)

        val exported = ParametricEqCodec.exportPowerampJson(parsed, includePaperPlayMeta = true)
        val reparsed = ParametricEqCodec.parsePowerampJson(exported)
        assertNotNull(reparsed)
        assertEquals(parsed.name, reparsed.name)
        assertEquals(parsed.preampDb, reparsed.preampDb, 0.01f)
        assertEquals(parsed.bands.size, reparsed.bands.size)
        for (i in parsed.bands.indices) {
            assertEquals(parsed.bands[i].type, reparsed.bands[i].type)
            assertEquals(parsed.bands[i].channel, reparsed.bands[i].channel)
            assertEquals(parsed.bands[i].enabled, reparsed.bands[i].enabled)
            assertEquals(parsed.bands[i].freqHz, reparsed.bands[i].freqHz, 0.01f)
            assertEquals(parsed.bands[i].gainDb, reparsed.bands[i].gainDb, 0.01f)
            assertEquals(parsed.bands[i].q, reparsed.bands[i].q, 0.01f)
        }
    }

    @Test
    fun iso31PresetHasExact31StandardBands() {
        val iso = ParametricEqProfile.iso31Preset()
        assertEquals(31, iso.bands.size)
        assertEquals(20f, iso.bands.first().freqHz)
        assertEquals(20000f, iso.bands.last().freqHz)
        assertTrue(iso.bands.zipWithNext().all { (a, b) -> b.freqHz > a.freqHz })
    }

    @Test
    fun nativeRustEvalResponseMatchesExpectedBiquadGain() {
        assertTrue(NativeEqMath.nativeAvailable, "Host libengine.so must be loaded via JNA")
        val profile = ParametricEqProfile(
            name = "1k +6dB",
            preampDb = 0f,
            autoPreamp = false,
            limiterEnabled = false,
            bands = listOf(ParametricBand(1, true, PeqFilterType.PK, PeqChannel.STEREO, 1000f, 6.0f, 1.41f)),
        )
        val freqs = floatArrayOf(50f, 1000f, 15000f)
        val resp = NativeEqMath.evalResponse(48000, profile, freqs)
        assertTrue(abs(resp.db[0]) < 0.2f, "50 Hz got ${resp.db[0]}")
        assertTrue(abs(resp.db[1] - 6.0f) < 0.05f, "1000 Hz got ${resp.db[1]}")
        assertTrue(abs(resp.db[2]) < 0.2f, "15000 Hz got ${resp.db[2]}")
    }
}
