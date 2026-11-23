package io.max.paperplay.core.account

import kotlinx.serialization.json.JsonObject

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Shared contracts for the own-account login, the chat index and the sync that uses it.
 * Each piece is built apart; they meet only through these types. Do not change a signature
 * here without telling the lead: add what you need in your own files.
 */

/** A group or channel the logged-in account is in. */
data class ChatInfo(val id: Long, val title: String, val isChannel: Boolean)

/** A song post found in one of the account's chats. [messageId] is TDLib's id. */
data class ChatPost(
    val chatId: Long,
    val messageId: Long,
    val uniqueId: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val size: Long,
    val mime: String,
    val fileName: String,
    val date: Long,
    /** The album from the file's own tags, as Telegram read it; empty when it has none. */
    val album: String = "",
    /** The tiny cover picture Telegram keeps in the post itself (JPEG), when the file has a cover. Costs no download. */
    val thumb: ByteArray? = null,
)

/** Where the login is. [Qr] carries the tg://login link to show as a code. */
sealed interface LoginStep {
    data object LoggedOut : LoginStep
    data object Starting : LoginStep
    data object Phone : LoginStep
    data class Qr(val link: String) : LoginStep
    data object Code : LoginStep
    data object Password : LoginStep
    data object Ready : LoginStep
    data class Failed(val message: String) : LoginStep
}

/** The owner's own Telegram account: login, chat listing, reading songs, copying. Built on its own TDLib client. */
interface AccountApi {
    /** Deletes the audio the account downloaded to play songs the bots cannot read. */
    suspend fun clearDownloads() {}
    /** Keeps those downloads under [maxBytes]. */
    /** Returns TDLib's reply (what was deleted), or null when not connected. [open] false leaves a closed session closed. */
    suspend fun trimDownloads(maxBytes: Long, open: Boolean = true): JsonObject? = null

    /** Which of [ids] this account can still see in [chatId]; null when Telegram could not be asked. */
    suspend fun existingPosts(chatId: Long, ids: List<Long>): Set<Long>? = null

    /** A line about the last [songsIn] read: posts it skipped because they are not songs, and how many small songs it kept. Empty before a read. */
    val lastReadNotes: String get() = ""

    /** The forum topics of [chatId] with the names Telegram gives them, by topic id. Empty when the chat has none or Telegram could not be asked. */
    suspend fun topicNames(chatId: Long): Map<Long, String> = emptyMap()

    val step: StateFlow<LoginStep>
    val loggedIn: StateFlow<Boolean>

    /** Starts the login (phone number flow). The flow then waits at [LoginStep.Phone]. */
    suspend fun begin()

    /** Starts the QR flow: [LoginStep.Qr] follows, then [LoginStep.Ready] once another device approves. */
    suspend fun beginQr()
    suspend fun phone(number: String)
    suspend fun code(code: String)
    suspend fun password(password: String)

    /** Logs out and forgets the session. The library and the index stay. */
    suspend fun logOut()

    /** Groups and channels, most recent first. Empty when not logged in. */
    suspend fun groupsAndChannels(): List<ChatInfo>

    /**
     * Song posts of [chatId], newest first, in batches as they are found, so a screen can show them while the
     * read goes on. With [afterMessageId] > 0 only posts newer than that id are read, and the flow ends there.
     */
    fun songsIn(chatId: Long, afterMessageId: Long = 0): Flow<List<ChatPost>>

    /** Like [songsIn], but a full read starts where [checkpoint] says and keeps it current, so a stopped read can go on instead of starting over. */
    fun songsIn(chatId: Long, afterMessageId: Long, checkpoint: ReadCheckpoint?): Flow<List<ChatPost>> = songsIn(chatId, afterMessageId)

    /**
     * How someone else can join [chatId]: its public name (no "@") and an invite link the account may see (empty when none). Null when
     * Telegram could not be asked. Costs one request per chat.
     */
    suspend fun chatLinks(chatId: Long): ChatLinks? = null

    /** Joins a chat by a t.me link (public name or invite); returns its id, or null when it could not be joined. */
    suspend fun joinChat(link: String): Long? = null

