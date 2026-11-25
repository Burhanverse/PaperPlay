package io.max.paperplay.core.sync

import io.max.paperplay.core.app.LibraryRepository
import io.max.paperplay.core.app.SecretStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock

/** A device that is linked right now. */
data class LanDevice(
    override val id: String,
    val name: String,
    /** A PC (takes the heavy background work). */
    override val desktop: Boolean,
    val host: String,
    val port: Int,
    /** True when the device is in the paired list (it can link without a code). False when it linked with the token mode. */
    val paired: Boolean,
    val connectedAt: Long,
    /** The app version the other device runs; null for a build too old to say. */
    val appVersion: String? = null,
    /** Nothing is synced: the versions differ and this device blocks that ([LanSync.blockVersions]). */
    val syncBlocked: Boolean = false,
    /** Nothing is synced: the other device said it blocks this version. */
    val syncRefused: Boolean = false,
) : DeviceInfo {
    /** Whether data goes between the two devices, so work may be split with it. */
    val syncs: Boolean get() = !syncBlocked && !syncRefused
}

/**
 * Raise it when synced data changes its meaning, so a build that reads it the old way neither sends nor gets any. Compared beside the app
 * version, which is the same for every build made between two releases.
 */
const val SYNC_FORMAT = 1

/** A device found by "Find devices automatically" or "Scan this network". Not linked yet. */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val desktop: Boolean,
    val host: String,
    val port: Int,
    val lastSeen: Long,
    /** Already paired: [LanSync.connect] works without a code. Otherwise use [LanSync.connectWithCode]. */
    val paired: Boolean,
)

/** A paired device: the long-term public key it was paired with. Shown in a "paired devices" list. */
data class PairedDevice(val id: String, val name: String, val publicKey: String, val pairedAt: Long)

/** A 6 digit code to show on this device. Valid until [expiresAt] (epoch ms) or its first use. */
data class PairingPin(val code: String, val expiresAt: Long)

/** Why a connection or sync failed. Show a short text per value. */
enum class LanFailure {
    /** Nothing answered at the address (or no saved address for the device). */
    Unreachable,
    /** Something answered but is not a PaperPlay device or the link broke. */
    Protocol,
    /** The code or password was wrong. */
    WrongCode,
    /** Too many wrong codes; wait and try again. */
    LockedOut,
    /** The other device only links with paired devices, or does not know this one. */
    NotPaired,
    /** The other device failed to prove it knows the code, key or token. Nothing was stored. */
    BadProof,
    /** This device is not listening, or has no such device. */
    NotReady,
    /** The app versions differ and this device blocks syncing then. */
    VersionBlocked,
    /** The app versions differ and the other device blocks syncing then. */
    VersionRefused,
}

/** Result of a connect. [device] is set when [ok]. */
data class ConnectResult(val ok: Boolean, val failure: LanFailure? = null, val device: LanDevice? = null)

/** What a sync changed: [songsChanged] and [playlistsChanged] on this device, [otherChanged] (songs and playlists together) on the other. A sync goes both ways. */
data class SyncResult(val ok: Boolean, val failure: LanFailure? = null, val songsChanged: Int = 0, val playlistsChanged: Int = 0, val otherChanged: Int = 0)

/** A message from a linked device that is not part of the sync (queue and now playing share, stubs for now). */
data class LanMessage(val deviceId: String, val type: String, val body: String)

