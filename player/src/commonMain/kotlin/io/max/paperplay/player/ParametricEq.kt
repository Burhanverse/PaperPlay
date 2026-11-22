package io.max.paperplay.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 31-band parametric equalizer model, ISO 1/3-octave preset generator, Equalizer APO
 * (`ParametricEQ.txt`) parser/exporter, and Poweramp Equalizer `.json` import/export.
 */
enum class PeqFilterType(
    val nativeCode: Int,
    val apoCode: String,
    val powerampType: Int,
    val label: String,
    val shortLabel: String,
    val usesGain: Boolean = true,
) {
    PK(0, "PK", 3, "Peaking", "PK", true),
    LSC(1, "LSC", 4, "Low Shelf", "LS", true),
    HSC(2, "HSC", 5, "High Shelf", "HS", true),
    LP(3, "LP", 0, "Low Pass", "LP", false),
    HP(4, "HP", 1, "High Pass", "HP", false),
    BP(5, "BP", 2, "Band Pass", "BP", false);

    companion object {
        fun fromNativeCode(code: Int): PeqFilterType = entries.firstOrNull { it.nativeCode == code } ?: PK

        fun fromApoCode(code: String): PeqFilterType? = when (code.uppercase()) {
            "PK", "PEQ", "PEAKING" -> PK
            "LSC", "LS", "LOW_SHELF", "LOWSHELF" -> LSC
            "HSC", "HS", "HIGH_SHELF", "HIGHSHELF" -> HSC
            "LP", "LPQ", "LOW_PASS", "LOWPASS" -> LP
            "HP", "HPQ", "HIGH_PASS", "HIGHPASS" -> HP
            "BP", "BPQ", "BAND_PASS", "BANDPASS" -> BP
            else -> null
        }

        fun fromPowerampType(code: Int): PeqFilterType = entries.firstOrNull { it.powerampType == code } ?: PK
    }
}

enum class PeqChannel(
    val nativeCode: Int,
    val shortLabel: String,
    val label: String,
) {
    STEREO(0, "L+R", "Stereo"),
    LEFT(1, "L", "Left"),
    RIGHT(2, "R", "Right");

    companion object {
        fun fromNativeCode(code: Int): PeqChannel = entries.firstOrNull { it.nativeCode == code } ?: STEREO
    }
}

data class ParametricBand(
    val id: Int,
    val enabled: Boolean = true,
    val type: PeqFilterType = PeqFilterType.PK,
    val channel: PeqChannel = PeqChannel.STEREO,
    val freqHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1.41f,
) {
    fun clamped(): ParametricBand = copy(
        freqHz = freqHz.coerceIn(10f, 24000f),
        gainDb = gainDb.coerceIn(-24f, 24f),
        q = q.coerceIn(0.1f, 20f),
    )
}