    /** Whether the account can see [chatId] (is a member, or it is public and can be opened). */
    suspend fun canSee(chatId: Long): Boolean = true

    /** Copies one post into [toChat] without a "forwarded" label. */
    suspend fun copyPost(fromChat: Long, messageId: Long, toChat: Long)

    /** Sends [content] as a document named [fileName] to the account's Saved Messages, with [tag] in its caption. */
    suspend fun saveToCloud(tag: String, fileName: String, content: ByteArray)

    /** The newest document in Saved Messages whose caption holds [tag], or null. */
    suspend fun loadFromCloud(tag: String): ByteArray?
}

/** One indexed post and what it means for the library. */
data class IndexedSong(
    val post: ChatPost,
    /** The unique id or message of the same song already in the main chat, when there is one. */
    val inMainChat: Boolean,
    /** The user moved (copied) it from here before. */
    val copied: Boolean,
    /** The main chat already has this song, but this file is better quality. */
    val better: Boolean = false,
    /** Why [better] holds, such as "FLAC instead of your MP3" or the two sizes; null otherwise. */
    val betterWhy: String? = null,
    /** An imported Spotify playlist still waits for this song. */
    val wanted: Boolean = false,
    /** The post is in the main chat itself. Copying it there would repeat it; it only needs adding to the library. */
    val ownChat: Boolean = false,
)

/** Progress of an index run, for screens and the notification. */
data class IndexProgress(val chatId: Long, val chatTitle: String, val found: Int, val running: Boolean)

/** The saved index of the account's chats. Persisted, so it survives restarts and logouts. */
/** What [ChatIndexApi.addPostsNotInLibrary] found: posts the account saw in the main chat, how many it added, and how many repeat posts of a file the library keeps as copies (the Duplicates page lists them). */
data class MainChatAdd(val seen: Int, val added: Int, val repeats: Int = 0)

/** How a chat can be found: public [username] (no "@"), an [inviteLink], and whether it is a channel. */
data class ChatLinks(val username: String, val inviteLink: String, val isChannel: Boolean)

/** What an index file import brought: chats and posts new to this device, and chats still to be joined. */
data class IndexImport(val chats: Int, val posts: Int, val notJoined: Int)

/** Where a read of a chat's history has got to: the search [filter] (0 audio, 1 documents) and the page cursor [from] inside it. */
class ReadCheckpoint(var filter: Int = 0, var from: Long = 0L)

/** What the index holds for one chat: songs read, when it was last read (epoch ms, 0 if never) and whether the whole history was read. */
data class ChatIndexDetails(val songs: Int, val refreshedAt: Long, val complete: Boolean)

interface ChatIndexApi {
    /** How many library songs exist only through the account (no post in a bot-readable chat), outside the main chat. */
    fun libraryOnlyCount(): Int = 0

    /**
     * Copies those songs into the main chat one by one, waiting [delayMs] between copies and while [paused] is true. [onStep] gets
     * (copied, failed, total, first failure reason) after each one. A song stays library-only until Telegram accepted its copy.
     */
    suspend fun copyLibraryOnly(delayMs: Long, paused: () -> Boolean, onStep: (Int, Int, Int, String?) -> Unit) {}

    /** Copies [songs] that are not in the main chat into it, like [copyLibraryOnly]. */
    suspend fun copySongsToMain(songs: List<io.max.paperplay.core.library.Song>, delayMs: Long, paused: () -> Boolean, onStep: (Int, Int, Int, String?) -> Unit) {}

    /**
     * Checks the library-only songs with the account and removes the ones whose post was deleted from Telegram. Returns (removed, still there,
     * not checked because Telegram could not be asked or the account cannot see that chat). Songs that still exist are left alone.
     */
    suspend fun clearDeletedLibraryOnly(): Triple<Int, Int, Int> = Triple(0, 0, 0)

    /** Details of an indexed chat, or null when it is not indexed. */
    fun details(chatId: Long): ChatIndexDetails? = null

    /** Chats that are indexed: id to title. */
    val indexedChats: StateFlow<Map<Long, String>>
    val progress: StateFlow<IndexProgress?>

