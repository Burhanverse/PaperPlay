package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.pow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.EQ_BANDS_HZ
import kotlinx.coroutines.launch

// Ten octave bands, 32 Hz to 16 kHz, in dB. The shapes are deliberately far apart so a preset is heard and seen at once.
private val eqPresets = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    // Bass presets lift the low end but keep 125 to 500 Hz flat or slightly down: that range masks voices when lifted.
    "Bass boost" to listOf(8f, 7f, 3f, 0f, -0.5f, 0f, 0f, 0f, 0f, 0f),
    "Deep bass" to listOf(12f, 9f, 2f, -1f, -1f, 0f, 0f, 0f, 0f, 0f),
    // Rumbling bass with clear mids and highs: low end up, low-mid boom down, a little presence for voices and attack.
    "Clean bass" to listOf(7f, 6f, 1f, -1.5f, -1f, 0f, 1f, 1.5f, 1f, 0f),
    // Voices forward without thinning the music: low-mid boom down, presence up, no treble boost (no extra sibilance).
    "Vocal clarity" to listOf(0f, 0f, -1f, -2f, -0.5f, 0f, 1.5f, 2f, 0.5f, 0f),
    // Earphone presets. Chu 2: the AutoEq correction (crinacle, 711 coupler) sampled at our ten centres and rounded.
    // ZSN Pro 2: a gentle treble cut for the upper-mid resonance reviewers report, not a measured correction.
    "Moondrop Chu 2" to listOf(0f, 0f, -2f, -1f, 1f, 2f, 0f, 0f, 3f, -2f),
    "KZ ZSN Pro 2" to listOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, -2f, -2f, 0f),
    "Bass reducer" to listOf(-8f, -7f, -5f, -3f, -1f, 0f, 0f, 0f, 0f, 0f),
    "Treble boost" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 5f, 7f, 8f),
    "Vocal" to listOf(-3f, -3f, -1f, 2f, 4f, 5f, 4f, 2f, 0f, -2f),
    "Rock" to listOf(6f, 5f, 3f, 0f, -2f, -1f, 2f, 4f, 5f, 6f),
    "Pop" to listOf(-2f, -1f, 1f, 3f, 4f, 3f, 1f, -1f, -2f, -2f),
    "Jazz" to listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 2f, 3f, 4f),
    "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
    "Electronic" to listOf(6f, 5f, 2f, 0f, -2f, 2f, 1f, 2f, 5f, 6f),
    "Hip-hop" to listOf(7f, 6f, 3f, 4f, -1f, -1f, 2f, 0f, 3f, 4f),
    "Loudness" to listOf(7f, 5f, 1f, 0f, -1f, 0f, -1f, 0f, 5f, 6f),
    "Acoustic" to listOf(4f, 4f, 3f, 1f, 2f, 2f, 3f, 3f, 3f, 1f),
)

private fun db(v: Float): String {
    val t = kotlin.math.round(v * 10) / 10f
    val s = if (t % 1f == 0f) t.toInt().toString() else t.toString()
    return if (t > 0) "+$s" else s
}

private fun hz(f: Int) = if (f >= 1000) "${f / 1000}k" else "$f"

