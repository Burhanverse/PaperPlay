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
    external fun stats(handle: Long, out: FloatArray)
    external fun reset(handle: Long)
}
