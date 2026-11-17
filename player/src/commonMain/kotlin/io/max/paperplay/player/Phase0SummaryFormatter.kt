package io.max.paperplay.player

import kotlin.math.roundToLong

/** Immutable snapshot of a Phase 0 DELEGATE diagnostic run for [Phase0SummaryFormatter]. */
internal data class Phase0SummarySnapshot(
    val build: String = "dev",
    val chain: String = SinkChainMode.FULL.id,
    val dacPlugged: Boolean = false,
    val bitPerfectOn: Boolean = false,
    val durMs: Long = 0L,
    val track: String = "unknown",
    val posAdvanceUs: Long = 0L,
    val bufferingCount: Long = 0L,
    val hbCalls: Long = 0L,
    val offeredBytes: Long = 0L,
    val acceptedBytes: Long = 0L,
    val falseReturns: Long = 0L,
    val dspTimeNs: Long = 0L,
    val dspCalls: Long = 0L,
    val atEnc: String = "none",
    val atRate: Int = 0,
    val atBuf: Int = 0,
    val atHeadAdvanceFrames: Long = 0L,
    val atWriteFull: Long = 0L,
    val atWriteShort: Long = 0L,
    val atWriteZero: Long = 0L,
    val atWriteErr: Long = 0L,
    val atLastErr: Int = 0,
    val atUnderruns: Long = 0L,
    val sinkUnderruns: Long = 0L,
    val factoryFloat: Boolean = false,
    val sinkFloat: Boolean = false,
)

/** Formats a single-line `PHASE0_SUMMARY` report from [Phase0SummarySnapshot]. Pure Kotlin (locale-independent). */
internal object Phase0SummaryFormatter {
    fun format(s: Phase0SummarySnapshot): String {
        val durTenths = (s.durMs.coerceAtLeast(0L) + 50L) / 100L
        val durStr = "${durTenths / 10}.${durTenths % 10}s"
        val posRateMsPerSec = if (s.durMs > 0L) (s.posAdvanceUs.toDouble() / s.durMs.toDouble()).roundToLong() else 0L
        val falseRatioHundredths = if (s.hbCalls > 0L) ((s.falseReturns.toDouble() / s.hbCalls.toDouble()) * 100.0).roundToLong() else 0L
        val dspHundredths = ((s.dspTimeNs.coerceAtLeast(0L).toDouble() / 1_000_000.0) * 100.0).roundToLong()

        return buildString {
            append("PHASE0_SUMMARY")
            append(" build=").append(s.build.ifBlank { "dev" }.replace(' ', '_'))
            append(" chain=").append(s.chain)
            append(" dac=").append(if (s.dacPlugged) "plugged" else "unplugged")
            append(" bp=").append(if (s.bitPerfectOn) "on" else "off")
            append(" dur=").append(durStr)
            append(" track=").append(s.track.ifBlank { "unknown" }.replace(' ', '_'))
            append(" | posRate=").append(posRateMsPerSec).append("ms/s")
            append(" buffering=").append(s.bufferingCount)
            append(" | hbCalls=").append(s.hbCalls)
            append(" offered=").append(s.offeredBytes).append('B')
            append(" accepted=").append(s.acceptedBytes).append('B')
            append(" falseRatio=").append(formatHundredths(falseRatioHundredths))
            append(" | dspMs=").append(formatHundredths(dspHundredths))
            append(" dspCalls=").append(s.dspCalls)
            append(" | atEnc=").append(s.atEnc)
            append(" atRate=").append(s.atRate).append("Hz")
            append(" atBuf=").append(s.atBuf).append('B')
            append(" atHeadAdv=").append(s.atHeadAdvanceFrames).append("fr")
            append(" atWriteFull=").append(s.atWriteFull)
            append(" atWriteShort=").append(s.atWriteShort)
            append(" atWriteZero=").append(s.atWriteZero)
            append(" atWriteErr=").append(s.atWriteErr)
            append(" atLastErr=").append(s.atLastErr)
            append(" atUnderruns=").append(s.atUnderruns)
            append(" sinkUnderruns=").append(s.sinkUnderruns)
            append(" | factoryFloat=").append(s.factoryFloat)
            append(" sinkFloat=").append(s.sinkFloat)
        }
    }

    private fun formatHundredths(value: Long): String {
        val sign = if (value < 0L) "-" else ""
        val absVal = kotlin.math.abs(value)
        val whole = absVal / 100L
        val frac = absVal % 100L
        val fracStr = if (frac < 10L) "0$frac" else "$frac"
        return "$sign$whole.$fracStr"
    }
}