data class ParametricEqProfile(
    val id: String = "custom",
    val name: String = "Custom",
    val source: String = "Custom",
    val preampDb: Float = 0f,
    val autoPreamp: Boolean = true,
    val limiterEnabled: Boolean = true,
    val bands: List<ParametricBand> = emptyList(),
) {
    /** Packs up to 31 bands into `[kind, channel, enabled, freqHz, gainDb, q]` for Rust FFI. */
    fun toNativeFlatFloatArray(): FloatArray {
        val limited = bands.take(MAX_PEQ_BANDS)
        val out = FloatArray(limited.size * 6)
        limited.forEachIndexed { idx, raw ->
            val b = raw.clamped()
            val base = idx * 6
            out[base] = b.type.nativeCode.toFloat()
            out[base + 1] = b.channel.nativeCode.toFloat()
            out[base + 2] = if (b.enabled) 1f else 0f
            out[base + 3] = b.freqHz
            out[base + 4] = b.gainDb
            out[base + 5] = b.q
        }
        return out
    }

    /** True when at least one band actively shapes the curve or manual preamp is non-zero. */
    fun hasActiveShaping(): Boolean {
        if (abs(preampDb) >= 0.01f) return true
        return bands.take(MAX_PEQ_BANDS).any { b ->
            b.enabled && (!b.type.usesGain || abs(b.gainDb) >= 0.01f)
        }
    }

    /** Compact semicolon-delimited wire format for offline AutoEQ DB rows: `kind,ch,en,freq,gain,q;...`. */
    fun toBandsBlob(): String = bands.take(MAX_PEQ_BANDS).joinToString(";") { b ->
        val c = b.clamped()
        "${c.type.nativeCode},${c.channel.nativeCode},${if (c.enabled) 1 else 0},${fmtNum(c.freqHz)},${fmtNum(c.gainDb)},${fmtNum(c.q)}"
    }

    companion object {
        const val MAX_PEQ_BANDS = 31

        /** Standard ISO 1/3-octave graphic EQ centre frequencies (31 bands, 20 Hz to 20 kHz). */
        val ISO_31_FREQS_HZ: FloatArray = floatArrayOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f,
            200f, 250f, 315f, 400f, 500f, 630f, 800f, 1000f, 1250f, 1600f,
            2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f,
        )

        /** Default Q for ISO 1/3-octave bands (bandwidth = 1/3 octave => Q ≈ 4.318). */
        const val ISO_31_Q: Float = 4.318f

        /** Standard 10-band octave centres for quick parametric starter presets. */
        val DEFAULT_10_FREQS_HZ: FloatArray = floatArrayOf(
            32f, 64f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f,
        )

        fun flat10(id: String = "flat-10", name: String = "Flat (10-Band)"): ParametricEqProfile = ParametricEqProfile(
            id = id,
            name = name,
            source = "Built-in",
            preampDb = 0f,
            autoPreamp = true,
            limiterEnabled = true,
            bands = DEFAULT_10_FREQS_HZ.mapIndexed { idx, f ->
                ParametricBand(
                    id = idx + 1,
                    enabled = true,
                    type = when (idx) {
                        0 -> PeqFilterType.LSC
                        DEFAULT_10_FREQS_HZ.lastIndex -> PeqFilterType.HSC
                        else -> PeqFilterType.PK
                    },
                    channel = PeqChannel.STEREO,
                    freqHz = f,
                    gainDb = 0f,
                    q = if (idx == 0 || idx == DEFAULT_10_FREQS_HZ.lastIndex) 0.71f else 1.41f,
                )
            },
        )

        fun iso31Preset(
            id: String = "iso-31",
            name: String = "31-Band Graphic (ISO)",
            gainsDb: FloatArray? = null,
        ): ParametricEqProfile = ParametricEqProfile(
            id = id,
            name = name,
            source = "Built-in",
            preampDb = 0f,
            autoPreamp = true,
            limiterEnabled = true,
            bands = ISO_31_FREQS_HZ.mapIndexed { idx, hz ->
                ParametricBand(
                    id = idx + 1,
                    enabled = true,
                    type = PeqFilterType.PK,
                    channel = PeqChannel.STEREO,
                    freqHz = hz,
                    gainDb = gainsDb?.getOrNull(idx)?.coerceIn(-24f, 24f) ?: 0f,
                    q = ISO_31_Q,
                )
            },
        )

        fun fromLegacy10Gains(gains: List<Float>, name: String = "Custom"): ParametricEqProfile {
            val safe = if (gains.size == 10) gains else List(10) { 0f }
            return ParametricEqProfile(
                id = "legacy-10",
                name = name,
                source = "Custom",
                preampDb = 0f,
                autoPreamp = true,
                limiterEnabled = true,
                bands = DEFAULT_10_FREQS_HZ.mapIndexed { idx, f ->
                    ParametricBand(
                        id = idx + 1,
                        enabled = true,
                        type = PeqFilterType.PK,
                        channel = PeqChannel.STEREO,
                        freqHz = f,
                        gainDb = safe[idx].coerceIn(-24f, 24f),
                        q = 1.41f,
                    )
                },
            )
        }

        fun fromBandsBlob(blob: String): List<ParametricBand> {
            if (blob.isBlank()) return emptyList()
            val out = ArrayList<ParametricBand>()
            var id = 1
            for (seg in blob.split(';')) {
                val p = seg.trim().split(',')
                if (p.size < 6) continue
                val type = PeqFilterType.fromNativeCode(p[0].toIntOrNull() ?: 0)
                val ch = PeqChannel.fromNativeCode(p[1].toIntOrNull() ?: 0)
                val en = (p[2].toIntOrNull() ?: 1) != 0
                val f = p[3].toFloatOrNull() ?: continue
                val g = p[4].toFloatOrNull() ?: 0f
                val q = p[5].toFloatOrNull() ?: 1.41f
                out.add(ParametricBand(id++, en, type, ch, f, g, q).clamped())
                if (out.size >= MAX_PEQ_BANDS) break
            }
            return out
        }
    }
}

