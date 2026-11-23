package io.max.paperplay.core.update

import io.max.paperplay.core.integrations.HttpFetcher
import io.max.paperplay.core.integrations.HttpRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the update check found and how far an update has got. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState

    /** [asset] is null where there is no installer to run (Linux): the release page is offered instead. */
    data class Available(val release: ReleaseInfo, val asset: ReleaseAsset?) : UpdateState
    data class Downloading(val release: ReleaseInfo, val fraction: Float) : UpdateState
    data class Ready(val release: ReleaseInfo, val path: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class Downloaded(val path: String, val sha256: String)

/** Why an install did not start, in words for the person; null from [UpdateInstaller.install] means it started. */
class InstallBlocked(val message: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** What the About page needs to show and set up Shizuku. */
interface ShizukuControl {
    val installed: Boolean
    val running: Boolean
    /** Shizuku has allowed this app. */
    val allowed: Boolean

    /** Asks Shizuku to allow this app (its own dialog), or opens Shizuku when it is not running. */
    fun ask()
}

/** The part that differs by platform: fetching the file and handing it to the system. */
interface UpdateInstaller {
    /** Android only: Shizuku, an optional way to install without the system confirmation screen. Null where there is no such choice. */
    val shizuku: ShizukuControl? get() = null

    /** Streams [url] to a file named [name] and returns where it went and its SHA-256 (lower-case hex). Throws on failure. */
    suspend fun download(url: String, name: String, onProgress: (Float) -> Unit): Downloaded

    /** Starts the install. Null when it started; otherwise why it could not (a missing permission, for example). */
    suspend fun install(path: String): InstallBlocked?

    fun delete(path: String)
}

/** The person's update choices, kept as settings of this device. */
class UpdatePrefs(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    var beta: Boolean
        get() = read("update_channel") == "beta"
        set(v) = write("update_channel", if (v) "beta" else "stable")
    var auto: Boolean
        get() = read("update_auto") != "false"
        set(v) = write("update_auto", v.toString())
    var shizuku: Boolean
        get() = read("update_installer") == "shizuku"
        set(v) = write("update_installer", if (v) "shizuku" else "system")
    var skipped: String?
        get() = read("update_skipped")?.takeIf { it.isNotEmpty() }
        set(v) = write("update_skipped", v.orEmpty())
    var lastCheck: Long
        get() = read("update_last_check")?.toLongOrNull() ?: 0L
        set(v) = write("update_last_check", v.toString())
}

/** What the in-app change log page shows. */
sealed interface ChangelogState {
    data object Idle : ChangelogState
    data object Loading : ChangelogState
    data class Ready(val entries: List<ChangelogVersionEntry>) : ChangelogState
    data class Failed(val message: String) : ChangelogState
}

/** Looks for a newer release on GitHub, downloads it, checks its SHA-256 against the published list and hands it to the installer. */
class UpdateService(
    private val http: HttpFetcher,
    private val installer: UpdateInstaller?,
    val prefs: UpdatePrefs,
    private val platform: UpdatePlatform,
    val installed: Version,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _changelog = MutableStateFlow<ChangelogState>(ChangelogState.Idle)
    val changelog: StateFlow<ChangelogState> = _changelog
    private var cachedReleases: List<ReleaseInfo> = emptyList()

    val shizuku get() = installer?.shizuku

    /** True when automatic checks are on and the last one is over a day old. */
    fun autoCheckDue() = prefs.auto && now() - prefs.lastCheck > DAY_MS

    suspend fun check() {
        _state.value = UpdateState.Checking
        try {
            val r = http.send(
                HttpRequest(
                    FEED_URL,
                    headers = mapOf("accept" to "application/vnd.github+json", "user-agent" to "PaperPlay/$installed"),
                ),
            )
            if (r.status != 200) throw IllegalStateException("GitHub answered ${r.status}")
            prefs.lastCheck = now()
            val releases = ReleaseFeed.parse(r.text)
            cachedReleases = releases
            val found = ReleaseFeed.newest(releases, installed, prefs.beta, prefs.skipped)
            _state.value = if (found == null) UpdateState.UpToDate(now()) else UpdateState.Available(found, ReleaseFeed.assetFor(found, platform))
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "The update check failed")
        }
    }

    /** Loads the full change log history from `CHANGELOG.md` and GitHub Releases for the in-app Change log page. */
    suspend fun loadChangelog(force: Boolean = false) {
        if (!force && _changelog.value is ChangelogState.Ready) return
        _changelog.value = ChangelogState.Loading
        try {
            val mdEntries = runCatching {
                val r = retryConnect {
                    http.send(HttpRequest(CHANGELOG_URL, headers = mapOf("user-agent" to "PaperPlay/$installed")))
                }
                if (r.status == 200) ChangelogParser.parseChangelogMarkdown(r.text) else emptyList()
            }.getOrDefault(emptyList())

            val releases = if (cachedReleases.isNotEmpty() && !force) {
                cachedReleases
            } else {
                runCatching {
                    val r = retryConnect {
                        http.send(
                            HttpRequest(
                                FEED_URL,
                                headers = mapOf("accept" to "application/vnd.github+json", "user-agent" to "PaperPlay/$installed"),
                            ),
                        )
                    }
                    if (r.status == 200) ReleaseFeed.parse(r.text).also { cachedReleases = it } else cachedReleases
                }.getOrDefault(cachedReleases)
            }

            val releaseEntries = releases.map(ChangelogParser::fromReleaseInfo)
            val merged = ChangelogParser.mergeEntries(mdEntries, releaseEntries)
            if (merged.isEmpty()) throw IllegalStateException("Could not load the change log from GitHub")
            _changelog.value = ChangelogState.Ready(merged)
        } catch (e: Exception) {
            _changelog.value = ChangelogState.Failed(e.message ?: "Could not load the change log")
        }
    }

    /** Does not offer this release again; a newer one still shows. */
    fun skip(release: ReleaseInfo? = null) {
        // The release the person was shown is skipped even if a later check changed the state meanwhile (it used to save nothing then).
        val tag = release?.tag ?: (_state.value as? UpdateState.Available)?.release?.tag ?: return
        prefs.skipped = tag
        if ((_state.value as? UpdateState.Available)?.release?.tag == tag) _state.value = UpdateState.UpToDate(now())
    }

    suspend fun download() {
        val s = _state.value as? UpdateState.Available ?: return
        val asset = s.asset ?: return
        val inst = installer ?: return
        _state.value = UpdateState.Downloading(s.release, 0f)
        try {
            if (!ReleaseFeed.isTrusted(asset.url)) throw IllegalStateException("The file is not from this project's releases")
            val sums = s.release.assets.firstOrNull { it.name == ReleaseFeed.CHECKSUMS }
                ?: throw IllegalStateException("This release has no checksum list")
            if (!ReleaseFeed.isTrusted(sums.url)) throw IllegalStateException("The checksum list is not from this project's releases")
            val expected = retryConnect { ReleaseFeed.parseChecksums(http.send(HttpRequest(sums.url)).text)[asset.name] }
                ?: throw IllegalStateException("No checksum for ${asset.name}")
            val file = retryConnect { inst.download(asset.url, asset.name) { _state.value = UpdateState.Downloading(s.release, it) } }
            if (!file.sha256.equals(expected, ignoreCase = true)) {
                inst.delete(file.path)
                throw IllegalStateException("The downloaded file does not match its checksum")
            }
            _state.value = UpdateState.Ready(s.release, file.path)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "The download failed")
        }
    }

    /** Starts the install of a downloaded update. Null when it started; otherwise the reason it could not. */
    suspend fun install(): InstallBlocked? {
        val s = _state.value as? UpdateState.Ready ?: return null
        return installer?.install(s.path)
    }

    /** A connect timeout is often one dead address of several; try again a few times. */
    private suspend fun <T> retryConnect(block: suspend () -> T): T {
        repeat(3) {
            try { return block() } catch (e: Exception) { if (e.message?.contains("connect timed out", ignoreCase = true) != true) throw e }
        }
        return block()
    }

    private companion object {
        const val FEED_URL = "https://api.github.com/repos/maxcodl/PaperPlay/releases?per_page=30"
        const val CHANGELOG_URL = "https://raw.githubusercontent.com/maxcodl/PaperPlay/main/CHANGELOG.md"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
