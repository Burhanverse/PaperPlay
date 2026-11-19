package io.max.paperplay.player

/** Measures how a song sounds (tempo, key, loudness, timbre) from its audio file, for recommendations. */
expect object AudioAnalysis {
    /** Layout version of the vectors [analyze] returns; 0 where this platform cannot analyze. */
    val version: Int

    /** The feature vector of the audio file at [path], or null when it cannot be read or is silent. Blocking; call off the main thread. */
    fun analyze(path: String): FloatArray?
}
