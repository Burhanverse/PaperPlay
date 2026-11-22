package io.max.paperplay.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

internal interface EngineCLib : Library {
    fun paperplay_eq_create(rate: Int, channels: Int): Pointer?
    fun paperplay_eq_destroy(handle: Pointer?)
    fun paperplay_eq_set_parametric(
        handle: Pointer?,
        enabled: Int,
        preamp_db: Float,
        auto_preamp: Int,
        limiter_enabled: Int,
        bands_flat: FloatArray?,
        bands_float_len: Int,
    )
    fun paperplay_eq_process(handle: Pointer?, buf: FloatArray, samples: Int)
    fun paperplay_eq_eval_response(
        rate: Int,
        preamp_db: Float,
        auto_preamp: Int,
        bands_flat: FloatArray?,
        bands_float_len: Int,
        freqs_hz: FloatArray,
        out_db: FloatArray,
        n_freqs: Int,
        channel: Int,
    ): Float
}

/**
 * Desktop JVM bridge to the Rust `engine` shared library via JNA.
 * Searches app resources, dev `engine/target/release`, and standard library paths.
 */
internal object EngineJvmLoader {
    val lib: EngineCLib? by lazy { load() }

    private fun load(): EngineCLib? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val libName = when {
            os.startsWith("windows") -> "engine.dll"
            os.startsWith("mac") -> "libengine.dylib"
            else -> "libengine.so"
        }
        val candidates = ArrayList<File>()
        System.getProperty("compose.application.resources.dir")?.let { dir ->
            candidates.add(File(dir, libName))
            candidates.add(File(dir, "native/$libName"))
        }
        val cwd = File(System.getProperty("user.dir") ?: ".")
        candidates.add(File(cwd, "engine/target/release/$libName"))
        candidates.add(File(cwd.parentFile ?: cwd, "engine/target/release/$libName"))
        candidates.add(File(cwd, "engine/target/debug/$libName"))
        candidates.add(File(cwd.parentFile ?: cwd, "engine/target/debug/$libName"))
        for (f in candidates) {
            if (f.isFile) {
                runCatching { Native.load(f.absolutePath, EngineCLib::class.java) }.getOrNull()?.let {
                    PlayLog.log("engine", "Desktop libengine loaded from ${f.absolutePath}")
                    return it
                }
            }
        }
        return runCatching { Native.load("engine", EngineCLib::class.java) }.getOrNull()
    }
}

actual object NativeEqMath {
    actual val nativeAvailable: Boolean
        get() = EngineJvmLoader.lib != null

    actual fun evalResponse(
        sampleRate: Int,
        profile: ParametricEqProfile,
        freqsHz: FloatArray,
        channel: PeqChannel,
    ): EqResponseCurve {
        val out = FloatArray(freqsHz.size)
        val lib = EngineJvmLoader.lib
        if (lib == null || freqsHz.isEmpty()) {
            return EqResponseCurve(out, profile.preampDb)
        }
        val flat = profile.toNativeFlatFloatArray()
        val eff = lib.paperplay_eq_eval_response(
            rate = sampleRate.coerceAtLeast(8000),
            preamp_db = profile.preampDb,
            auto_preamp = if (profile.autoPreamp) 1 else 0,
            bands_flat = if (flat.isEmpty()) null else flat,
            bands_float_len = flat.size,
            freqs_hz = freqsHz,
            out_db = out,
            n_freqs = freqsHz.size,
            channel = channel.nativeCode,
        )
        return EqResponseCurve(out, eff)
    }
}
