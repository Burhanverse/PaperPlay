package io.max.paperplay.player

import java.nio.ByteBuffer

/** JNI face of the Rust engine (`engine/`). [available] is false when the library is missing; callers then keep the plain pipeline. */
internal object EngineNative {
    val available: Boolean = runCatching { System.loadLibrary("engine") }
        .onFailure { PlayLog.log("engine", "libengine failed to load: $it") }.isSuccess
        .also { if (it) PlayLog.log("engine", "libengine loaded") }

    external fun create(rate: Int, channels: Int): Long
    external fun destroy(handle: Long)
    external fun setEq(handle: Long, gains: FloatArray)
    external fun process(handle: Long, buf: ByteBuffer, samples: Int)
    external fun setBass(handle: Long, db: Float)
    external fun setExact(handle: Long, on: Boolean)
    external fun setDry(handle: Long, on: Boolean)
    external fun setWidth(handle: Long, width: Float)
    external fun setCorrection(handle: Long, filters: FloatArray)
    external fun setOptions(handle: Long, bits: Int)
    /** Converts [samples] PCM samples of [kind] (0 = 16-bit, 1 = 24-bit, 2 = 32-bit, 3 = float) from direct [src] at byte [offset] into [out] as float and processes them; returns the samples written. */
    external fun processPcm(handle: Long, src: ByteBuffer, offset: Int, kind: Int, samples: Int, out: ByteBuffer): Int
    external fun stats(handle: Long, out: FloatArray)
    external fun reset(handle: Long)
    /** Reconfigures sample rate & channel count dynamically, clamping frequencies to `0.49 * rate` and resetting states. */
    external fun reconfigure(handle: Long, rate: Int, channels: Int)
    /** Processes direct [buf] bytes `[offset, offset + length)` in-place (`kind`: 0=16-bit, 1=24-bit, 2=32-bit, 3=float) with f64 DSP + TPDF dither. */
    external fun processPcmInPlace(handle: Long, buf: ByteBuffer, offset: Int, length: Int, kind: Int): Int
    /** Audio features of the first [n] samples of mono [pcm] at [rate] Hz into [out]; false when too short or silent. */
    external fun analyze(pcm: FloatArray, n: Int, rate: Int, out: FloatArray): Boolean
    external fun featureVersion(): Int
    /** Configures up to 31 parametric EQ bands (`6` floats per band: kind, channel, enabled, freqHz, gainDb, q). */
    external fun setParametricEq(
        handle: Long,
        enabled: Boolean,
        preampDb: Float,
        autoPreamp: Boolean,
        limiterEnabled: Boolean,
        bands: FloatArray,
    )
    /** Evaluates the exact Rust DSP magnitude response at [freqsHz] into [outDb]; returns effective preamp dB. */
    external fun evalParametricResponse(
        rate: Int,
        preampDb: Float,
        autoPreamp: Boolean,
        bands: FloatArray,
        freqsHz: FloatArray,
        outDb: FloatArray,
        channel: Int,
    ): Float
}
