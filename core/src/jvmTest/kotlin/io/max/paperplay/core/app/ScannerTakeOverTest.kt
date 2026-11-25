package io.max.paperplay.core.app

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.core.library.LibraryChat
import io.max.paperplay.core.library.LibraryRepo
import io.max.paperplay.core.library.Song
import io.max.paperplay.core.telegram.FakeTdNative
import io.max.paperplay.core.telegram.TdClient
import io.max.paperplay.core.telegram.TdConfig
import io.max.paperplay.core.telegram.TdRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds

/** A read through an extra bot takes over the songs saved as account songs and leaves every other owner's songs alone. */
class ScannerTakeOverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val config = TdConfig(1, "hash", "123:fake-token", "/db", "/files", "key")
    private val chatId = -1001234567890L

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun song(n: Long, remote: String) = Song(
        chat_id = chatId, message_id = n * MESSAGE_ID_STEP, unique_id = "u$n", remote_id = remote, title = "Track $n", artist = "Artist $n", album = "",
        duration_sec = 180L, size = 500_000L, mime = "audio/mpeg", file_name = "track_$n.mp3", date = n, cover_path = null, release_id = null,
        cover_url = null, genre = null, year = null, metadata_source = null, favorite = false, cover_hi_res = false, user_edited = false,
        enrich_state = 0, lyrics = null, cached_bytes = 0, last_played_at = null, pinned = false, tags_at = 0, favorite_at = 0, cover_at = 0,
        instrumental = false, mood_scanned_at = 0, plays = 0, skips = 0, topic_id = null, sample_rate = null, bit_depth = null, codec = null,
    )

    @Test
    fun anExtraBotTakesOverAccountSongsOnly(): Unit = runBlocking {
        var now = 1_000L
        val db = LibraryDatabase(libraryDriver())
        val repo = LibraryRepository(LibraryRepo(db) { ++now }) { ++now }
        repo.addChat(chatId, chatId.toString())
        db.songsQueries.insertSong(song(1, ACCOUNT_REMOTE)) // only the account has it: the bot should take it over
        db.songsQueries.insertSong(song(2, "mainBotFileId")) // the main bot owns it
        db.songsQueries.insertSong(song(3, botRemote(77L, "otherBotFileId"))) // another bot owns it

        val lastMsgId = 3L * MESSAGE_ID_STEP
        val fake = FakeTdNative { _, req ->
            val extra = req["@extra"]
            when (req["@type"]!!.jsonPrimitive.content) {
                "createSupergroupChat", "getSupergroup" -> listOf(buildJsonObject { put("@type", "ok"); extra?.let { put("@extra", it) } })
                "getChat" -> listOf(
                    buildJsonObject {
                        put("@type", "chat"); extra?.let { put("@extra", it) }
                        put("id", chatId); put("title", "Vault"); put("last_message", buildJsonObject { put("id", lastMsgId) })
                    },
                )
                "getMessages" -> {
                    val ids = req["message_ids"]!!.jsonArray.map { it.jsonPrimitive.long }
                    listOf(
                        buildJsonObject {
                            put("@type", "messages"); extra?.let { put("@extra", it) }
                            put(
                                "messages",
                                buildJsonArray {
                                    for (id in ids) {
                                        val n = id / MESSAGE_ID_STEP
                                        if (id % MESSAGE_ID_STEP != 0L || n !in 1L..3L) continue
                                        add(
                                            buildJsonObject {
                                                put("@type", "message"); put("chat_id", chatId); put("id", id); put("date", n)
                                                put(
                                                    "content",
                                                    buildJsonObject {
                                                        put("@type", "messageAudio")
                                                        put(
                                                            "audio",
                                                            buildJsonObject {
                                                                put("title", "Track $n"); put("performer", "Artist $n"); put("duration", 180)
                                                                put("mime_type", "audio/mpeg"); put("file_name", "track_$n.mp3")
                                                                put(
                                                                    "audio",
                                                                    buildJsonObject {
                                                                        put("id", n); put("size", 500_000L)
                                                                        put("remote", buildJsonObject { put("id", "botSees$n"); put("unique_id", "u$n") })
                                                                    },
                                                                )
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
                // Without an answer each of these waits for the 60 s timeout.
                else -> listOf(buildJsonObject { put("@type", "error"); put("code", 404); put("message", "not faked"); extra?.let { put("@extra", it) } })
            }
        }
        val client = TdClient(TdRuntime(fake, scope, Dispatchers.IO), config).also { it.start() }
        val scanner = Scanner(repo, scope, minPause = 1.milliseconds)
        scanner.parallelBatches = 1

        val seen = scanner.scanChat(LibraryChat(chatId, chatId.toString(), null, use_bot = true, walk_done = false), full = true, via = client, owner = 5L)

        assertNotNull(seen)
        assertEquals(botRemote(5L, "botSees1"), db.songsQueries.song(chatId, 1 * MESSAGE_ID_STEP).executeAsOne().remote_id, "the account song goes to the bot")
        assertEquals("mainBotFileId", db.songsQueries.song(chatId, 2 * MESSAGE_ID_STEP).executeAsOne().remote_id, "the main bot's song stays")
        assertEquals(botRemote(77L, "otherBotFileId"), db.songsQueries.song(chatId, 3 * MESSAGE_ID_STEP).executeAsOne().remote_id, "another bot's song stays")
        assertEquals(true, scanner.report.value?.contains("1 taken over from the account"), scanner.report.value)
    }
}