    /** Reads a chat once from the start and saves what it finds; posts show up in [songs] as they are saved. */
    suspend fun indexChat(chat: ChatInfo, full: Boolean = false)

    /** Ends the read going on now. */
    fun stopRun() {}

    /** Reads only posts newer than the last one indexed for each indexed chat. */
    suspend fun refresh()

    /** Songs of [chatId] in the index, updating as the index grows. */
    fun songs(chatId: Long): Flow<List<IndexedSong>>

    /** Possible matches in the index for a wanted track (title, artist, length), best first. */
    suspend fun matchesFor(title: String, artist: String, durationSec: Int): List<ChatPost>

    /** Copies [post] to the main chat and remembers it. */
    suspend fun copyToMain(post: ChatPost)

    fun forget(chatId: Long)

    /** Adds [post] to the library so it can be played through the owner's account, with no copy in Telegram. */
    suspend fun addToLibrary(post: ChatPost) {}

    /** Adds every indexed song of the main chat that the library lacks. Returns how many were added. */
    suspend fun addMissingFromMainChat(): Int = 0

    /**
     * Adds every post of the main chat that the library has no row for, judged by message id, so a song posted twice is kept
     * twice like the chat has it. Uses only the posts the last full read of the chat in this session saw, so posts deleted
     * since are not brought back. [MainChatAdd.seen] is 0 when no full read has run yet.
     */
    suspend fun addPostsNotInLibrary(): MainChatAdd = MainChatAdd(0, 0, 0)

    /**
     * Removes songs of the main chat whose post is no longer in Telegram, judged by the posts the last full read of the chat in this
     * session saw (your account sees every post, so this is reliable). A song with another post of the same file is kept through that
     * post. Returns how many songs went, -1 when no full read has run, or -2 when the read saw far fewer posts than the library
     * holds, which is not trusted and removes nothing.
     */
    suspend fun pruneGoneFromMainChat(): Int = -1

    /** Indexed posts of the main chat that the library lacks: what the bot cannot read. */
    suspend fun missingFromMainChat(): List<ChatPost> = emptyList()

    /** Indexed chats with their counts and links, kept current. */
    val chats: StateFlow<List<IndexedChat>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    /** Counts every change of the index, for pages that re-read. */
    val changes: StateFlow<Long> get() = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** True while indexing is paused by the owner (on this device or a linked one); nothing is read until resumed. */
    val paused: StateFlow<Boolean> get() = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun setPaused(paused: Boolean) {}

    /** One page of a chat, newest first; [query] matches title, artist, album or file name. */
    fun page(chatId: Long, filter: IndexFilter, query: String, offset: Int, limit: Int): List<IndexedSong> = emptyList()

    /** Posts per [IndexFilter], for the chips. */
    fun counts(chatId: Long, query: String): List<Int> = List(IndexFilter.entries.size) { 0 }

    /** Every post a filter and search select. */
    fun select(chatId: Long, filter: IndexFilter, query: String): List<ChatPost> = emptyList()

    fun postsByIds(chatId: Long, ids: Collection<Long>): List<ChatPost> = emptyList()

    /** The cover Telegram keeps inside the post, when the file has one. */
    fun thumb(chatId: Long, messageId: Long): ByteArray? = null

    /** Posts of every indexed chat matching [query], for search. */
    fun searchAll(query: String, limit: Int): List<ChatPost> = emptyList()

    /** Works the library flags out again when the library or the index changed since the last time. Cheap when nothing did. */
    suspend fun updateFlags() {}

    /** Writes an index file (chats with their links, and posts) of [chatIds], or of every chat when null. */
    suspend fun exportTo(out: Appendable, chatIds: Set<Long>? = null) { out.append(export()) }

    /** Merges an index file in. Never deletes; chats the account is not in are marked to join. */
    suspend fun importFrom(text: String): IndexImport { import(text); return IndexImport(0, 0, 0) }

    /** Joins an imported chat through its link and marks it joined; false when it could not be joined. */
    suspend fun join(chatId: Long): Boolean = false

    /** The index as text, for saving to Telegram; and the way back. */
    suspend fun export(): String
    suspend fun import(json: String)
}
