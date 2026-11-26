package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.max.paperplay.core.recs.WeatherKind
import io.max.paperplay.ui.AlertIcon
import io.max.paperplay.ui.StandbyOrientation
import io.max.paperplay.ui.StandbyWindowEffect
import io.max.paperplay.ui.rememberCharging
import io.max.paperplay.ui.rememberStandbyAlerts
import io.max.paperplay.ui.clockNow
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage
import io.max.paperplay.ui.rememberBatteryPercent
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.theme.schemeFromSeed
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Android standby: a near-black screen with a clock, the song and a slow procedural animation.
 * Tap or swipe down to leave. Callers show it on phones only.
 */
@Composable
fun StandbyScreen(state: PlaybackUiState, backend: PlayerControls, prefs: StandbyPrefs, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        StandbyWindowEffect(prefs.dim)
        StandbyOrientation(prefs.landscape)
        StandbyContent(state, backend, prefs, onClose)
    }
}

@Composable
private fun StandbyContent(state: PlaybackUiState, backend: PlayerControls, p: StandbyPrefs, onClose: () -> Unit) {
    var clock by remember { mutableStateOf(clockNow()) }
    val showClock = p.shows(StandbyWidget.Clock)
    // Once a second also when only the drift needs it, so the shift below is smooth.
    LaunchedEffect(showClock, p.pixelShift) { while (true) { clock = clockNow(); delay(if (showClock || p.pixelShift) 1000L else 20_000L) } }
    var sheet by remember { mutableStateOf(false) }
    // The first tap only shows the settings button; a second tap within a few seconds leaves standby.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    val tone = standbyTone(p, state)
    val song = state.current
    val web = backend as? CoreBackend

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (armed) onClose() else armed = true }) }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 160f) onClose() }) { _, dy -> total += dy }
            },
    ) {
        val wide = maxWidth > maxHeight
        val boxHeight = maxHeight
        // AMOLED burn-in: everything, the settings button included, drifts slowly along a path that never repeats exactly
        // (periods of 7 and 5 minutes), by up to 6% of the shorter side, so no pixel stays lit.
        val seconds = clock.hour * 3600 + clock.minute * 60 + clock.second
        val amp = minOf(maxWidth, maxHeight) * 0.015f
        val shiftX = if (p.pixelShift) amp * kotlin.math.sin(2.0 * kotlin.math.PI * seconds / 420.0).toFloat() else 0.dp
        val shiftY = if (p.pixelShift) amp * kotlin.math.sin(2.0 * kotlin.math.PI * seconds / 300.0 + 1.0).toFloat() else 0.dp
        val scale = p.clockSize
        val body: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
                if (showClock) {
                    val h = if (p.hour24) clock.hour else (clock.hour % 12).let { if (it == 0) 12 else it }
                    Text(
                        "${if (p.hour24) h.toString().padStart(2, '0') else h.toString()}:${clock.minute.toString().padStart(2, '0')}",
                        color = tone, fontSize = (92 * scale).sp, fontWeight = FontWeight.Light, maxLines = 1,
                    )
                    Text(clock.dateLabel + if (!p.hour24) (if (clock.hour < 12) "  AM" else "  PM") else "", color = tone.copy(alpha = 0.6f), style = MaterialTheme.typography.titleMedium)
                }
                if (p.shows(StandbyWidget.Song) && song != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (p.shows(StandbyWidget.Cover)) CoverImage(song.coverUri, song.id, Modifier.size(56.dp), size = 56.dp, radius = 10.dp, colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }))
                    Column(horizontalAlignment = if (p.shows(StandbyWidget.Cover)) Alignment.Start else Alignment.CenterHorizontally) {
                        Text(song.title, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                        Text(song.artist, color = tone.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (p.shows(StandbyWidget.Progress) && song != null) StandbyProgress(state, backend, tone)
                if (p.shows(StandbyWidget.Controls)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(backend::previous) { AppIcon(Icons.Rounded.FastRewind, "Previous", Modifier.size(32.dp), tint = tone) }
                    IconButton(backend::togglePlay, Modifier.size(64.dp)) {
                        AppIcon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(44.dp), tint = tone)
                    }
                    IconButton(backend::next) { AppIcon(Icons.Rounded.FastForward, "Next", Modifier.size(32.dp), tint = tone) }
                }
                if (p.shows(StandbyWidget.Next)) state.queue.getOrNull(state.queueIndex + 1)?.let {
                    Text("Next: ${it.title} - ${it.artist}", color = tone.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                if (p.shows(StandbyWidget.Lyrics) && song != null) StandbyLyric(song.id, backend, tone)
                if (p.shows(StandbyWidget.LyricsScroll) && song != null) {
                    val lines = rememberLrc(song.id, backend)
                    if (lines.isNotEmpty()) StandbyLyricScroll(lines, backend, tone, 3, false, Modifier.fillMaxWidth())
                }
                if (p.shows(StandbyWidget.Weather) && web != null) {
                    val place by web.weatherPlace.collectAsState()
                    val kind by web.weather.collectAsState()
                    place?.let { Text(it.name + (kind?.let { k -> ", " + weatherWord(k) } ?: ""), color = tone.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) }
                }
                if (p.shows(StandbyWidget.Battery)) rememberBatteryPercent()?.let {
                    val charging = rememberCharging()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (charging) AppIcon(Icons.Rounded.Bolt, "Charging", Modifier.size(14.dp), tint = tone.copy(alpha = 0.7f))
                        Text("Battery $it%", color = tone.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (p.shows(StandbyWidget.Alerts)) StandbyAlertRow(tone)
            }
        }
        val visual: @Composable (Modifier) -> Unit = { m ->
            val lyricsLoading = remember(song?.id) { mutableStateOf(true) }.also { l -> LaunchedEffect(song?.id) { kotlinx.coroutines.delay(4_000); l.value = false } }.value
            val sideLines = if (p.style == StandbyStyle.Lyrics && song != null) rememberLrc(song.id, backend, state.durationMs) else emptyList()
            if (p.style == StandbyStyle.Lyrics && song != null && sideLines.isEmpty()) Box(m, contentAlignment = Alignment.Center) {
                Text(
                    if (lyricsLoading) " " else "No lyrics for this song", color = tone.copy(alpha = 0.5f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
            } else if (sideLines.isNotEmpty()) Box(m, contentAlignment = Alignment.Center) {
                StandbyLyricScroll(sideLines, backend, tone, if (wide) 7 else 5, true, Modifier.fillMaxWidth())
            } else StandbyVisual(p.style, tone, state.playing, state.volume, song?.id.orEmpty(), m)
        }
        Box(Modifier.fillMaxSize().offset(shiftX, shiftY)) {
            if (wide) Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                body(Modifier.weight(1f).fillMaxHeight())
                visual(Modifier.weight(1f).fillMaxHeight().padding(vertical = 24.dp))
            } else Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                body(Modifier.weight(1f).fillMaxWidth())
                visual(Modifier.fillMaxWidth().height(boxHeight * 0.28f))
            }
        }
        // Both shown briefly on entry, then faded to invisible; they still work where they were.
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { delay(3000); hint = false }
        val hintAlpha by androidx.compose.animation.core.animateFloatAsState(if (armed) 0.7f else if (hint) 0.35f else 0f, androidx.compose.animation.core.tween(1500))
        androidx.compose.foundation.layout.Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            IconButton({ p.putLandscape(!p.landscape) }) {
                AppIcon(Icons.Rounded.ScreenRotation, if (p.landscape) "Landscape standby on" else "Landscape standby off",
                    tint = tone.copy(alpha = hintAlpha))
            }
            IconButton({ sheet = true }) {
                AppIcon(Icons.Rounded.Settings, "Customise standby", tint = tone.copy(alpha = hintAlpha))
            }
        }
        if (sheet) StandbyCustomiseSheet(p) { sheet = false }
    }
}

@Composable
private fun standbyTone(p: StandbyPrefs, state: PlaybackUiState): Color {
    val uri = state.current?.coverUri
    val primary = MaterialTheme.colorScheme.primary
    // "Accent" is the colour chosen in settings; if the app accent follows the cover or the wallpaper there is no fixed colour, so it is the theme colour.
    val accentSeed = io.max.paperplay.ui.app.LocalUiPrefs.current?.seed
    val raw = CoverSeeds.palette(uri).firstOrNull()?.takeIf { it != 0 } ?: CoverSeeds.seed(uri)
    val base = when (p.color) {
        "custom" -> Color(p.customColor)
        "accent" -> if (accentSeed != null) remember(accentSeed) { schemeFromSeed(accentSeed, dark = true).primary } else primary
        // The cover's own main colour, lifted if it is too dark to read on black.
        else -> if (raw != null) Color(raw).let { c -> if (c.luminance() < 0.4f) lerp(c, Color.White, 0.45f) else c } else primary
    }
    // Pulled slightly toward white so it reads at low brightness.
    return lerp(base, Color.White, 0.2f).copy(alpha = 0.9f)
}

private fun weatherWord(k: WeatherKind) = when (k) {
    WeatherKind.CLEAR -> "clear"
    WeatherKind.CLOUDY -> "cloudy"
    WeatherKind.RAIN -> "rain"
    WeatherKind.STORM -> "storm"
    WeatherKind.SNOW -> "snow"
}

/** A bolt while charging and one icon per app with a notification; a call pulses. Nothing is drawn when there is nothing to show. */
@Composable
private fun StandbyAlertRow(tone: Color) {
    val charging = rememberCharging()
    val alerts = rememberStandbyAlerts()
    if (!charging && alerts.isEmpty()) return
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition().animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
    )
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (charging) AppIcon(Icons.Rounded.Bolt, "Charging", Modifier.size(22.dp), tint = tone)
        alerts.take(8).forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                AlertIcon(a.packageName, Modifier.size(if (a.call) 30.dp else 22.dp), tone.copy(alpha = if (a.call) pulse else 0.8f))
                if (a.count > 1) Text("${a.count}", color = tone.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A thin bar; the position is read in its own scope so a tick never recomposes the screen. */
@Composable
private fun StandbyProgress(state: PlaybackUiState, backend: PlayerControls, tone: Color) {
    val pos by backend.position.collectAsState()
    val fraction = pos.progress(state.durationMs)
    Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(tone.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tone.copy(alpha = 0.7f)))
    }
}