/**
 * Local network sync and connect, no UI. One instance per app, from [createLanSync] (see `AppServices.enableLanSync`).
 * It is OFF until [start] is called.
 *
 * What the Connection page maps to:
 * - This device name: [deviceName] and [setDeviceName].
 * - Listening port: [port] (47872 unless changed with [setPort]); [listening] says whether it accepts links.
 * - Saved addresses list: [saved]; [addAddress] and [removeAddress].
 * - Find devices automatically: [discoveryOn] with [setDiscovery]; found devices appear in [discovered].
 * - Scan this network: [scanNetwork] (state in [scanning]).
 * - Encryption is always on: every link does an X25519 key exchange with fresh keys and uses AES-256-GCM. There is no switch.
 * - Connected devices list: [connected] with [disconnect], [syncNow], [unpair].
 * - This device's own addresses to read out to another device: [localAddresses].
 *
 * Pairing: a new device links with a code, once. Either the 6 digit PIN from [showPin] (short lived, single use), or the
 * password from [setPassword], or, only when [tokenMode] is on, by holding the same bot token (never sent; proved by
 * a keyed hash of the handshake). Both sides then store each other's public key and later links ([connect]) need no code.
 * While [onlyPaired] is on, NO new device can link (codes and the token are refused); paired devices still can.
 * Wrong codes are counted per address and overall; after five the address is locked out for five minutes.
 * Entering a code only pairs and links; the link carries song data only (favorites, plays, tags, pins, playlists) and never
 * tokens, the session or other secrets.
 */
interface LanSync {
    /** True while the server socket is open. */
    val listening: StateFlow<Boolean>
    /** The TCP port this device listens on and offers to others. */
    val port: StateFlow<Int>
    val deviceName: StateFlow<String>
    /** This device's own IPv4 addresses on the local network. */
    val localAddresses: StateFlow<List<String>>
    /** Linked devices now. */
    val connected: StateFlow<List<LanDevice>>
    /** The saved addresses, newest seen first. */
    val saved: StateFlow<List<KnownAddress>>
    /** Devices paired with this one, linked or not. */
    val paired: StateFlow<List<PairedDevice>>
    /** Devices heard from by discovery or found by a scan, newest first. */
    val discovered: StateFlow<List<DiscoveredDevice>>
    /** The PIN being shown, or null when none (or it expired). */
    val pin: StateFlow<PairingPin?>
    /** True when a password is set (the password itself is never exposed). */
    val passwordSet: StateFlow<Boolean>
    val onlyPaired: StateFlow<Boolean>
    /** Nothing is synced with a device that runs another app version (on unless switched off). Playing and controls go on. */
    val blockVersions: StateFlow<Boolean>
    val tokenMode: StateFlow<Boolean>
    val discoveryOn: StateFlow<Boolean>
    val scanning: StateFlow<Boolean>
    /** Short text for the last problem (for example "port in use"), or null. */
    val lastError: StateFlow<String?>
    /** Queue and now playing messages from linked devices (stubs). */
    val messages: SharedFlow<LanMessage>

    /** Opens the server socket and, if [discoveryOn], starts announcing. Safe to call twice. Saved as "on" for the next launch. */
    suspend fun start()
    /** Closes every link and the socket. */
    suspend fun stop()

    /** Shows a new 6 digit PIN, valid two minutes, replacing any shown before. */
    suspend fun showPin(): PairingPin
    suspend fun hidePin()
    /** Sets the password (at least 6 characters) or clears it with null. */
    suspend fun setPassword(password: String?)
    /** Invalidates the PIN and the password at once, and clears the lockouts. Paired devices stay paired. */
    suspend fun resetCode()

    /** Links to a new device with the PIN or password shown on it, and pairs both ways. */
    suspend fun connectWithCode(host: String, port: Int, code: String): ConnectResult
    /** Links to a device with the same bot token (needs [tokenMode] here and there). */
    suspend fun connectWithToken(host: String, port: Int): ConnectResult
    /** Links to a paired device, trying every saved address of it, newest first (then a discovered one). */
    suspend fun connect(deviceId: String): ConnectResult
    /** Disconnects and keeps it that way: the device is not dialled, and its dials are refused, until [connect] is called for it. */
    suspend fun disconnect(deviceId: String)

    /** Paired devices the person disconnected by hand and who stay disconnected. */
    val held: StateFlow<Set<String>>

    /** Merges song data with one linked device, or with all when [deviceId] is null; both sides end equal. */
    suspend fun syncNow(deviceId: String? = null): SyncResult

