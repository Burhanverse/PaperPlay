package io.max.paperplay.core.integrations

import io.max.paperplay.core.metadata.LrcLine
import io.max.paperplay.core.metadata.pairLrc
import io.max.paperplay.core.metadata.pairLrcLines
import io.max.paperplay.core.metadata.parseLrc
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsPairingTest {
    @Test
    fun pairsExactAndSlightlyOffsetTimestamps() {
        val primary = """
            [00:10.00]頂点へと続く未来まで
            [00:16.00]信じるままに 一歩ずつ
            [00:22.00]I sing, alive
        """.trimIndent()
        val roma = """
            [00:10.25]chouten he to tsuzuku mirai made
            [00:15.70]shinjiru mama ni ippo zutsu
            [00:22.00]I sing, alive
        """.trimIndent()
        val paired = pairLrc(primary, roma)
        assertEquals(3, paired.size)
        assertEquals("chouten he to tsuzuku mirai made", paired[0].secondary)
        assertEquals("shinjiru mama ni ippo zutsu", paired[1].secondary)
        // Identical line is deduplicated
        assertNull(paired[2].secondary)
    }

    @Test
    fun missingSecondaryLineDoesNotStealNextLinesSecondary() {
        val primary = listOf(
            LrcLine(10_000L, "Line one"),
            LrcLine(11_000L, "Line two"),
            LrcLine(15_000L, "Line three"),
        )
        // Line one has no secondary; 10900ms belongs to Line two (11000ms, diff 100ms < diff 900ms)
        val secondary = listOf(
            LrcLine(10_900L, "Sec two"),
            LrcLine(15_050L, "Sec three"),
        )
        val paired = pairLrcLines(primary, secondary)
        assertNull(paired[0].secondary)
        assertEquals("Sec two", paired[1].secondary)
        assertEquals("Sec three", paired[2].secondary)
    }

    @Test
    fun rejectsBeyondMaxOffsetAndPreservesDuplicateTimestampsInOrder() {
        val primary = listOf(
            LrcLine(5_000L, "Chorus A"),
            LrcLine(5_000L, "Chorus B"),
            LrcLine(20_000L, "Far line"),
        )
        val secondary = listOf(
            LrcLine(5_000L, "Sub A"),
            LrcLine(5_000L, "Sub B"),
            LrcLine(23_000L, "Too far"),
        )
        val paired = pairLrcLines(primary, secondary, maxOffsetMs = 1500L)
        assertEquals("Sub A", paired[0].secondary)
        assertEquals("Sub B", paired[1].secondary)
        assertNull(paired[2].secondary)
        // Ensure parseLrc behavior is unchanged
        assertEquals(2, parseLrc("[00:05.00][00:10.00]Repeated").size)
    }

    @Test
    fun parsesNeteaseLyricBundleJson() {
        val raw = """
            {
              "lrc": { "lyric": "[00:10.00]夢を撃ち抜く瞬間に" },
              "romalrc": { "lyric": "[00:10.00]yume wo uchinuku shunkan ni" },
              "tlyric": { "lyric": "[00:10.00]In the moment we shoot through the dream" }
            }
        """.trimIndent()
        val bundle = parseNeteaseLyricBundle(Json.parseToJsonElement(raw).jsonObject)
        assertNotNull(bundle)
        assertEquals("[00:10.00]夢を撃ち抜く瞬間に", bundle.original)
        assertEquals("[00:10.00]yume wo uchinuku shunkan ni", bundle.romanized)
        assertEquals("[00:10.00]In the moment we shoot through the dream", bundle.translation)
        assertNull(parseNeteaseLyricBundle(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun fetchNeteaseLyricBundleUsesFixtureAndCachesAndNeverThrows() = runBlocking {
        var calls = 0
        val http = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): HttpResponse {
                calls++
                val body = when {
                    request.url.contains("/api/search/get") -> """
                        { "result": { "songs": [ { "id": 42, "name": "Sing Alive", "duration": 315000, "artists": [ { "name": "Roselia" } ] } ] } }
                    """.trimIndent()
                    request.url.contains("/api/song/lyric?id=42") -> """
                        {
                          "lrc": { "lyric": "[00:10.00]頂点へ" },
                          "romalrc": { "lyric": "[00:10.00]chouten he" },
                          "tlyric": { "lyric": "" }
                        }
                    """.trimIndent()
                    else -> "{}"
                }
                return HttpResponse(200, emptyMap(), body.encodeToByteArray())
            }
        }

        val first = fetchNeteaseLyricBundle(http, "Sing Alive", "Roselia", "Sing Alive", 315)
        assertNotNull(first)
        assertEquals("[00:10.00]頂点へ", first.original)
        assertEquals("[00:10.00]chouten he", first.romanized)
        assertNull(first.translation)
        val afterFirst = calls

        // Second call with same query hits cache without extra HTTP calls
        val second = fetchNeteaseLyricBundle(http, "Sing Alive", "Roselia", "Sing Alive", 315)
        assertEquals(first, second)
        assertEquals(afterFirst, calls)

        // Network failure returns null gracefully
        val failingHttp = object : HttpFetcher {
            override suspend fun send(request: HttpRequest): HttpResponse {
                throw RuntimeException("offline")
            }
        }
        val failed = fetchNeteaseLyricBundle(failingHttp, "Other Song Unique", "Other Artist", "", 200)
        assertNull(failed)
        assertTrue(afterFirst >= 2)
    }
}
