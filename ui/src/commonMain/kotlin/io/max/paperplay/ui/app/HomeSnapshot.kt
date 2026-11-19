package io.max.paperplay.ui.app

import io.max.paperplay.core.library.Song
import io.max.paperplay.core.recs.DailyMix
import io.max.paperplay.core.recs.HomeMix
import io.max.paperplay.core.recs.MixGroup
import io.max.paperplay.core.recs.MixShelf
import io.max.paperplay.core.recs.MixTile
import io.max.paperplay.core.recs.ShelfKind

/**
 * The Home page as it was last shown, saved as song ids in a preference. On launch it is read back and drawn at once, with its
 * covers, while the real plan is built; the real plan then replaces it. Songs that left the library are dropped.
 */
internal object HomeSnapshot {
    const val PREF = "home_snapshot_v1"
    private const val FIELD = '\t'
    private const val ID = '\u001f'

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
    private fun ids(l: List<Song>) = l.joinToString(ID.toString()) { it.unique_id }

    fun encode(plan: HomeMix, mixes: List<DailyMix>): String = buildString {
        for (t in plan.tiles) appendLine("T$FIELD${clean(t.title)}$FIELD${t.cover.unique_id}$FIELD${ids(t.songs)}")
        for (s in plan.shelves) {
            if (s.kind == ShelfKind.SONGS) appendLine("S$FIELD${clean(s.title)}$FIELD${ids(s.songs)}")
            else {
                appendLine("A$FIELD${clean(s.title)}$FIELD${s.kind.name}")
                for (g in s.groups) appendLine("G$FIELD${clean(g.name)}$FIELD${g.cover.unique_id}$FIELD${ids(g.songs)}")
            }
        }
        for (m in mixes) appendLine("M$FIELD${m.id}$FIELD${clean(m.title)}$FIELD${ids(m.songs)}")
    }

    /** Every token of the saved text that could be a song id, for one query that fetches them all. */
    fun idsIn(raw: String): List<String> = raw.split(FIELD, '\n', ID).filter { it.isNotEmpty() }.distinct().take(900)

    /** The saved page and mixes, or null when nothing usable is saved. [lookup] finds a song by its id. */
    fun decode(raw: String?, lookup: (String) -> Song?): Pair<HomeMix, List<DailyMix>>? {
        if (raw.isNullOrBlank()) return null
        val cache = HashMap<String, Song?>()
        fun song(id: String) = cache.getOrPut(id) { lookup(id) }
        fun list(text: String) = text.split(ID).filter { it.isNotEmpty() }.mapNotNull(::song)
        val tiles = ArrayList<MixTile>()
        val shelves = ArrayList<MixShelf>()
        val mixes = ArrayList<DailyMix>()
        var groupsOf: Pair<String, ShelfKind>? = null
        var groups = ArrayList<MixGroup>()
        fun closeGroups() {
            groupsOf?.let { (title, kind) -> if (groups.isNotEmpty()) shelves += MixShelf(title, kind, emptyList(), groups) }
            groupsOf = null; groups = ArrayList()
        }
        for (line in raw.lineSequence()) {
            val p = line.split(FIELD)
            when (p.firstOrNull()) {
                "T" -> if (p.size >= 4) { closeGroups(); val c = song(p[2]); val l = list(p[3]); if (c != null && l.isNotEmpty()) tiles += MixTile(p[1], c, l) }
                "S" -> if (p.size >= 3) { closeGroups(); val l = list(p[2]); if (l.isNotEmpty()) shelves += MixShelf(p[1], ShelfKind.SONGS, l) }
                "A" -> if (p.size >= 3) { closeGroups(); groupsOf = p[1] to (runCatching { ShelfKind.valueOf(p[2]) }.getOrNull() ?: ShelfKind.ALBUMS) }
                "G" -> if (p.size >= 4 && groupsOf != null) { val c = song(p[2]); val l = list(p[3]); if (c != null && l.isNotEmpty()) groups += MixGroup(p[1], c, l) }
                "M" -> if (p.size >= 4) { closeGroups(); val l = list(p[3]); if (l.isNotEmpty()) mixes += DailyMix(p[1], p[2], l) }
            }
        }
        closeGroups()
        if (tiles.isEmpty() && shelves.isEmpty()) return null
        return HomeMix(tiles, shelves) to mixes
    }
}
