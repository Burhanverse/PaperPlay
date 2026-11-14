package io.max.paperplay.compose

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.max.paperplay.core.app.audioExtensions
import io.max.paperplay.ui.app.FolderScan
import io.max.paperplay.ui.app.FolderSong

/** The real path of a storage-provider document id such as "primary:Music/Rock" or "1234-ABCD:Music" (an SD card); null if it has no volume. */
private fun volumePath(docId: String): java.io.File? {
    val i = docId.indexOf(':')
    if (i <= 0) return null
    val volume = docId.substring(0, i)
    val root = if (volume == "primary") android.os.Environment.getExternalStorageDirectory() else java.io.File("/storage/$volume")
    return java.io.File(root, docId.substring(i + 1))
}

/** The real folder of a picked tree, on the phone or an SD card. Whether it can be read is up to the audio permission. */
internal fun treeFolder(tree: Uri): java.io.File? = volumePath(DocumentsContract.getTreeDocumentId(tree))

/** The real file of a picked song when it lives on the phone's or an SD card's storage and can be read now; null means copy it. */
internal fun pickedFile(uri: Uri): java.io.File? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    return runCatching { volumePath(DocumentsContract.getDocumentId(uri)) }.getOrNull()?.takeIf { it.isFile && it.canRead() }
}

/** Like [scanFolderTree] but for a folder read in place: the songs are used where they are, never copied or deleted. */
internal fun scanFolderPath(dir: java.io.File): FolderScan {
    fun songs(files: Sequence<java.io.File>, base: java.io.File) = files.filter { it.isFile && it.extension.lowercase() in audioExtensions }
        .map { FolderSong(it.path, it.relativeTo(base).path, it.length()) }.toList()
    val top = songs(dir.listFiles().orEmpty().asSequence(), dir)
    val subs = dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        .associate { it.name to songs(it.walkTopDown().maxDepth(MAX_DEPTH), it) }.filterValues { it.isNotEmpty() }
    io.max.paperplay.core.app.appLog("folder", "in place $dir: ${top.size} songs, ${subs.size} subfolders")
    return FolderScan(top, subs) { chosen, ready -> ready(chosen.map { it.id }, false) }
}

private const val MAX_DEPTH = 6
private const val MAX_SONGS = 5000

/**
 * Lists the songs of a folder picked with the system folder picker: those directly in it, and those of each subfolder apart.
 * [copy] makes a real file of one document (called later, only for the songs the user chose) and returns its path.
 */
internal fun scanFolderTree(cr: ContentResolver, tree: Uri, copy: (Uri) -> String?): FolderScan {
    val root = DocumentsContract.getTreeDocumentId(tree)
    var count = 0
    /** The songs under [docId], with names relative to the folder; [prefix] is the path so far. */
    fun walk(docId: String, prefix: String, depth: Int, out: MutableList<FolderSong>, topOnly: Boolean, subs: MutableMap<String, MutableList<FolderSong>>?) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        cr.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && count < MAX_SONGS) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth >= MAX_DEPTH) continue
                    if (topOnly && subs != null) {
                        val list = subs.getOrPut(name) { mutableListOf() }
                        walk(id, "", depth + 1, list, false, null)
                    } else walk(id, "$prefix$name/", depth + 1, out, false, null)
                } else if (name.substringAfterLast('.', "").lowercase() in audioExtensions) {
                    out += FolderSong(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), prefix + name, if (c.isNull(3)) 0L else c.getLong(3))
                    count++
                }
            }
        }
    }
    val top = mutableListOf<FolderSong>()
    val subs = sortedMapOf<String, MutableList<FolderSong>>(String.CASE_INSENSITIVE_ORDER)
    walk(root, "", 0, top, true, subs)
    io.max.paperplay.core.app.appLog("folder", "scanned $tree: ${top.size} songs, ${subs.size} subfolders (${subs.values.sumOf { it.size }} songs), ${top.count { it.size < io.max.paperplay.core.app.MIN_UPLOAD_BYTES }} small")
    return FolderScan(top, subs.filterValues { it.isNotEmpty() }) { chosen, ready ->
        ready(chosen.mapNotNull { copy(Uri.parse(it.id)) }, true)
    }
}