/** Result of evaluating a parametric EQ curve directly in the Rust DSP engine. */
data class EqResponseCurve(
    val db: FloatArray,
    val effectivePreampDb: Float,
)

/**
 * Platform bridge that invokes the Rust DSP engine's `eval_parametric_response` directly
 * (Android JNI / Desktop JNA), ensuring zero duplicated biquad math in Kotlin.
 */
expect object NativeEqMath {
    val nativeAvailable: Boolean
    fun evalResponse(
        sampleRate: Int,
        profile: ParametricEqProfile,
        freqsHz: FloatArray,
        channel: PeqChannel = PeqChannel.STEREO,
    ): EqResponseCurve
}

/** Generates [count] log-spaced frequencies between [minHz] and [maxHz] for plotting the EQ curve. */
fun logSpacedFreqs(count: Int = 256, minHz: Float = 20f, maxHz: Float = 20000f): FloatArray {
    val n = count.coerceAtLeast(2)
    val lo = kotlin.math.ln(minHz.toDouble())
    val hi = kotlin.math.ln(maxHz.toDouble())
    return FloatArray(n) { i ->
        kotlin.math.exp(lo + (hi - lo) * i / (n - 1)).toFloat()
    }
}

// ---- Equalizer APO (ParametricEQ.txt) & Poweramp EQ (.json) Parsers / Exporters ----

