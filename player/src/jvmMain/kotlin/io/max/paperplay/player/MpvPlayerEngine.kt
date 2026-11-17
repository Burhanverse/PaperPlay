package io.max.paperplay.player

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * libmpv engine for the desktop: any format FFmpeg reads, a real equalizer and bass ([MpvFilters]), correction profiles
 * (an exact response through `firequalizer`) and optional exclusive WASAPI output for bit-perfect playback.
 *
 * mpv runs the queue as a two-entry playlist: the song that plays and, once it has started, the next one appended, so
 * the change from one song to the next has no gap. All decisions run on one thread ([cmd]); the event thread only reads
 * mpv's events and posts work. [options] are extra mpv options set before start (tests pass `ao=null`).
 */
internal class MpvPlayerEngine(private val lib: MpvLib, options: Map<String, String> = emptyMap()) : PlayerEngine {
    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _bufferedMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    private val _info = MutableStateFlow<OutputInfo?>(null)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val bufferedMs: StateFlow<Long> = _bufferedMs
    override val currentIndex: StateFlow<Int> = _currentIndex
    override val outputInfo: StateFlow<OutputInfo?> = _info

    private val ctx: Pointer = lib.mpv_create() ?: error("mpv_create failed")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mpv-cmd").apply { isDaemon = true } }
    private val http = lazy { StreamHttpServer() }
    @Volatile private var closed = false

    private var queue = emptyList<QueueItem>()
    /** Queue indices in the order mpv holds them: the song that plays, then the one appended after it. */
    private val playlist = ArrayList<Int>()
    private var loaded = false
    @Volatile private var paused = false

    // After a seek the data for the new position may not be there yet (a song still downloading), and the stream can end early.
    // That is not the end of the song: it is opened again at the place that was asked for, twice at most.
    @Volatile private var resumeAtMs = 0L
    private var prematureRetries = 0
    @Volatile private var seekTarget = -1L
    @Volatile private var seekAt = 0L
    @Volatile private var startedAt = 0L
    private var lastPos = -1L

    @Volatile private var eqGains: List<Float>? = null
    @Volatile private var bass = 0f
    @Volatile private var normalize = false
    @Volatile private var volume = 1f
    @Volatile private var speed = 1f
    @Volatile private var exclusive = false
    @Volatile private var profile: List<Pair<Double, Double>>? = null
    @Volatile private var plan: MpvFilters.Plan? = null
    @Volatile private var graph: String? = null
    /** What mpv answered when the filter graph was last set (negative is an error), and the graph; for tests and the log. */
    @Volatile internal var filterResult = 0
    @Volatile internal var lastGraph: String? = null

    private val events: Thread

    init {
        // No window, no config files or scripts; the player is audio only.
        val base = mapOf(
            "vo" to "null", "video" to "no", "audio-display" to "no", "idle" to "yes", "keep-open" to "no",
            "gapless-audio" to "yes", "audio-pitch-correction" to "yes", "replaygain" to "no", "volume-max" to "100",
            "audio-client-name" to "PaperPlay", "terminal" to "no", "input-default-bindings" to "no", "load-scripts" to "no",
            "cache-secs" to "30", "network-timeout" to "30",
        )
        (base + options).forEach { (k, v) -> val rc = lib.mpv_set_option_string(ctx, k, v); if (rc < 0) PlayLog.log("mpv", "option $k=$v: ${lib.mpv_error_string(rc)}") }
        val rc = lib.mpv_initialize(ctx)
        check(rc >= 0) { "mpv_initialize failed: ${lib.mpv_error_string(rc)}" }
        lib.mpv_observe_property(ctx, 1, "time-pos", MpvC.FORMAT_DOUBLE)
        lib.mpv_observe_property(ctx, 2, "duration", MpvC.FORMAT_DOUBLE)
        lib.mpv_observe_property(ctx, 3, "pause", MpvC.FORMAT_FLAG)
        lib.mpv_observe_property(ctx, 4, "demuxer-cache-time", MpvC.FORMAT_DOUBLE)
        events = Thread({ eventLoop() }, "mpv-events").apply { isDaemon = true; start() }
    }

