package io.max.paperplay.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.Executors

/**
 * VLCJ-backed engine for desktop JVM playback.
 *
 * Supports every audio format and lossless container that libvlc provides:
 * FLAC, ALAC (Apple Lossless), AAC / M4A, MP3, Opus, Vorbis (OGG), WAV, AIFF,
 * WMA, TTA, Musepack, Matroska audio, and more — all decoded via the bundled
 * libvlc runtime without requiring VLC to be installed by the end user.
 *
 * [VlcLoader.init] is called once per process (before the first factory) to
 * locate the bundled libvlc and point libvlc at its own plugin directory.
 * All libvlc calls run on one dedicated executor thread because vlcj forbids
 * calling the player from its own event thread.
 *
 * [vlcArgs] are extra libvlc options; tests pass "--aout=dummy" to prevent
 * real audio device access.
 */
class JvmPlayerEngine(vararg vlcArgs: String) : PlayerEngine {
    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _bufferedMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val bufferedMs: StateFlow<Long> = _bufferedMs
    override val currentIndex: StateFlow<Int> = _currentIndex
    private val _info = MutableStateFlow<OutputInfo?>(null)
    override val outputInfo: StateFlow<OutputInfo?> = _info
    override val supportsBitPerfect: Boolean = false
    override val bitPerfectStatus: StateFlow<BitPerfectStatus> = MutableStateFlow(
        BitPerfectStatus.Unsupported("Switch to the mpv audio engine for bit-perfect output"),
    )
    @Volatile private var eqGains: List<Float>? = null

    // After a seek libvlc can report the end of the stream when the data for the new position was not there in time (a song still
    // downloading). That is not the end of the song: it is opened again at the place that was asked for, twice at most.
    @Volatile private var resumeAtMs = 0L
    @Volatile private var prematureRetries = 0
    @Volatile private var seekTarget = -1L
    @Volatile private var seekAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var firstTick = false

