package io.max.paperplay.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/** The part of the libmpv client API the player uses (client.h). All calls are thread safe. */
internal interface MpvLib : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)
    /** [args] must end with a null entry. */
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    /** Returns an mpv_event that stays valid until the next call; read it at once. */
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer

    /** Asks for log messages of [minLevel] ("warn", "error"...) as EVENT_LOG_MESSAGE events. */
    fun mpv_request_log_messages(ctx: Pointer, minLevel: String): Int
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String
}

/** Constants from client.h. */
internal object MpvC {
    const val FORMAT_FLAG = 3
    const val FORMAT_DOUBLE = 5

    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_LOG_MESSAGE = 2
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_AUDIO_RECONFIG = 18
    const val EVENT_PLAYBACK_RESTART = 21
    const val EVENT_PROPERTY_CHANGE = 22

    const val END_EOF = 0
    const val END_STOP = 2
    const val END_QUIT = 3
    const val END_ERROR = 4
    const val END_REDIRECT = 5
}

/**
 * Finds `libmpv-2.dll` and loads it through JNA.
 *
 * Priority: the copy packaged with the app (`resources/mpv/windows/`), the development copy under `desktopApp/src/jvmMain/resources/`,
 * the `PAPERPLAY_MPV` environment variable (a folder or the library file). The library is not in git; see HANDOVER.md.
 * [lib] is null when nothing is found, and the player then stays on VLC.
 */
internal object MpvLoader {
    private val windows = System.getProperty("os.name", "").lowercase().startsWith("windows")
    private val libName = if (windows) "libmpv-2.dll" else "libmpv.so.2"

    val file: File? by lazy { find() }

    val lib: MpvLib? by lazy {
        val f = file ?: return@lazy null
        runCatching {
            // UTF-8 both ways: file names and titles must survive the trip.
            Native.load(f.absolutePath, MpvLib::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        }.onFailure { PlayLog.log("mpv", "could not load ${f.path}: $it") }.getOrNull()
    }

    val available: Boolean get() = file != null

    private fun find(): File? {
        val resources = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        val folders = buildList {
            if (resources != null) { add(File(resources, "mpv/windows")); add(File(resources, "mpv")); add(resources) }
            add(File("desktopApp/src/jvmMain/resources/mpv/windows"))
            add(File("../desktopApp/src/jvmMain/resources/mpv/windows"))
        }
        folders.map { File(it, libName) }.firstOrNull { it.isFile }?.let { return it }
        val env = System.getenv("PAPERPLAY_MPV") ?: return null
        val p = File(env)
        return if (p.isFile) p else File(p, libName).takeIf { it.isFile }
    }
}
