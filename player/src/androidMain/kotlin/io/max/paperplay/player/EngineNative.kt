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
    /** Converts [samples] PCM samples of [kind] (0 = 16-bit, 1 = 24-bit, 2 = 32-bit, 3 = float) from direct [src] at byte [offset] into [out] as float and processes them; returns the samples written. */
    external fun processPcm(handle: Long, src: ByteBuffer, offset: Int, kind: Int, samples: Int, out: ByteBuffer): Int
    external fun stats(handle: Long, out: FloatArray)
    external fun reset(handle: Long)
}
