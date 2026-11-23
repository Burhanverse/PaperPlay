package io.max.paperplay.core.app

import io.max.paperplay.core.account.ChatIndexApi
import io.max.paperplay.core.account.IndexImport
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.FileAppender
import io.max.paperplay.core.backup.Fs
import io.max.paperplay.core.backup.writeZip

/**
 * The chat index as a file to keep or give to someone: a zip with one `index.json` ([io.max.paperplay.core.account.IndexFile]). An app
 * backup holds the same entry, so either can be imported. Everything goes through files on disk; a 250,000-song index is about 40 MB of
 * text and is never held in memory while writing.
 */
object IndexArchive {
    const val ENTRY = "index.json"

    /** Writes [chatIds] (all chats when null) to a zip at [zipPath]; [tmp] is a scratch path. Chats nobody else could join are left out. */
    suspend fun export(index: ChatIndexApi, zipPath: String, tmp: String, chatIds: Set<Long>? = null) {
        try {
            val out = FileAppender(tmp)
            index.exportTo(out, chatIds)
            out.close()
            writeZip(zipPath) { it.addFile(ENTRY, tmp) }
        } finally {
            Fs.deleteRecursively(tmp)
        }
    }

    /** Merges the index inside [source] (an index zip or an app backup). Throws [IllegalArgumentException] when it has none. */
    suspend fun import(index: ChatIndexApi, source: BackupSource, tmp: String): IndexImport {
        Fs.deleteRecursively(tmp)
        try {
            var found = false
            source.scan { e -> if (!e.isDirectory && e.name == ENTRY) { e.copyTo(tmp); found = true } }
            if (!found) throw IllegalArgumentException("This file has no chat index in it.")
            return index.importFrom(Fs.readText(tmp))
        } finally {
            Fs.deleteRecursively(tmp)
        }
    }
}