    /** Forgets one paired device (and disconnects it). It needs a new code to link again. */
    suspend fun unpair(deviceId: String)
    suspend fun unpairAll()

    /**
     * Adds an address like `192.168.1.5` or `192.168.1.5:47872`. False when it is not an address. With [deviceId] it is one more
     * address of that device (a device moves between networks and keeps one entry).
     */
    suspend fun addAddress(text: String, label: String? = null, deviceId: String? = null): Boolean
    /** Changes a saved address ([KnownAddress.key]) to [text], keeping its device. False when [text] is not an address. */
    suspend fun editAddress(oldKey: String, text: String): Boolean
    /** Removes a saved address by [KnownAddress.key]. */
    suspend fun removeAddress(key: String)
    /** Tries every address of this network's /24 once on the port, asks who answers, and returns them. */
    suspend fun scanNetwork(): List<DiscoveredDevice>

    suspend fun setDeviceName(name: String)
    suspend fun setPort(port: Int)
    suspend fun setOnlyPaired(on: Boolean)
    suspend fun setBlockVersions(on: Boolean)

    /** Syncs with one device although the versions differ, and lets it sync until the link ends. */
    suspend fun forceSync(deviceId: String): SyncResult
    suspend fun setTokenMode(on: Boolean)
    suspend fun setDiscovery(on: Boolean)

    /** Stub: sends this device's queue (song unique ids and the playing index) to a linked device. */
    suspend fun sendQueue(deviceId: String, uniqueIds: List<String>, index: Int): Boolean
    /** Stub: sends the playing song and position to a linked device. */
    suspend fun sendNowPlaying(
        deviceId: String, uniqueId: String?, positionMs: Long, playing: Boolean, title: String? = null, artist: String? = null,
        durationMs: Long = 0, outName: String? = null, outKind: String? = null,
    ): Boolean

    /** Names of the paired devices this one is dialling right now, for a short "Connecting to ..." notice. */
    val connecting: StateFlow<List<String>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    /** What each linked device says it is playing, by device id. */
    val remoteNow: StateFlow<Map<String, RemoteNow>> get() = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    /** Asks a linked device to "play", "pause", "next", "previous", "seek:<ms>" or "play:<uniqueId>:<ms>" (play that song from there). False when it is not linked. */
    suspend fun sendControl(deviceId: String, action: String): Boolean = sendControl(deviceId, action, emptyList())

    /** As above, with "queue:<index>:<ms>" and [uids] to play that list from that song and place. */
    suspend fun sendControl(deviceId: String, action: String, uids: List<String>): Boolean = false

    /**
     * Sends [body] (with its own "t", such as "indexctl") to one linked device, or to all when [deviceId] is null. It arrives in
     * [messages] there. Only paired devices are listened to. False when nothing was linked.
     */
    suspend fun sendMessage(deviceId: String?, body: kotlinx.serialization.json.JsonObject): Boolean = false

    /** Asks one linked, paired device (its [LanDataSync.answer]) and waits; null when it did not answer in time or refused. */
    suspend fun request(deviceId: String, q: kotlinx.serialization.json.JsonObject, timeoutMs: Long): kotlinx.serialization.json.JsonObject? = null
}

/**
 * The heavy data that goes between linked devices besides the song state: library rows one device lacks and the chat index. Each
 * device pulls what it is missing during [LanSync.syncNow]; the other answers.
 */
