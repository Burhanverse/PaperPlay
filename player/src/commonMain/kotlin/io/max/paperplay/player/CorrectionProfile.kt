package io.max.paperplay.player

/**
 * Earphone correction profiles in the AutoEq "GraphicEQ" text form: `GraphicEQ: 20 -4.0; 21 -4.2; ...`, a gain in dB at each
 * frequency. The AutoEq project (https://github.com/jaakkopasanen/AutoEq, MIT licence) publishes one for most earphones.
 */
object CorrectionProfile {
    /**
     * Reads a GraphicEQ line into frequency and gain pairs; null when the text is not one. Frequencies go up in order, between
     * 10 Hz and 40 kHz; gains are limited to +/-24 dB; at least eight points are needed.
     */
    fun parse(text: String): List<Pair<Double, Double>>? {
        val body = text.substringAfter("GraphicEQ:", text).trim()
        val points = body.split(';').mapNotNull { seg ->
            val p = seg.trim().split(Regex("\\s+"))
            if (p.size != 2) null else {
                val f = p[0].toDoubleOrNull(); val g = p[1].toDoubleOrNull()
                if (f == null || g == null || f < 10 || f > 40000) null else f to g.coerceIn(-24.0, 24.0)
            }
        }
        if (points.size < 8) return null
        if (points.zipWithNext().any { (a, b) -> b.first <= a.first }) return null
        return points
    }

    /**
     * Reads AutoEq "ParametricEQ.txt" text (`Filter 1: ON PK Fc 244 Hz Gain 4.9 dB Q 1.21`, also LSC and HSC) into 4 floats
     * per filter: kind (0 peak, 1 low shelf, 2 high shelf), Hz, Q, gain dB. The preamp line is ignored (the engine sets its
     * own headroom). Null when no filter is found.
     */
    fun parseParametric(text: String): FloatArray? {
        val re = Regex("""Filter\s*\d*:\s*ON\s+(PK|LSC|HSC|LS|HS)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""")
        val out = re.findAll(text).mapNotNull { m ->
            val (type, fc, gain, q) = m.destructured
            val kind = when (type) { "PK" -> 0f; "LSC", "LS" -> 1f; else -> 2f }
            val f = fc.toFloatOrNull(); val g = gain.toFloatOrNull(); val qq = q.toFloatOrNull()
            if (f == null || g == null || qq == null) null else floatArrayOf(kind, f, qq, g.coerceIn(-24f, 24f))
        }.toList()
        return if (out.isEmpty()) null else out.reduce { a, b -> a + b }
    }

    /**
     * Built-in earphone corrections (phone engine): AutoEq ParametricEQ results toward its default in-ear target. Each is the
     * measured earphone's deviation from that target, so it changes the earphone's own sound, not the music.
     */
    val EARPHONES: Map<String, String> = linkedMapOf(
        // ToneDeafMonk measurement, switches on-on-on-on: AutoEq results/ToneDeafMonk/in-ear/KZ Castor Improved Bass Version (on-on-on-on).
        "KZ Castor Bass (on-on-on-on)" to
            "Filter 1: ON LSC Fc 105 Hz Gain 4.2 dB Q 0.70\n" +
            "Filter 2: ON PK Fc 244 Hz Gain 4.9 dB Q 1.21\n" +
            "Filter 3: ON PK Fc 85 Hz Gain -4.8 dB Q 0.24\n" +
            "Filter 4: ON PK Fc 398 Hz Gain 2.1 dB Q 0.79\n" +
            "Filter 5: ON PK Fc 5251 Hz Gain -2.2 dB Q 3.24\n" +
            "Filter 6: ON HSC Fc 10000 Hz Gain -3.8 dB Q 0.70\n" +
            "Filter 7: ON PK Fc 2791 Hz Gain -1.1 dB Q 3.02\n" +
            "Filter 8: ON PK Fc 8369 Hz Gain -1.1 dB Q 3.25\n" +
            "Filter 9: ON PK Fc 6570 Hz Gain 2.2 dB Q 5.91\n" +
            "Filter 10: ON PK Fc 1455 Hz Gain -0.5 dB Q 2.30",
        // crinacle measurement on a 711 coupler: AutoEq results/crinacle/711 in-ear/Moondrop Chu 2.
        "Moondrop Chu 2" to
            "Filter 1: ON LSC Fc 105 Hz Gain -0.2 dB Q 0.70\n" +
            "Filter 2: ON PK Fc 166 Hz Gain -2.6 dB Q 0.87\n" +
            "Filter 3: ON PK Fc 5928 Hz Gain 6.7 dB Q 0.95\n" +
            "Filter 4: ON PK Fc 780 Hz Gain 2.2 dB Q 1.24\n" +
            "Filter 5: ON PK Fc 4221 Hz Gain -4.4 dB Q 0.80\n" +
            "Filter 6: ON HSC Fc 10000 Hz Gain -3.1 dB Q 0.70\n" +
            "Filter 7: ON PK Fc 9560 Hz Gain 1.6 dB Q 2.29\n" +
            "Filter 8: ON PK Fc 1418 Hz Gain -0.7 dB Q 2.98\n" +
            "Filter 9: ON PK Fc 1028 Hz Gain 0.5 dB Q 3.66\n" +
            "Filter 10: ON PK Fc 2167 Hz Gain 0.4 dB Q 4.09",
    )

