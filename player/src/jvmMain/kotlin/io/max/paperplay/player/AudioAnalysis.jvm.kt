package io.max.paperplay.player

// The desktop has no decoder wired to the Rust engine yet; vectors measured on the phone reach it later.
actual object AudioAnalysis {
    actual val version: Int = 0
    actual fun analyze(path: String): FloatArray? = null
}