    // ---- mpv access

    private fun command(vararg args: String): Boolean {
        val rc = lib.mpv_command(ctx, arrayOf<String?>(*args, null))
        if (rc < 0) PlayLog.log("mpv", "command ${args.firstOrNull()}: ${lib.mpv_error_string(rc)}")
        return rc >= 0
    }

    private fun setProp(name: String, value: String) {
        val rc = lib.mpv_set_property_string(ctx, name, value)
        if (rc < 0) PlayLog.log("mpv", "property $name=$value: ${lib.mpv_error_string(rc)}")
    }

    private fun str(name: String): String? {
        val p = lib.mpv_get_property_string(ctx, name) ?: return null
        return try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) }
    }

    private fun int(name: String): Int? = str(name)?.toIntOrNull()

    private fun cmd(block: () -> Unit) { if (!closed) io.execute { runCatching(block).onFailure { PlayLog.log("mpv", "error: $it") } } }

    // ---- events

    private fun eventLoop() {
        while (!closed) {
            val ev = lib.mpv_wait_event(ctx, 0.25)
            when (ev.getInt(0)) {
                MpvC.EVENT_NONE -> {}
                MpvC.EVENT_SHUTDOWN -> return
                MpvC.EVENT_PROPERTY_CHANGE -> ev.getPointer(16)?.let { onProperty(it) }
                MpvC.EVENT_START_FILE -> cmd { onStartFile() }
                MpvC.EVENT_FILE_LOADED -> cmd { onFileLoaded() }
                MpvC.EVENT_AUDIO_RECONFIG -> cmd { publishInfo() }
                MpvC.EVENT_END_FILE -> { val reason = ev.getPointer(16)?.getInt(0) ?: MpvC.END_EOF; cmd { onEndFile(reason) } }
            }
        }
    }

    /** mpv_event_property: name at 0, format at 8, data at 16. */
    private fun onProperty(p: Pointer) {
        val name = p.getPointer(0)?.getString(0, "UTF-8") ?: return
        val format = p.getInt(8)
        val data = p.getPointer(16) ?: return
        when (name) {
            "time-pos" -> if (format == MpvC.FORMAT_DOUBLE) {
                val ms = (data.getDouble(0) * 1000).toLong().coerceAtLeast(0)
                if (abs(ms - lastPos) >= 100 || paused) { lastPos = ms; _positionMs.value = ms }
            }
            "duration" -> if (format == MpvC.FORMAT_DOUBLE) _durationMs.value = (data.getDouble(0) * 1000).toLong().coerceAtLeast(0)
            "demuxer-cache-time" -> if (format == MpvC.FORMAT_DOUBLE) _bufferedMs.value = (data.getDouble(0) * 1000).toLong().coerceAtLeast(0)
            "pause" -> if (format == MpvC.FORMAT_FLAG) {
                paused = data.getInt(0) != 0
                if (loaded && (_state.value == PlayerState.Playing || _state.value == PlayerState.Paused)) _state.value = if (paused) PlayerState.Paused else PlayerState.Playing
            }
        }
    }

    private fun onStartFile() {
        val pos = int("playlist-playing-pos") ?: 0
        // mpv moved on to the appended song by itself: drop what already played from its list.
        if (pos > 0 && pos < playlist.size) repeat(pos) { playlist.removeAt(0); command("playlist-remove", "0") }
        val first = playlist.firstOrNull() ?: return
        if (_currentIndex.value != first) {
            prematureRetries = 0; seekTarget = -1; resumeAtMs = 0
            _positionMs.value = 0; _durationMs.value = 0; lastPos = -1
            startedAt = System.currentTimeMillis()
        }
        _currentIndex.value = first
        loaded = false
        _state.value = PlayerState.Loading
    }

    private fun onFileLoaded() {
        loaded = true
        _state.value = if (paused) PlayerState.Paused else PlayerState.Playing
        val resume = resumeAtMs
        if (resume > 0) { resumeAtMs = 0; command("seek", "%.3f".format(java.util.Locale.ROOT, resume / 1000.0), "absolute+exact") }
        publishInfo()
        prepareNext()
        PlayLog.log("mpv", "playing after ${System.currentTimeMillis() - startedAt}ms ${_info.value?.let { "codec=${it.codec} rate=${it.sampleRateHz}Hz" } ?: ""}")
    }

    private fun prepareNext() {
        val next = (playlist.firstOrNull() ?: return) + 1
        if (next !in queue.indices || playlist.size > 1) return
        command("loadfile", mrlFor(next), "append")
        playlist.add(next)
    }

    private fun onEndFile(reason: Int) {
        when (reason) {
            MpvC.END_STOP, MpvC.END_QUIT, MpvC.END_REDIRECT -> return
            MpvC.END_ERROR -> { _state.value = PlayerState.Error; return }
        }
        val now = System.currentTimeMillis()
        val dur = _durationMs.value
        val at = if (seekTarget >= 0 && now - seekAt < 8_000) seekTarget else _positionMs.value
        val cur = playlist.firstOrNull() ?: _currentIndex.value
        if (dur > 10_000 && at < dur - 5_000 && prematureRetries < 2 && cur in queue.indices) {
            prematureRetries++
            resumeAtMs = at
            start(cur, retry = true)
            return
        }
        // With a next song appended mpv carries on by itself. Without one, the end of the queue (or a song that did not get appended).
        if (playlist.size > 1) return
        if (cur + 1 in queue.indices) start(cur + 1) else { loaded = false; _positionMs.value = _durationMs.value; _state.value = PlayerState.Ended }
    }

    // ---- playback

    private fun mrlFor(index: Int): String {
        val item = queue[index]
        return item.source?.let { http.value.register(item.uri, it) } ?: item.uri
    }

    private fun start(index: Int, retry: Boolean = false) {
        playlist.clear(); playlist.add(index)
        if (!retry) { prematureRetries = 0; resumeAtMs = 0; seekTarget = -1 }
        _currentIndex.value = index
        startedAt = System.currentTimeMillis()
        _state.value = PlayerState.Loading
        loaded = false
        if (!retry) { _positionMs.value = 0; _durationMs.value = 0; lastPos = -1 }
        setProp("pause", "no")
        command("loadfile", mrlFor(index), "replace")
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) = cmd {
        queue = items
        if (items.isEmpty()) {
            command("stop")
            playlist.clear()
            _currentIndex.value = -1
            _state.value = PlayerState.Idle
        } else start(startIndex.coerceIn(items.indices))
    }

    override fun play() = cmd { if (_state.value == PlayerState.Ended) start(_currentIndex.value) else setProp("pause", "no") }
    override fun pause() = cmd { setProp("pause", "yes") }
    override fun next() = cmd { val i = _currentIndex.value; if (i + 1 in queue.indices) start(i + 1) }
    override fun previous() = cmd {
        val i = _currentIndex.value
        if (i > 0 && _positionMs.value <= RESTART_THRESHOLD_MS) start(i - 1)
        else if (i >= 0) command("seek", "0", "absolute+exact")
    }
    override fun seek(positionMs: Long) = cmd {
        seekTarget = positionMs; seekAt = System.currentTimeMillis()
        command("seek", "%.3f".format(java.util.Locale.ROOT, positionMs.coerceAtLeast(0) / 1000.0), "absolute+exact")
    }

    // ---- sound settings

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        cmd { setProp("volume", "%.1f".format(java.util.Locale.ROOT, this.volume * 100)); publishInfo() }
    }

    override fun setSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 2f)
        cmd { setProp("speed", "%.3f".format(java.util.Locale.ROOT, this.speed)); publishInfo() }
    }

    override fun setEqualizer(gainsDb: List<Float>?) { eqGains = gainsDb; cmd { applyFilters() } }
    override fun setBass(db: Float) { bass = db; cmd { applyFilters() } }
    override fun setNormalize(on: Boolean) { normalize = on; cmd { applyFilters() } }
    override fun setCorrectionProfile(text: String?) { profile = text?.let { CorrectionProfile.parse(it) }; cmd { applyFilters() } }

    override fun setExclusiveOutput(on: Boolean) {
        exclusive = on
        cmd { setProp("audio-exclusive", if (on) "yes" else "no"); publishInfo() }
    }

    private fun applyFilters() {
        val p = MpvFilters.plan(eqGains, bass)
        val g = MpvFilters.graph(p, normalize, profile)
        plan = p; graph = g
        val rc = lib.mpv_set_property_string(ctx, "af", MpvFilters.afValue(g))
        filterResult = rc; lastGraph = g
        if (rc < 0) PlayLog.log("mpv", "audio filters refused (${lib.mpv_error_string(rc)}): $g")
        publishInfo()
    }

    // ---- output info

    private fun deviceName(): String {
        val id = str("audio-device") ?: return "System default output"
        val json = str("audio-device-list") ?: return id
        val m = Regex("\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"description\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json)
            .firstOrNull { it.groupValues[1].replace("\\\\", "\\") == id }
        return m?.groupValues?.get(2)?.takeIf { it.isNotBlank() } ?: if (id == "auto") "System default output" else id
    }

    private fun publishInfo() {
        val srcFmt = str("audio-params/format"); val srcRate = int("audio-params/samplerate"); val srcCh = int("audio-params/channel-count")
        val outFmt = str("audio-out-params/format"); val outRate = int("audio-out-params/samplerate"); val outCh = int("audio-out-params/channel-count")
        val ao = str("current-ao")
        val effects = buildList {
            val p = plan
            if (p != null && p.bands.any { abs(it) >= 0.05 }) add("Equalizer")
            if (p != null && p.bassDb >= 0.05) add("Bass +${"%.1f".format(java.util.Locale.ROOT, p.bassDb)} dB")
            if (profile != null) add("Correction profile")
            if (normalize) add("Normalise")
            if (speed != 1f) add("Speed ${speed}x")
            if (volume < 0.995f) add("Volume ${(volume * 100).toInt()}%")
        }
        val same = srcFmt != null && srcFmt == outFmt && srcRate == outRate && srcCh == outCh
        val wasapiExclusive = exclusive && ao?.startsWith("wasapi") == true
        val perfect = wasapiExclusive && effects.isEmpty() && same
        val details = buildList {
            if (srcFmt != null) add("Decoder output" to "$srcFmt, ${srcRate ?: "?"} Hz, ${srcCh ?: "?"} ch")
            if (outFmt != null) add("Device format" to "$outFmt, ${outRate ?: "?"} Hz, ${outCh ?: "?"} ch" + if (ao != null) " (${if (wasapiExclusive) "WASAPI exclusive" else ao})" else "")
            plan?.let {
                add("Curve peak" to "%+.1f dB".format(java.util.Locale.ROOT, it.peakDb))
                add("Level trim" to "bass %+.1f dB, rest %+.1f dB".format(java.util.Locale.ROOT, it.lowTrimDb, it.highTrimDb))
            }
        }
        _info.value = OutputInfo(
            codec = str("audio-codec-name")?.takeIf { it.isNotBlank() }?.uppercase(),
            sampleRateHz = srcRate?.takeIf { it > 0 },
            channels = srcCh?.takeIf { it > 0 },
            bitrateKbps = str("audio-bitrate")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
            device = deviceName(),
            outputRateHz = outRate?.takeIf { it > 0 },
            effects = effects,
            bitPerfect = perfect,
            note = when {
                perfect -> "Exclusive WASAPI output: the samples reach the sound device in the format they were decoded in."
                effects.isNotEmpty() -> "Effects change the sound before it reaches the output."
                !exclusive -> "Windows mixes sound at the format set in Sound settings. Turn on exclusive output for bit-perfect playback."
                !same -> "The device did not accept the decoded format, so mpv converts it."
                else -> null
            },
            details = details,
        )
    }

    override fun release() {
        if (closed) return
        closed = true
        lib.mpv_wakeup(ctx)
        events.join(2000)
        io.shutdown()
        io.awaitTermination(2, TimeUnit.SECONDS)
        lib.mpv_terminate_destroy(ctx)
        if (http.isInitialized()) http.value.close()
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3000L
    }
}
