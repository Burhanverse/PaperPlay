package io.max.paperplay.core.dupes

import io.max.paperplay.core.library.PostCopy
import io.max.paperplay.core.library.Song
import kotlin.math.abs

const val GENERAL_TOPIC_ID = 1048576L

/** What the metadata and audio-info slices decide for the duplicate finder. */
interface SongRules {
    /** Codec/format name of the song, as shown (FLAC, MP3). */
    fun format(s: Song): String
    fun isLossless(format: String): Boolean

    /** Better first: higher tier, then more detail, then bitrate, then size. */
    fun compareQuality(a: Song, b: Song): Int

    /** comparableText(searchTitle(cleanTitle(title, artist))). */
    fun comparableTitle(s: Song): String

    /** comparableText(firstArtist(artist)). */
    fun comparableArtist(s: Song): String
}

enum class DuplicateKind {
    /** The identical file, posted more than once. */
    SameFile,

    /** Matching title, artist and length, in different files. Can occasionally be wrong. */
    LikelySameSong,
}

/** One post of a song in a chat. [song] is the library's row for it; null for a repeat the library doesn't list. */
data class DuplicatePost(
    val chatId: Long,
    val messageId: Long,
    val fileName: String,
    val size: Long,
    val topicId: Long?,
    val lossless: Boolean,
    val format: String,
    /** When it was posted, in seconds. */
    val date: Long,
    val song: Song? = null,
)

/** [keep] is the copy suggested to keep; [suggested] holds the message ids of [others] safe to suggest deleting. */
data class DuplicateGroup(
    val kind: DuplicateKind,
    val title: String,
    val artist: String,
    val keep: DuplicatePost,
    val others: List<DuplicatePost>,
    val suggested: Set<Long>,
)

/** The group without posts that are deleted by now: the next live post becomes the copy to keep; null when fewer than two are left. */
fun DuplicateGroup.without(gone: Set<Pair<Long, Long>>): DuplicateGroup? {
    if (gone.isEmpty()) return this
    val live = (listOf(keep) + others).filter { (it.chatId to it.messageId) !in gone }
    return if (live.size < 2) null else copy(keep = live.first(), others = live.drop(1))
}

enum class DuplicateSection { Extras, OtherTopics, Maybe }

fun sectionOf(g: DuplicateGroup): DuplicateSection = when {
    g.suggested.isNotEmpty() -> DuplicateSection.Extras
    g.kind == DuplicateKind.SameFile -> DuplicateSection.OtherTopics
    else -> DuplicateSection.Maybe
}

private fun List<String>.joinAnd() =
    if (size < 2) joinToString("") else "${dropLast(1).joinToString(", ")} and ${last()}"

/** Says in plain words why these posts count as repeats. */
fun describeDuplicate(g: DuplicateGroup, topicOf: (DuplicatePost) -> String?): String {
    val posts = listOf(g.keep) + g.others
    if (g.kind == DuplicateKind.SameFile) {
        val places = posts.mapNotNull(topicOf).distinct()
        if (places.size > 1) return "The exact same file was posted in ${places.joinAnd()}."
        val where = if (places.isEmpty()) "" else " in ${places.single()}"
        return "The exact same file was posted ${posts.size} times$where."
    }
    val lossy = g.others.filter { !it.lossless }.map { it.format }.distinct()
    if (g.keep.lossless && lossy.isNotEmpty()) {
        return "The same song in different quality: ${g.keep.format} is lossless, ${lossy.joinAnd()} isn't."
    }
    return "Same title, artist and length, but different files. They could be different versions."
}

/** The post that stays when the ticked ones go. Null if none would, which is never allowed. */
fun survivorOf(g: DuplicateGroup, isTicked: (DuplicatePost) -> Boolean): DuplicatePost? =
    (listOf(g.keep) + g.others).firstOrNull { !isTicked(it) }

/**
 * Surely worse than [keep], so it may be ticked on its own. Between two lossless files only measured depth and rate count: a smaller FLAC is
 * another release or master, not a worse copy. Lossy files go by size: the same format, or clearly smaller in another one.
 */
private fun worseQuality(post: DuplicatePost, keep: DuplicatePost): Boolean {
    if (keep.lossless != post.lossless) return keep.lossless
    if (!keep.lossless) return post.size * 3 < keep.size * 2 || (post.format == keep.format && post.size * 100 < keep.size * 95)
    val a = keep.song ?: return false
    val b = post.song ?: return false
    if (!measured(a) || !measured(b)) return false
    val depth = b.bit_depth!!.compareTo(a.bit_depth!!)
    val rate = b.sample_rate!!.compareTo(a.sample_rate!!)
    return depth <= 0 && rate <= 0 && depth + rate != 0
}

private fun measured(s: Song) = s.bit_depth != null && s.sample_rate != null

private val versionWords = Regex("\\b(remaster(?:ed)?|clean)\\b", RegexOption.IGNORE_CASE)