interface LanDataSync {
    /** Answers [request] from the device [peerId]. Runs on the link's thread; keep each answer to a page. */
    fun answer(peerId: String, request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject

    /** This device's side: asks [peerId] for what it lacks through [ask] (a request and its answer) and stores it. Returns rows added. */
    suspend fun pull(peerId: String, ask: suspend (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject): Int

    /** Only the chat index, through [ask]; see [pull]. */
    suspend fun pullPosts(peerId: String, ask: suspend (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject) {}

    /** Only the listening time, through [ask]; see [pull]. */
    suspend fun pullListening(ask: suspend (kotlinx.serialization.json.JsonObject) -> kotlinx.serialization.json.JsonObject) {}

    /** Which of [ids] (song unique ids) still have their post in the main chat, so a deletion of them is stale. */
    fun stillPosted(ids: Set<String>): Set<String> = emptySet()
}

/** The song a linked device is playing. */
data class RemoteNow(
    val uniqueId: String?, val title: String?, val artist: String?, val playing: Boolean,
    /** Position and length when the message came, and when it came ([at], epoch ms), so the position can be carried forward without polling. */
    val positionMs: Long = 0, val durationMs: Long = 0, val at: Long = 0,
    /** Where that device's sound goes: its name ("realme Buds Air8 Pro", "Speakers (Realtek)") and kind: headphones, speakers or phone. */
    val outName: String? = null, val outKind: String? = null,
) {
    /** Heard from within the last 12 s; the sender repeats its state every 5 s, so an older one means the device went quiet. */
    fun fresh(now: Long): Boolean = at > 0 && now - at < 12_000

    /** True while the device really plays a song. */
    fun playingNow(now: Long): Boolean = playing && uniqueId != null && fresh(now)

    /** Where the song is now: the reported position moved on by the time since, while it plays. */
    fun positionAt(now: Long): Long {
        val moved = if (playing && at > 0) positionMs + (now - at).coerceAtLeast(0) else positionMs
        return if (durationMs > 0) moved.coerceAtMost(durationMs) else moved
    }
}

/** A control action from another device: "play:<uniqueId>:<ms>" and "seek:<ms>" carry a value, the rest are plain. */
sealed interface RemoteAction {
    data object Play : RemoteAction
    data object Pause : RemoteAction
    data object Next : RemoteAction
    data object Previous : RemoteAction
    data class Seek(val positionMs: Long) : RemoteAction
    data class PlaySong(val uniqueId: String, val positionMs: Long) : RemoteAction

    companion object {
        fun parse(action: String?): RemoteAction? = when {
            action == null -> null
            action == "play" -> Play
            action == "pause" -> Pause
            action == "next" -> Next
            action == "previous" -> Previous
            action.startsWith("seek:") -> action.removePrefix("seek:").toLongOrNull()?.let { Seek(it.coerceAtLeast(0)) }
            action.startsWith("play:") -> {
                val rest = action.removePrefix("play:")
                val cut = rest.lastIndexOf(':')
                if (cut <= 0) null else rest.substring(cut + 1).toLongOrNull()?.let { PlaySong(rest.substring(0, cut), it.coerceAtLeast(0)) }
            }
            else -> null
        }
    }
}

/** A cover to send: when it was chosen by hand (0 when found automatically), the image, and where it was found. */
class CoverData(val at: Long, val bytes: ByteArray, val sourceUrl: String?)

/** What [createLanSync] needs. */
class LanEnv(
    val library: LibraryRepository,
    /** Holds this device's private identity key and the password. Never sent anywhere. */
    val secrets: SecretStore,
    val scope: CoroutineScope,
    val isDesktop: Boolean,
    /** Name used until the user sets one. */
    val defaultName: String,
    /** The bot token, only to prove it is the same one on both devices. Never sent. */
    val botToken: () -> String?,
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The hand-chosen cover of a song, as (when it was chosen, image bytes); null when it has none to give. */
    val coverBytes: (uniqueId: String) -> CoverData? = { null },
    /** Stores a hand-chosen cover another device sent, keeping the time it was chosen. */
    val saveCover: (uniqueId: String, at: Long, bytes: ByteArray, sourceUrl: String?) -> Unit = { _, _, _, _ -> },
    /** Library rows and the chat index, pulled from each linked device on every sync. */
    val data: LanDataSync? = null,
    val appVersion: String = "",
)

/** Builds the sockets, crypto and discovery (Android and desktop share one implementation). Does not start it. */
expect fun createLanSync(env: LanEnv): LanSync