    private val factory = run {
        val loaderArgs = VlcLoader.init()
        // Prepend plugin-path and no-video before any caller-supplied vlcArgs.
        // WASAPI output and the high quality resampler; callers' own args (tests pass --aout=dummy) come last and win.
        MediaPlayerFactory("--no-video", "--aout=mmdevice", "--audio-resampler=soxr", *loaderArgs.toTypedArray(), *vlcArgs)
    }
    private val player = factory.mediaPlayers().newMediaPlayer()
    private val http = lazy { StreamHttpServer() }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-cmd").apply { isDaemon = true } }
    private var queue = emptyList<QueueItem>()

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // libvlc has no audio output until playback starts, so a volume set earlier is applied now.
            override fun playing(mp: MediaPlayer) {
                _state.value = PlayerState.Playing
                mp.audio().setVolume((volume * 100).toInt()); if (rate != 1f) mp.controls().setRate(rate)
                val resume = resumeAtMs
                if (resume > 0) { resumeAtMs = 0; mp.controls().setTime(resume) }
                // The equalizer needs an audio output too, so it is applied again for each new track.
                applyEqualizer(mp)
                publishInfo(mp)
                val i = _info.value
                PlayLog.log("vlc", "playing after ${System.currentTimeMillis() - startedAt}ms codec=${i?.codec} rate=${i?.sampleRateHz}Hz ch=${i?.channels} bitrate=${i?.bitrateKbps}kbps device=${i?.device}")
            }
            override fun paused(mp: MediaPlayer) { _state.value = PlayerState.Paused }
            override fun error(mp: MediaPlayer) { _state.value = PlayerState.Error }
            override fun lengthChanged(mp: MediaPlayer, newLength: Long) { _durationMs.value = newLength }
            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                _positionMs.value = newTime
                if (!firstTick && startedAt > 0) { firstTick = true; PlayLog.log("vlc", "first position tick ${System.currentTimeMillis() - startedAt}ms after start, at ${newTime}ms") }
            }
            override fun finished(mp: MediaPlayer) = cmd {
                val now = System.currentTimeMillis()
                val dur = _durationMs.value
                val at = if (seekTarget >= 0 && now - seekAt < 8_000) seekTarget else _positionMs.value
                if (dur > 10_000 && at < dur - 5_000 && prematureRetries < 2 && _currentIndex.value in queue.indices) {
                    prematureRetries++
                    resumeAtMs = at
                    start(_currentIndex.value, retry = true)
                    return@cmd
                }
                if (_currentIndex.value + 1 < queue.size) start(_currentIndex.value + 1)
                else _state.value = PlayerState.Ended
            }
        })
    }

    private fun cmd(block: () -> Unit) { io.execute(block) }

    private fun start(index: Int, retry: Boolean = false, startPositionMs: Long = 0L) {
        val item = queue[index]
        val safePos = startPositionMs.coerceAtLeast(0L)
        if (!retry) { prematureRetries = 0; resumeAtMs = safePos; seekTarget = if (safePos > 0L) safePos else -1L }
        _currentIndex.value = index
        startedAt = System.currentTimeMillis(); firstTick = false
        _state.value = PlayerState.Loading
        if (!retry) { _positionMs.value = safePos; _durationMs.value = 0 }
        val mrl = item.source?.let { http.value.register(item.uri, it) } ?: item.uri
        // The stream is a loopback server fed from disk, so libvlc's default one second of network buffering only delays the first sound.
        val fast = arrayOf(":network-caching=150", ":file-caching=150")
        if (normalize) player.media().play(mrl, *fast, ":audio-filter=normvol", ":norm-max-level=1.0") else player.media().play(mrl, *fast)
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) = setQueue(items, startIndex, 0L)

    override fun setQueue(items: List<QueueItem>, startIndex: Int, startPositionMs: Long) {
        val safePos = startPositionMs.coerceAtLeast(0L)
        if (items.isNotEmpty()) {
            _currentIndex.value = startIndex.coerceIn(items.indices)
            _positionMs.value = safePos
        }
        cmd {
            queue = items
            if (items.isEmpty()) {
                player.controls().stop()
                _currentIndex.value = -1
                _positionMs.value = 0L
                _state.value = PlayerState.Idle
            } else start(startIndex.coerceIn(items.indices), startPositionMs = safePos)
        }
    }

    override fun play() = cmd {
        if (_state.value == PlayerState.Ended) start(_currentIndex.value) else player.controls().play()
    }

    override fun pause() = cmd { player.controls().setPause(true) }

    override fun next() = cmd { if (_currentIndex.value + 1 < queue.size) start(_currentIndex.value + 1) }

    override fun previous() = cmd {
        val i = _currentIndex.value
        if (i > 0 && _positionMs.value <= RESTART_THRESHOLD_MS) start(i - 1) else if (i >= 0) player.controls().setTime(0)
    }

    override fun seek(positionMs: Long) = cmd { seekTarget = positionMs; seekAt = System.currentTimeMillis(); player.controls().setTime(positionMs) }
    @Volatile private var volume = 1f
    @Volatile private var rate = 1f
    @Volatile private var normalize = false

    override fun setSpeed(speed: Float) {
        rate = speed.coerceIn(0.5f, 2f)
        cmd { player.controls().setRate(rate); publishInfo(player) }
    }

    /** Takes effect from the next track. */
    override fun setNormalize(on: Boolean) { normalize = on }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        cmd { player.audio().setVolume((this.volume * 100).toInt()); publishInfo(player) }
    }

    override fun setEqualizer(gainsDb: List<Float>?) = cmd {
        eqGains = gainsDb
        applyEqualizer(player)
        publishInfo(player)
    }

    private fun applyEqualizer(mp: MediaPlayer) {
        val gains = eqGains
        if (gains == null || gains.size != EQ_BANDS_HZ.size) { mp.audio().setEqualizer(null); return }
        val eq = factory.equalizer().newEqualizer()
        // VLC has ten bands; each reads our curve at its own frequency.
        val vlcHz = listOf(60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000)
        val amps = vlcHz.map { eqGainAt(gains, it.toDouble()).coerceIn(-12f, 12f) }
        // VLC's equalizer does not protect against clipping, so the whole curve is lowered by its loudest boost.
        eq.setPreamp(-(amps.maxOrNull() ?: 0f).coerceAtLeast(0f))
        amps.forEachIndexed { b, db -> eq.setAmp(b, db) }
        mp.audio().setEqualizer(eq)
    }

    private fun publishInfo(mp: MediaPlayer) {
        val track = runCatching { mp.media().info()?.audioTracks()?.firstOrNull() }.getOrNull()
        val effects = buildList {
            if (eqGains != null) add("Equalizer")
            if (normalize) add("Normalise")
            if (rate != 1f) add("Speed ${rate}x")
            if (volume < 0.995f) add("Volume ${(volume * 100).toInt()}%")
        }
        val rate = track?.rate()?.takeIf { it > 0 }
        val ch = track?.channels()?.takeIf { it > 0 }
        val dev = runCatching { mp.audio().outputDevice() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "System default output"
        val note = "Windows mixes sound at the format set in Sound settings, so desktop playback is not bit-perfect even with no effects."
        _info.value = OutputInfo(
            codec = track?.codecName()?.takeIf { it.isNotBlank() },
            sampleRateHz = rate,
            channels = ch,
            bitrateKbps = track?.bitRate()?.takeIf { it > 0 }?.let { it / 1000 },
            device = dev,
            effects = effects,
            bitPerfect = false,
            details = listOf("Engine" to "VLC"),
            note = note,
            transport = TransportInfo(
                kind = TransportKind.NONE,
                verified = false,
                deviceName = dev,
                unverifiedReason = note,
            ),
        )
    }

    override fun release() {
        io.shutdown()
        io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        player.release()
        factory.release()
        if (http.isInitialized()) http.value.close()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3000L
    }
}

/** Which engine the desktop uses; set before the player is created (from the saved choice). VLC is the default. */
object PcEngine {
    @Volatile var useMpv = false
    /** Whether the mpv library was found, for a message in the settings. */
    val mpvFound: Boolean get() = MpvLoader.available
}

actual fun createPlayerEngine(): PlayerEngine = LazyPlayerEngine(
    factory = {
        val lib = if (PcEngine.useMpv) MpvLoader.lib else null
        if (PcEngine.useMpv && lib == null) PlayLog.log("mpv", "the mpv library was not found, so VLC plays")
        if (lib != null) MpvPlayerEngine(lib) else JvmPlayerEngine()
    },
    supportsBitPerfect = PcEngine.useMpv && MpvLoader.available,
)