/** Phone engine extras below the equalizer: stereo width and the listening check (exact level, compare with original). */
@Composable
internal fun EngineExtras(backend: PlayerControls) {
    if (!io.max.paperplay.ui.isPhone) return
    val exact by backend.eqExact.collectAsState()
    val dry by backend.eqDry.collectAsState()
    val width by backend.eqWidth.collectAsState()
    val on by backend.eqOn.collectAsState()
    val earphone by backend.eqEarphone.collectAsState()
    SectionLabel("Earphone correction")
    val names = listOf("None") + io.max.paperplay.player.CorrectionProfile.EARPHONES.keys
    ChoiceRow(names, earphone.ifEmpty { "None" }, { it }, { backend.setEqEarphone(if (it == "None") "" else it) })
    Text(
        "Evens out the earphone's own measured sound (AutoEq). Wired earphones only; choose None for earbuds.",
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SectionLabel("Stereo width")
    Text(
        "${kotlin.math.round(width * 100).toInt()}%" + when {
            width > 1.005f -> ", instruments spread further apart; bass stays centred"
            width < 0.995f -> ", narrower, softer on hard-panned recordings"
            else -> ", as recorded"
        },
        Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
    androidx.compose.material3.Slider(
        value = width, onValueChange = { backend.setEqWidth(kotlin.math.round(it * 20) / 20f) }, valueRange = 0.5f..1.6f, steps = 21,
        enabled = on, modifier = Modifier.padding(horizontal = 20.dp),
    )
    SectionLabel("Listening check")
    SwitchRow(
        "Exact level", exact, backend::setEqExact, null,
        if (exact) "Plays the curve as drawn; quieter by the curve's peak, so raise the volume"
        else "Keeps the music loud; big bass boosts are heard a little smaller than drawn",
    )
    SwitchRow(
        "Compare with original", dry, backend::setEqDry, null,
        "Plays the untouched sound at the same loudness, so only the tuning differs",
    )
}

/** Settings > Listening > Equalizer. Ten bands drawn as a curve you can drag, plus presets. */
@Composable
internal fun EqualizerPage(backend: CoreBackend) {
    val on by backend.eqOn.collectAsState()
    val gains by backend.eqGains.collectAsState()
    val bass by backend.eqBass.collectAsState()
    BitPerfectRow(backend)
    EqualizerContent(on, gains, { backend.setEqualizer(on = it) }, { backend.setEqualizer(on = true, gains = it) }, bass, if (backend.hasBass) { v: Float -> backend.setEqBass(v) } else null)
    EngineExtras(backend)
}

@Composable
internal fun EqualizerContent(on: Boolean, gains: List<Float>, onToggle: (Boolean) -> Unit, onGains: (List<Float>) -> Unit, bass: Float = 0f, onBass: ((Float) -> Unit)? = null) {
    SwitchRow("Equalizer", on, onToggle, io.max.paperplay.ui.components.EqualizerBoardIcon, "Applies to this device only")
    val preset = eqPresets.entries.firstOrNull { e -> e.value.zip(gains).all { (a, b) -> kotlin.math.abs(a - b) < 0.05f } }?.key
    SectionLabel("Response")
    var drag by remember(gains) { mutableStateOf(gains) }
    EqualizerGraph(drag, on, Modifier.padding(horizontal = 20.dp), { drag = it }, { onGains(drag) })
    Text(
        (preset ?: "Custom") + if (on) "" else " (off)",
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
    )
    if (onBass != null) {
        SectionLabel("Bass")
        Text(
            if (bass < 0.05f) "Off" else "+${kotlin.math.round(bass * 10) / 10f} dB, deep low end below 100 Hz",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        androidx.compose.material3.Slider(
            value = bass, onValueChange = { onBass(kotlin.math.round(it * 2) / 2f) }, valueRange = 0f..12f, steps = 23,
            enabled = on, modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
    SectionLabel("Presets")
    ChoiceRow(eqPresets.keys.toList(), preset ?: "", { it }, { onGains(eqPresets.getValue(it)) })
    Text(
        "Drag the dots to shape the sound. A device with fewer bands follows the same curve.",
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The frequency response as a curve on a log axis, +/-12 dB. It glides to a new shape when a preset is picked and follows the
 * finger while a dot is dragged, so every change shows at once.
 */
@Composable
private fun EqualizerGraph(gains: List<Float>, on: Boolean, modifier: Modifier, onDrag: (List<Float>) -> Unit, onDone: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shown = gains.map { androidx.compose.animation.core.animateFloatAsState(it, androidx.compose.animation.core.tween(380), label = "eq").value }
    val line = if (on) scheme.primary else scheme.outline
    Column(modifier) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(190.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHigh)
                .pointerInput(gains.size) {
                    fun place(x: Float, y: Float) {
                        val i = ((x / size.width) * EQ_BANDS_HZ.size).toInt().coerceIn(0, EQ_BANDS_HZ.size - 1)
                        val g = ((1f - y / size.height) * 24f - 12f).coerceIn(-12f, 12f)
                        onDrag(gains.toMutableList().also { it[i] = kotlin.math.round(g * 2) / 2f })
                    }
                    detectDragGestures(onDragStart = { place(it.x, it.y) }, onDragEnd = onDone, onDragCancel = onDone) { c, _ -> c.consume(); place(c.position.x, c.position.y) }
                }
                .pointerInput(gains.size) {
                    detectTapGestures { p ->
                        val i = ((p.x / size.width) * EQ_BANDS_HZ.size).toInt().coerceIn(0, EQ_BANDS_HZ.size - 1)
                        onDrag(gains.toMutableList().also { it[i] = kotlin.math.round(((1f - p.y / size.height) * 24f - 12f).coerceIn(-12f, 12f) * 2) / 2f }); onDone()
                    }
                },
        ) {
            val w = size.width; val h = size.height
            fun yOf(g: Float) = h * (1f - (g + 12f) / 24f)
            // Grid: 0 dB brighter, +-6 and +-12 faint.
            for (g in listOf(-12f, -6f, 0f, 6f, 12f)) drawLine(scheme.onSurface.copy(alpha = if (g == 0f) 0.30f else 0.10f), Offset(0f, yOf(g)), Offset(w, yOf(g)), strokeWidth = if (g == 0f) 2f else 1f)
            for (i in 1 until EQ_BANDS_HZ.size) drawLine(scheme.onSurface.copy(alpha = 0.06f), Offset(w * i / EQ_BANDS_HZ.size, 0f), Offset(w * i / EQ_BANDS_HZ.size, h), strokeWidth = 1f)
            // The curve: gain read at 200 points across ten octaves; band i sits at (i + 0.5) / 10 of the width.
            val steps = 200
            val path = androidx.compose.ui.graphics.Path()
            val fill = androidx.compose.ui.graphics.Path()
            for (k in 0..steps) {
                val x = w * k / steps
                val hzAt = 32.0 * 2.0.pow((k.toDouble() / steps) * EQ_BANDS_HZ.size - 0.5)
                val y = yOf(io.max.paperplay.player.eqGainAt(shown, hzAt))
                if (k == 0) { path.moveTo(x, y); fill.moveTo(x, yOf(0f)); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, yOf(0f)); fill.close()
            drawPath(fill, line.copy(alpha = 0.18f))
            drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            shown.forEachIndexed { i, g ->
                val c = Offset(w * (i + 0.5f) / EQ_BANDS_HZ.size, yOf(g))
                drawCircle(scheme.surfaceContainerHigh, 9f, c); drawCircle(line, 6.5f, c)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            EQ_BANDS_HZ.forEachIndexed { i, f ->
                Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(hz(f), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    Text(db(gains[i]), style = MaterialTheme.typography.labelSmall, color = if (gains[i] != 0f) line else scheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** Settings > Listening > ListenBrainz. */
@Composable
internal fun ListenBrainzPage(backend: CoreBackend) {
    val user by backend.lbUser.collectAsState()
    ListenBrainzContent(user, backend::disconnectListenBrainz, backend::connectListenBrainz)
}

@Composable
internal fun ListenBrainzContent(user: String?, onDisconnect: () -> Unit, onConnect: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user != null) {
            Text("Sending what you listen to as $user.", style = MaterialTheme.typography.titleMedium)
            Text("A song counts after half of it, or four minutes, whichever comes first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onDisconnect) { Text("Disconnect") }
        } else {
            Text(
                "Paste your user token from listenbrainz.org/settings. Titles and artists of songs you finish are sent to ListenBrainz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("User token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Button({ busy = true; problem = null; scope.launch { problem = onConnect(token); busy = false; if (problem == null) token = "" } }, enabled = token.isNotBlank() && !busy) {
                Text(if (busy) "Checking" else "Connect")
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EqualizerPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(390.dp, 800.dp)) { EqualizerContent(true, listOf(8f, 7f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f), {}, {}) }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun EqualizerDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(1024.dp, 800.dp)) { EqualizerContent(true, listOf(8f, 7f, 5f, 3f, 1f, 0f, 0f, 0f, 0f, 0f), {}, {}) }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ListenBrainzPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(390.dp, 400.dp)) { ListenBrainzContent(null, {}, { null }) }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun ListenBrainzDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(1024.dp, 400.dp)) { ListenBrainzContent("maxcodl", {}, { null }) }
    }
}

/** Paste an AutoEq GraphicEQ line for the earphones in use; the mpv engine applies it as an exact response. */
@Composable
internal fun CorrectionProfileDialog(current: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val valid = text.isBlank() || io.max.paperplay.player.CorrectionProfile.parse(text) != null
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Earphone correction profile") },
        text = {
            Column {
                Text(
                    "Paste the GraphicEQ line from the AutoEq results for your earphones (a file ending in GraphicEQ.txt), or pick one below.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 6, isError = !valid, label = { Text("GraphicEQ: 20 -4.0; 21 -4.2; ...") })
                if (!valid) Text("This does not look like a GraphicEQ line.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.TextButton({ text = io.max.paperplay.player.CorrectionProfile.MOONDROP_CHU_2 }) { Text("Use Moondrop Chu 2") }
                androidx.compose.material3.TextButton({ text = "" }) { Text("Clear") }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton({ onSave(text.trim()) }, enabled = valid) { Text("Save") } },
        dismissButton = { androidx.compose.material3.TextButton(onClose) { Text("Cancel") } },
    )
}