/** The small one line lyric: just the current line. */
@Composable
private fun StandbyLyric(id: String, backend: PlayerControls, tone: Color) {
    val lines = rememberLrc(id, backend)
    if (lines.isEmpty()) return
    val pos by backend.position.collectAsState()
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= pos.positionMs } } }
    val line = lines.getOrNull(current)?.text.orEmpty()
    Text(line.ifBlank { " " }, color = tone.copy(alpha = 0.75f), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun rememberLrc(id: String, backend: PlayerControls, durationMs: Long = 0): List<io.max.paperplay.core.metadata.LrcLine> {
    var text by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        text = runCatching { backend.lyricsFor(id) }.getOrNull()
        if (text == null) { kotlinx.coroutines.delay(3_000); text = runCatching { backend.lyricsFor(id) }.getOrNull() }
    }
    return remember(text, durationMs / 1000) { lrcOrPlain(text, durationMs) }
}

/** Timed lyrics as they are; plain lyrics spread evenly over the song so they still scroll; a one line "pure music" notice is no lyrics. */
internal fun lrcOrPlain(text: String?, durationMs: Long): List<io.max.paperplay.core.metadata.LrcLine> {
    if (text.isNullOrBlank()) return emptyList()
    val timed = io.max.paperplay.core.metadata.parseLrc(text)
    val lines = if (timed.isNotEmpty()) timed else {
        val plain = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val step = if (durationMs > 0 && plain.isNotEmpty()) durationMs / plain.size else 4_000L
        plain.mapIndexed { i, t -> io.max.paperplay.core.metadata.LrcLine(i * step, t) }
    }
    val instrumental = lines.size == 1 && lines[0].text.let { it.contains("纯音乐") || it.contains("instrumental", ignoreCase = true) }
    return if (instrumental) emptyList() else lines
}

