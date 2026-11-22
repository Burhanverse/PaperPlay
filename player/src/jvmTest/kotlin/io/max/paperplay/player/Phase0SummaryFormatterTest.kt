package io.max.paperplay.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Phase0SummaryFormatterTest {

    @Test
    fun testFormatsAllMetricsAndFloatFlags() {
        val line = Phase0SummaryFormatter.format(
            Phase0SummarySnapshot(
                build = "1.2.3(45)",
                chain = SinkChainMode.NO_DSP_FLOAT_OFF.id,
                dacPlugged = true,
                bitPerfectOn = false,
                durMs = 30_000L,
                track = "flac/24b/96000Hz",
                posAdvanceUs = 5_400_000L, // 180 ms/s crawl
                bufferingCount = 29L,
                hbCalls = 1000L,
                offeredBytes = 384_000L,
                acceptedBytes = 192_000L,
                falseReturns = 250L,
                dspTimeNs = 12_340_000L, // 12.34 ms
                dspCalls = 400L,
                atEnc = "PCM24",
                atRate = 96000,
                atBuf = 16384,
                atHeadAdvanceFrames = 17_280L,
                atWriteFull = 750L,
                atWriteShort = 50L,
                atWriteZero = 200L,
                atWriteErr = 2L,
                atLastErr = -32,
                atUnderruns = 7L,
                sinkUnderruns = 3L,
                factoryFloat = false,
                sinkFloat = false,
            )
        )

        assertTrue(line.startsWith("PHASE0_SUMMARY "))
        assertTrue(line.contains("build=1.2.3(45)"))
        assertTrue(line.contains("chain=no_dsp_float_off"))
        assertTrue(line.contains("dac=plugged"))
        assertTrue(line.contains("bp=off"))
        assertTrue(line.contains("dur=30.0s"))
        assertTrue(line.contains("track=flac/24b/96000Hz"))
        assertTrue(line.contains("posRate=180ms/s"))
        assertTrue(line.contains("buffering=29"))
        assertTrue(line.contains("hbCalls=1000"))
        assertTrue(line.contains("offered=384000B"))
        assertTrue(line.contains("accepted=192000B"))
        assertTrue(line.contains("falseRatio=0.25"))
        assertTrue(line.contains("dspMs=12.34 dspCalls=400"))
        assertTrue(line.contains("atEnc=PCM24 atRate=96000Hz atBuf=16384B atHeadAdv=17280fr"))
        assertTrue(line.contains("atWriteFull=750 atWriteShort=50 atWriteZero=200 atWriteErr=2 atLastErr=-32"))
        assertTrue(line.contains("atUnderruns=7 sinkUnderruns=3"))
        assertTrue(line.contains("factoryFloat=false sinkFloat=false"))
    }

    @Test
    fun testHandlesZeroDurationAndZeroCallsWithoutNaN() {
        val line = Phase0SummaryFormatter.format(Phase0SummarySnapshot())
        assertTrue(line.contains("dur=0.0s"))
        assertTrue(line.contains("posRate=0ms/s"))
        assertTrue(line.contains("falseRatio=0.00"))
        assertTrue(line.contains("dspMs=0.00"))
        assertEquals(1, line.lines().size, "Summary must remain strictly single-line")
    }
}
