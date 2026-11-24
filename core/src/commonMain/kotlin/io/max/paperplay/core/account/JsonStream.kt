package io.max.paperplay.core.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.Reader

/**
 * Walks a JSON text of any size piece by piece: only the piece asked for is in memory. A 40 MB index parsed as one tree needs far more
 * than a phone's 256 MB heap. Malformed text throws [IllegalArgumentException].
 */
class JsonStream(private val input: Reader) {
    private var ahead = NONE

    @PublishedApi internal fun read(): Int = if (ahead != NONE) ahead.also { ahead = NONE } else input.read()

    /** The next character that is not a blank, left in place. */
    @PublishedApi internal fun peek(): Int {
        while (true) {
            val c = read()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == 0xFEFF) continue
            ahead = c
            return c
        }
    }

    @PublishedApi internal fun expect(c: Char) {
        val got = peek()
        if (got != c.code) throw IllegalArgumentException("Expected '$c' in the JSON text.")
        read()
    }

    val isArray: Boolean get() = peek() == '['.code
    val isObject: Boolean get() = peek() == '{'.code

    /** Goes through an object; [each] must use up the value of the key it is given. */
    inline fun obj(each: (String) -> Unit) {
        expect('{')
        if (peek() == '}'.code) { read(); return }
        while (true) {
            if (peek() != '"'.code) throw IllegalArgumentException("Expected a key in the JSON text.")
            val key = key()
            expect(':')
            each(key)
            if (peek() == ','.code) { read(); continue }
            expect('}')
            return
        }
    }

    /** Goes through an array; [each] must use up one value. */
    inline fun arr(each: () -> Unit) {
        expect('[')
        if (peek() == ']'.code) { read(); return }
        while (true) {
            each()
            if (peek() == ','.code) { read(); continue }
            expect(']')
            return
        }
    }

    @PublishedApi internal fun key(): String = Json.decodeFromString(raw())

    fun value(): JsonElement = Json.parseToJsonElement(raw())

    fun skip() { walk(null) }

    @PublishedApi internal fun raw(): String = StringBuilder().also { walk(it) }.toString()

    /** Reads one whole value, into [out] when given. */
    private fun walk(out: StringBuilder?) {
        var depth = 0
        val start = peek()
        val scalar = start != '{'.code && start != '['.code && start != '"'.code
        while (true) {
            val c = read()
            if (c < 0) { if (scalar && depth == 0) return; throw IllegalArgumentException("The JSON text ends too early.") }
            if (scalar) {
                // A number, true, false or null ends at the next separator, which is left for the caller.
                if (c == ','.code || c == '}'.code || c == ']'.code || c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) { ahead = c; return }
                out?.append(c.toChar())
                continue
            }
            out?.append(c.toChar())
            when (c) {
                '"'.code -> {
                    while (true) {
                        val s = read()
                        if (s < 0) throw IllegalArgumentException("The JSON text ends too early.")
                        out?.append(s.toChar())
                        if (s == '\\'.code) { val e = read(); if (e >= 0) out?.append(e.toChar()) } else if (s == '"'.code) break
                    }
                    if (depth == 0) return
                }
                '{'.code, '['.code -> depth++
                '}'.code, ']'.code -> if (--depth == 0) return
            }
        }
    }

    private companion object { const val NONE = -2 }
}