/** Titles are compared without words like these, so two files can match and still be different versions. */
private fun versionOf(s: Song) = versionWords.findAll(s.title).map { it.value.lowercase().removeSuffix("ed") }.toSet()

private fun sameVersion(a: Song, b: Song) = versionOf(a) == versionOf(b) && abs(a.duration_sec - b.duration_sec) <= LENGTH_TOLERANCE

/** The same format within a few percent of the size (a re-tag or re-cut of the same track): a re-upload, so the lesser one can go. */
private fun reupload(post: DuplicatePost, keep: DuplicatePost) =
    post.format == keep.format && abs(post.size - keep.size) <= keep.size * 0.08

/** A copy in General of a song already filed in a real topic: what's left when a move couldn't delete it. */
private fun leftoverInGeneral(keep: DuplicatePost, other: DuplicatePost) =
    other.topicId == GENERAL_TOPIC_ID && keep.topicId != null && keep.topicId != GENERAL_TOPIC_ID

private const val LENGTH_TOLERANCE = 3

private fun postOf(s: Song, rules: SongRules): DuplicatePost {
    val format = rules.format(s)
    return DuplicatePost(s.chat_id, s.message_id, s.file_name, s.size, s.topic_id, rules.isLossless(format), format, s.date, s)
}

/**
 * Finds repeats, from the library's [songs] and the [copies] it found beyond them.
 * A repeat in another topic isn't suggested for deletion: topics act as playlists.
 * With [onlyChat], only posts in that chat count.
 */
fun findDuplicates(songs: List<Song>, copies: List<PostCopy>, rules: SongRules, onlyChat: Long? = null): List<DuplicateGroup> {
    val groups = mutableListOf<DuplicateGroup>()
    val byUnique = songs.associateBy { it.unique_id }
    val copiesOf = LinkedHashMap<String, MutableList<PostCopy>>()
    for (c in copies) if (onlyChat == null || c.chat_id == onlyChat) copiesOf.getOrPut(c.unique_id) { mutableListOf() }.add(c)
    for ((uid, list) in copiesOf) {
        val song = byUnique[uid] ?: continue
        val listedHere = onlyChat == null || song.chat_id == onlyChat
        // Listed under a post elsewhere, so one of these has to be the one that stays.
        if (!listedHere && list.size < 2) continue
        val format = rules.format(song)
        fun of(c: PostCopy) = DuplicatePost(
            c.chat_id, c.message_id, c.file_name, c.size, c.topic_id, rules.isLossless(format), format, c.date,
        )
        val keep = if (listedHere) postOf(song, rules) else of(list.first())
        val others = (if (listedHere) list else list.drop(1)).map(::of)
        groups += DuplicateGroup(
            DuplicateKind.SameFile, song.title, song.artist, keep, others,
            others.filter { it.topicId == keep.topicId || leftoverInGeneral(keep, it) }.map { it.messageId }.toSet(),
        )
    }

    val byName = LinkedHashMap<String, MutableList<Song>>()
    for (s in songs) {
        if (s.duration_sec <= 0 || (onlyChat != null && s.chat_id != onlyChat)) continue
        val title = rules.comparableTitle(s)
        if (title.isEmpty()) continue
        byName.getOrPut("$title|${rules.comparableArtist(s)}") { mutableListOf() }.add(s)
    }
    for (same in byName.values) {
        if (same.size < 2) continue
        same.sortBy { it.duration_sec }
        fun flush(run: List<Song>) {
            if (run.size < 2) return
            // Best quality, then the larger file, then the newest post. Depth and rate are known only once a song was played, so unless all
            // lossless files have them the size decides: a file nobody played yet is not worse for that.
            val all = run.map { postOf(it, rules) }
            val byMeasure = all.all { !it.lossless || measured(it.song!!) }
            val posts = all.sortedWith { a, b ->
                val q = when {
                    a.lossless != b.lossless -> b.lossless.compareTo(a.lossless)
                    a.lossless && byMeasure -> rules.compareQuality(a.song!!, b.song!!)
                    else -> b.size.compareTo(a.size)
                }
                if (q != 0) q else b.date.compareTo(a.date)
            }
            val keep = posts.first()
            val others = posts.drop(1)
            groups += DuplicateGroup(
                DuplicateKind.LikelySameSong, keep.song!!.title, keep.song.artist, keep, others,
                // Lower quality always goes, in any topic. A re-upload of equal quality goes only from the same topic, as topics act as playlists.
                others.filter { sameVersion(it.song!!, keep.song) && (worseQuality(it, keep) || (it.topicId == keep.topicId && reupload(it, keep))) }
                    .map { it.messageId }.toSet(),
            )
        }
        // Runs of similar length, so an album version and a long mix stay apart.
        var run = mutableListOf(same.first())
        for (s in same.drop(1)) {
            if (s.duration_sec - run.last().duration_sec <= LENGTH_TOLERANCE) {
                run.add(s)
            } else {
                flush(run)
                run = mutableListOf(s)
            }
        }
        flush(run)
    }
    return groups
}