/** [rows] lines with the current one in the middle: the list scrolls smoothly to each new line and the lines fade out with distance. */
@Composable
private fun StandbyLyricScroll(lines: List<io.max.paperplay.core.metadata.LrcLine>, backend: PlayerControls, tone: Color, rows: Int, big: Boolean, modifier: Modifier = Modifier) {
    val pos by backend.position.collectAsState()
    val current by remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= pos.positionMs } } }
    val itemH = if (big) 44.dp else 30.dp
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current) { list.animateScrollToItem(current.coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier.height(itemH * rows)
            // The top and bottom lines fade out to nothing at the edges.
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
        state = list, userScrollEnabled = false,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = itemH * (rows / 2)),
    ) {
        itemsIndexed(lines) { i, l ->
            val d = kotlin.math.abs(i - current)
            val a by androidx.compose.animation.core.animateFloatAsState(when (d) { 0 -> 0.95f; 1 -> 0.45f; 2 -> 0.22f; else -> 0.1f }, androidx.compose.animation.core.tween(450))
            Box(Modifier.fillMaxWidth().height(itemH), contentAlignment = Alignment.Center) {
                Text(
                    l.text.ifBlank { " " }, color = tone.copy(alpha = a), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = if (big) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = if (d == 0) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Preview(name = "Standby", widthDp = 390, heightDp = 844)
@Composable
private fun StandbyPreview() = io.max.paperplay.ui.PreviewFrame(dark = true) {
    StandbyContent(io.max.paperplay.ui.previewPlayback(), io.max.paperplay.ui.FakePlayerControls(), StandbyPrefs({ null }, { _, _ -> }), onClose = {})
}