    /** Moondrop Chu 2, crinacle measurement on a 711 coupler, from the AutoEq results (GraphicEQ.txt, 20 Hz to 20 kHz). */
    const val MOONDROP_CHU_2 = "GraphicEQ: 20 -4.0; 21 -4.2; 22 -4.3; 23 -4.3; 24 -4.4; 26 -4.7; 27 -4.8; 29 -4.8; 30 -4.8; 32 -4.7; 34 -4.7; " +
        "36 -4.8; 38 -4.8; 40 -4.8; 43 -4.8; 45 -4.7; 48 -4.9; 50 -4.9; 53 -4.8; 56 -4.8; 59 -4.8; 63 -4.8; 66 -4.9; 70 -4.9; 74 -5.0; " +
        "78 -5.1; 83 -5.3; 87 -5.4; 92 -5.6; 97 -5.8; 103 -5.9; 109 -6.1; 115 -6.3; 121 -6.5; 128 -6.6; 136 -6.7; 143 -6.7; 151 -6.8; " +
        "160 -6.8; 169 -6.7; 178 -6.7; 188 -6.6; 199 -6.5; 210 -6.4; 222 -6.3; 235 -6.1; 248 -6.0; 262 -5.8; 277 -5.7; 292 -5.5; " +
        "309 -5.3; 326 -5.1; 345 -4.9; 364 -4.7; 385 -4.5; 406 -4.3; 429 -4.1; 453 -4.0; 479 -3.8; 506 -3.6; 534 -3.4; 565 -3.3; " +
        "596 -3.2; 630 -3.0; 665 -2.9; 703 -2.7; 743 -2.6; 784 -2.5; 829 -2.4; 875 -2.4; 924 -2.4; 977 -2.6; 1032 -2.8; 1090 -3.1; " +
        "1151 -3.5; 1216 -3.9; 1284 -4.2; 1357 -4.5; 1433 -4.6; 1514 -4.7; 1599 -4.7; 1689 -4.7; 1784 -4.6; 1885 -4.6; 1991 -4.6; " +
        "2103 -4.6; 2221 -4.6; 2347 -4.7; 2479 -4.9; 2618 -5.2; 2766 -5.4; 2921 -5.5; 3086 -5.6; 3260 -5.5; 3443 -5.3; 3637 -5.1; " +
        "3842 -4.9; 4058 -4.6; 4287 -4.2; 4528 -3.7; 4783 -3.1; 5052 -2.5; 5337 -1.7; 5637 -0.9; 5955 -0.4; 6290 -0.2; 6644 -0.6; " +
        "7018 -1.3; 7414 -1.5; 7831 -1.4; 8272 -1.5; 8738 -1.7; 9230 -2.1; 9749 -2.6; 10298 -3.0; 10878 -3.4; 11490 -3.8; " +
        "12137 -4.3; 12821 -4.8; 13543 -5.4; 14305 -6.0; 15110 -6.6; 15961 -7.3; 16860 -8.0; 17809 -8.8; 18812 -9.6; 19871 -10.4"
}