object ParametricEqCodec {
    private val preampRe = Regex("""^\s*Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE)
    private val channelRe = Regex("""^\s*Channel\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val filterRe = Regex(
        """^\s*Filter(?:\s+\d+)?\s*:\s*(ON|OFF)\s+([A-Za-z_]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*(?:Hz)?(?:\s+Gain\s+([+-]?\d+(?:\.\d+)?)\s*(?:dB)?)?(?:\s+Q\s+(\d+(?:\.\d+)?))?.*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parses Equalizer APO `ParametricEQ.txt` text (including AutoEQ results) into a [ParametricEqProfile].
     * Supports comments (`#`), `Preamp:`, `Channel:` (`L`/`R`/`all`), `ON`/`OFF`, filter types
     * `PK|LSC|HSC|LS|HS|LP|HP|BP` (and `LPQ`/`HPQ`), and optional `Gain`/`Q` fields.
     * Returns `null` when no valid filters and no preamp directive are found.
     */
    fun parseEqualizerApo(
        text: String,
        name: String = "Imported APO",
        source: String = "Equalizer APO",
        id: String = "apo-${abs(text.hashCode())}",
    ): ParametricEqProfile? {
        var preampDb = 0f
        var sawPreamp = false
        var currentChannel = PeqChannel.STEREO
        val bands = ArrayList<ParametricBand>()
        var nextId = 1

        for (rawLine in text.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue

            preampRe.matchEntire(line)?.let { m ->
                m.groupValues[1].toFloatOrNull()?.let {
                    preampDb = it.coerceIn(-24f, 24f)
                    sawPreamp = true
                }
                continue
            }

            channelRe.matchEntire(line)?.let { m ->
                val ch = m.groupValues[1].trim().uppercase()
                currentChannel = when {
                    ch == "L" || ch == "LEFT" || ch == "FL" -> PeqChannel.LEFT
                    ch == "R" || ch == "RIGHT" || ch == "FR" -> PeqChannel.RIGHT
                    else -> PeqChannel.STEREO
                }
                continue
            }

            val fm = filterRe.matchEntire(line) ?: continue
            val enabled = fm.groupValues[1].equals("ON", ignoreCase = true)
            val type = PeqFilterType.fromApoCode(fm.groupValues[2]) ?: continue
            val freq = fm.groupValues[3].toFloatOrNull() ?: continue
            val gain = fm.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 0f
            val q = fm.groupValues[5].takeIf { it.isNotEmpty() }?.toFloatOrNull()
                ?: if (type == PeqFilterType.PK) 1.41f else 0.707f

            if (bands.size < ParametricEqProfile.MAX_PEQ_BANDS) {
                bands.add(
                    ParametricBand(
                        id = nextId++,
                        enabled = enabled,
                        type = type,
                        channel = currentChannel,
                        freqHz = freq,
                        gainDb = if (type.usesGain) gain else 0f,
                        q = q,
                    ).clamped(),
                )
            }
        }

        if (bands.isEmpty() && !sawPreamp) return null
        return ParametricEqProfile(
            id = id,
            name = name,
            source = source,
            preampDb = preampDb,
            autoPreamp = !sawPreamp,
            limiterEnabled = true,
            bands = bands,
        )
    }

    /** Exports a [ParametricEqProfile] as standard Equalizer APO `ParametricEQ.txt`. */
    fun exportEqualizerApo(profile: ParametricEqProfile): String = buildString {
        appendLine("# PaperPlay Parametric EQ: ${profile.name}")
        appendLine("Preamp: ${fmtSigned(profile.preampDb)} dB")
        var lastCh: PeqChannel? = null
        profile.bands.take(ParametricEqProfile.MAX_PEQ_BANDS).forEachIndexed { idx, raw ->
            val b = raw.clamped()
            if (b.channel != lastCh && (b.channel != PeqChannel.STEREO || lastCh != null)) {
                appendLine(
                    when (b.channel) {
                        PeqChannel.LEFT -> "Channel: L"
                        PeqChannel.RIGHT -> "Channel: R"
                        PeqChannel.STEREO -> "Channel: L R"
                    },
                )
                lastCh = b.channel
            }
            val state = if (b.enabled) "ON" else "OFF"
            val freqStr = fmtNum(b.freqHz)
            val qStr = fmtNum(b.q)
            if (b.type.usesGain) {
                appendLine("Filter ${idx + 1}: $state ${b.type.apoCode} Fc $freqStr Hz Gain ${fmtNum(b.gainDb)} dB Q $qStr")
            } else {
                appendLine("Filter ${idx + 1}: $state ${b.type.apoCode} Fc $freqStr Hz Q $qStr")
            }
        }
    }.trimEnd()

    /**
     * Parses a Poweramp Equalizer `.json` preset (either a single JSON object `{...}` or Poweramp's
     * top-level array `[{...}]`). Also parses PaperPlay's internal serialized JSON seamlessly.
     */
    fun parsePowerampJson(json: String): ParametricEqProfile? {
        val root = MiniJson.parse(json) ?: return null
        val obj = when (root) {
            is Map<*, *> -> root
            is List<*> -> root.firstOrNull() as? Map<*, *>
            else -> null
        } ?: return null

        val name = (obj["name"] as? String)?.takeIf { it.isNotBlank() } ?: "Imported Poweramp"
        val source = (obj["source"] as? String)?.takeIf { it.isNotBlank() } ?: "Poweramp"
        val id = (obj["id"] as? String)?.takeIf { it.isNotBlank() } ?: "pa-${abs(json.hashCode())}"
        val preamp = (obj["preamp"] as? Number)?.toFloat()?.coerceIn(-24f, 24f) ?: 0f
        val autoPreamp = (obj["autoPreamp"] as? Boolean) ?: (abs(preamp) < 0.01f)
        val limiterEnabled = (obj["limiterEnabled"] as? Boolean) ?: true
        val rawBands = obj["bands"] as? List<*> ?: return null

        val bands = ArrayList<ParametricBand>()
        var nextId = 1
        for (item in rawBands) {
            val bm = item as? Map<*, *> ?: continue
            val type = when (val t = bm["type"]) {
                is Number -> PeqFilterType.fromPowerampType(t.toInt())
                is String -> PeqFilterType.fromApoCode(t) ?: PeqFilterType.PK
                else -> PeqFilterType.PK
            }
            val ch = when (val c = bm["channels"]) {
                is Number -> PeqChannel.fromNativeCode(c.toInt())
                is String -> when (c.uppercase()) {
                    "L", "LEFT" -> PeqChannel.LEFT
                    "R", "RIGHT" -> PeqChannel.RIGHT
                    else -> PeqChannel.STEREO
                }
                else -> PeqChannel.STEREO
            }
            val enabled = (bm["enabled"] as? Boolean) ?: true
            val freq = (bm["frequency"] as? Number)?.toFloat()
                ?: (bm["freq"] as? Number)?.toFloat()
                ?: continue
            val gain = (bm["gain"] as? Number)?.toFloat() ?: 0f
            val q = (bm["q"] as? Number)?.toFloat() ?: 1.41f
            bands.add(
                ParametricBand(
                    id = nextId++,
                    enabled = enabled,
                    type = type,
                    channel = ch,
                    freqHz = freq,
                    gainDb = gain,
                    q = q,
                ).clamped(),
            )
            if (bands.size >= ParametricEqProfile.MAX_PEQ_BANDS) break
        }
        return ParametricEqProfile(
            id = id,
            name = name,
            source = source,
            preampDb = preamp,
            autoPreamp = autoPreamp,
            limiterEnabled = limiterEnabled,
            bands = bands,
        )
    }

    /**
     * Exports a [ParametricEqProfile] in Poweramp Equalizer `.json` array format (`[{...}]`).
     * Set [includePaperPlayMeta] to `true` for internal DB persistence (adds `id`, `source`, `autoPreamp`, `limiterEnabled`).
     */
    fun exportPowerampJson(profile: ParametricEqProfile, includePaperPlayMeta: Boolean = false): String = buildString {
        append('[')
        append('{')
        append("\"name\":").append(jsonStr(profile.name))
        append(",\"preamp\":").append(fmtNum(profile.preampDb))
        append(",\"parametric\":true")
        if (includePaperPlayMeta) {
            append(",\"id\":").append(jsonStr(profile.id))
            append(",\"source\":").append(jsonStr(profile.source))
            append(",\"autoPreamp\":").append(profile.autoPreamp)
            append(",\"limiterEnabled\":").append(profile.limiterEnabled)
        }
        append(",\"bands\":[")
        profile.bands.take(ParametricEqProfile.MAX_PEQ_BANDS).forEachIndexed { idx, raw ->
            val b = raw.clamped()
            if (idx > 0) append(',')
            append('{')
            append("\"type\":").append(b.type.powerampType)
            append(",\"channels\":").append(b.channel.nativeCode)
            append(",\"frequency\":").append(fmtNum(b.freqHz))
            append(",\"q\":").append(fmtNum(b.q))
            append(",\"gain\":").append(fmtNum(b.gainDb))
            append(",\"enabled\":").append(b.enabled)
            append('}')
        }
        append("]}]")
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}

private fun fmtNum(v: Float): String {
    val r = (v * 1000f).roundToInt() / 1000f
    return if (r % 1f == 0f) r.toInt().toString() else r.toString()
}

private fun fmtSigned(v: Float): String {
    val s = fmtNum(v)
    return if (v > 0f && !s.startsWith("+")) "+$s" else s
}

/** Minimal zero-dependency RFC-8259 JSON parser for Poweramp `.json` presets. */
private object MiniJson {
    fun parse(input: String): Any? = runCatching { Parser(input).parseRoot() }.getOrNull()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseRoot(): Any? {
            skipWs()
            if (i >= s.length) return null
            val v = parseValue()
            skipWs()
            return v
        }

        private fun skipWs() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) error("eof")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) error("expected $lit")
            i += lit.length
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return map }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') error("expected key")
                val k = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') error("expected ':'")
                i++
                map[k] = parseValue()
                skipWs()
                if (i >= s.length) error("unclosed object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> error("unexpected char")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return list }
            while (true) {
                list.add(parseValue())
                skipWs()
                if (i >= s.length) error("unclosed array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> error("unexpected char")
                }
            }
        }

        private fun parseString(): String {
            i++ // '"'
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) error("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("unclosed string")
        }

        private fun parseNumber(): Number {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isFloat = false
            if (i < s.length && s[i] == '.') {
                isFloat = true
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isFloat = true
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val token = s.substring(start, i)
            return if (isFloat) token.toDouble() else token.toLong()
        }
    }
}
