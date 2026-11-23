package io.max.paperplay.player

actual object NativeEqMath {
    actual val nativeAvailable: Boolean
        get() = EngineNative.available

    actual fun evalResponse(
        sampleRate: Int,
        profile: ParametricEqProfile,
        freqsHz: FloatArray,
        channel: PeqChannel,
    ): EqResponseCurve {
        val out = FloatArray(freqsHz.size)
        if (!EngineNative.available || freqsHz.isEmpty()) {
            return EqResponseCurve(out, profile.preampDb)
        }
        val flat = profile.toNativeFlatFloatArray()
        val eff = EngineNative.evalParametricResponse(
            rate = sampleRate.coerceAtLeast(8000),
            preampDb = profile.preampDb,
            autoPreamp = profile.autoPreamp,
            bands = flat,
            freqsHz = freqsHz,
            outDb = out,
            channel = channel.nativeCode,
        )
        return EqResponseCurve(out, eff)
    }
}
