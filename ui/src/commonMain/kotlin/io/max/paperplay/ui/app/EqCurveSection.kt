package io.max.paperplay.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.NativeEqMath
import io.max.paperplay.player.OutputInfo
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.player.PeqChannel
import io.max.paperplay.player.PeqFilterType
import io.max.paperplay.player.logSpacedFreqs
import io.max.paperplay.ui.components.AppIcon
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round

private const val GRAPH_MIN_HZ = 20f
private const val GRAPH_MAX_HZ = 20000f
private val GRAPH_GRID_FREQS = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

@Composable
internal fun EqCurveSection(
    backend: PlayerControls,
    profile: ParametricEqProfile,
    eqOn: Boolean,
    bitPerfectStatus: BitPerfectStatus,
    bitPerfectBypass: Boolean,
    sampleRate: Int,
    outputInfo: OutputInfo?,
    selectedBand: ParametricBand?,
    scrollState: ScrollState,
    bottomPadding: Dp,
    onSelectBand: (Int) -> Unit,
    onDragBand: (Int, Float, Float) -> Unit,
    onChangeBand: (ParametricBand) -> Unit,
    onDeleteBand: (Int) -> Unit,
    onOpenNumeric: (ParametricBand) -> Unit,
    onChangeProfile: (ParametricEqProfile) -> Unit,
    onOpenPresets: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val graphFreqs = remember { logSpacedFreqs(200, GRAPH_MIN_HZ, GRAPH_MAX_HZ) }
    val response = remember(profile, sampleRate) {
        NativeEqMath.evalResponse(sampleRate, profile, graphFreqs, PeqChannel.STEREO)
    }
    val badge = resolveEqSignalBadge(
        bitPerfectStatus = bitPerfectStatus,
        eqOn = eqOn,
        bitPerfectBypass = bitPerfectBypass,
        sampleRate = sampleRate,
        bitDepth = outputInfo?.bitDepth ?: 0,
    )
    val badgeColor = when (badge.tone) {
        EqSignalTone.Primary -> scheme.primary
        EqSignalTone.Secondary -> scheme.secondary
        EqSignalTone.Muted -> scheme.onSurfaceVariant
    }
    val badgeIcon = when (badge.iconKind) {
        "usb" -> Icons.Rounded.Usb
        "verified" -> Icons.Rounded.Verified
        "active" -> Icons.Rounded.CheckCircle
        else -> Icons.Rounded.Info
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(top = 4.dp, bottom = bottomPadding + 16.dp),
    ) {
        SettingsSection("Status and master") {
            SettingsRow(
                title = badge.title,
                icon = badgeIcon,
                subtitle = badge.detail,
                titleColor = badgeColor,
                iconTint = badgeColor,
            )
            RowDivider()
            SettingsRow(
                title = profile.name,
                icon = Icons.Rounded.LibraryMusic,
                subtitle = "${profile.bands.count { it.enabled }} of ${profile.bands.size} bands active · ${profile.source}",
                onClick = onOpenPresets,
            ) {
                AppIcon(Icons.Rounded.ChevronRight, "Browse presets")
            }
            if (backend.hasBass) {
                RowDivider()
                BitPerfectRow(backend)
            }
        }

        SettingsSection("Response") {
            ParametricResponseGraph(
                profile = profile,
                responseDb = response.db,
                effectivePreampDb = response.effectivePreampDb,
                freqsHz = graphFreqs,
                enabled = eqOn && !bitPerfectBypass,
                selectedBandId = selectedBand?.id ?: -1,
                onSelectBand = onSelectBand,
                onDragBand = onDragBand,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }

        if (selectedBand != null) {
            val idx = profile.bands.indexOfFirst { it.id == selectedBand.id }.coerceAtLeast(0)
            SettingsSection("Selected band") {
                BandPrecisionControls(
                    band = selectedBand,
                    title = "Band ${idx + 1} · ${fmtHz(selectedBand.freqHz)}",
                    canDelete = profile.bands.size > 1,
                    onChange = onChangeBand,
                    onDelete = { onDeleteBand(selectedBand.id) },
                    onEditNumbers = { onOpenNumeric(selectedBand) },
                )
            }
        }

        SettingsSection("Preamp and headroom") {
            val autoPart = response.effectivePreampDb - profile.preampDb
            val detail = if (profile.autoPreamp && abs(autoPart) >= 0.05f) {
                "${fmtDb(profile.preampDb)} dB (Auto ${fmtDb(autoPart)}, Total ${fmtDb(response.effectivePreampDb)} dB)"
            } else {
                "${fmtDb(profile.preampDb)} dB"
            }
            SliderRow(
                title = "Preamp: $detail",
                value = profile.preampDb,
                range = -24f..24f,
                onChange = { v -> onChangeProfile(profile.copy(preampDb = round(v * 10f) / 10f)) },
                hint = "Lowers or raises overall level before EQ filters to prevent digital clipping.",
            )
            RowDivider()
            SwitchRow(
                title = "Auto-preamp",
                checked = profile.autoPreamp,
                onChange = { onChangeProfile(profile.copy(autoPreamp = it)) },
                icon = Icons.Rounded.Tune,
                subtitle = "Automatically pulls peak gain down based on the active curve so boosts stay clean.",
            )
            RowDivider()
            SwitchRow(
                title = "Limiter (-0.5 dB)",
                checked = profile.limiterEnabled,
                onChange = { onChangeProfile(profile.copy(limiterEnabled = it)) },
                icon = Icons.Rounded.Speed,
                subtitle = "Soft-clip look-ahead limiter at -0.5 dBFS to protect against unexpected peaks.",
            )
        }
    }
}

/**
 * Interactive 60 FPS log-frequency response Canvas (`20 Hz - 20 kHz`) driven directly by Rust DSP output.
 * Dragging a band node updates its frequency (log X) and gain (linear Y) in real time.
 */
@Composable
internal fun ParametricResponseGraph(
    profile: ParametricEqProfile,
    responseDb: FloatArray,
    effectivePreampDb: Float,
    freqsHz: FloatArray,
    enabled: Boolean,
    selectedBandId: Int,
    onSelectBand: (Int) -> Unit,
    onDragBand: (Int, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val maxBandGain = remember(profile.bands, responseDb) {
        val bMax = profile.bands.maxOfOrNull { abs(it.gainDb) } ?: 0f
        val rMax = responseDb.maxOfOrNull { abs(it) } ?: 0f
        maxOf(bMax, rMax)
    }
    val dbRange = if (maxBandGain > 14f) 24f else 15f
    val lnMin = remember { ln(GRAPH_MIN_HZ.toDouble()) }
    val lnSpan = remember { ln(GRAPH_MAX_HZ.toDouble()) - lnMin }

    fun freqToNormX(hz: Float): Float =
        ((ln(hz.coerceIn(GRAPH_MIN_HZ, GRAPH_MAX_HZ).toDouble()) - lnMin) / lnSpan).toFloat().coerceIn(0f, 1f)

    fun normXToFreq(nx: Float): Float =
        exp(lnMin + nx.coerceIn(0f, 1f).toDouble() * lnSpan).toFloat()

    fun dbToNormY(db: Float): Float = (0.5f - db / (2f * dbRange)).coerceIn(0f, 1f)
    fun normYToDb(ny: Float): Float = ((0.5f - ny.coerceIn(0f, 1f)) * (2f * dbRange)).coerceIn(-24f, 24f)

    val curveColor = if (enabled) scheme.primary else scheme.outline
    val leftColor = if (enabled) scheme.tertiary else scheme.outline
    val rightColor = if (enabled) scheme.secondary else scheme.outline
    val preampColor = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val graphShape = appShape(16.dp, CornerArea.Cards)

    var draggingId by remember(profile.id) { mutableIntStateOf(-1) }
    val currentProfileState by rememberUpdatedState(profile)
    val currentOnSelectBand by rememberUpdatedState(onSelectBand)
    val currentOnDragBand by rememberUpdatedState(onDragBand)

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(graphShape)
                .background(scheme.surfaceContainerHigh)
                .semantics {
                    contentDescription = "Parametric equalizer frequency response graph, ${profile.bands.size} bands"
                }
                .pointerInput(profile.id, dbRange) {
                    detectTapGestures { pos ->
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        val bands = currentProfileState.bands
                        val hit = bands.minByOrNull { b ->
                            val bx = freqToNormX(b.freqHz) * w
                            val by = dbToNormY(if (b.type.usesGain) b.gainDb else 0f) * h
                            hypot(pos.x - bx, pos.y - by)
                        }
                        if (hit != null) {
                            val bx = freqToNormX(hit.freqHz) * w
                            val by = dbToNormY(if (hit.type.usesGain) hit.gainDb else 0f) * h
                            if (hypot(pos.x - bx, pos.y - by) <= 56f) {
                                currentOnSelectBand(hit.id)
                            }
                        }
                    }
                }
                .pointerInput(profile.id, dbRange) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val bands = currentProfileState.bands
                            val nearest = bands.minByOrNull { b ->
                                val bx = freqToNormX(b.freqHz) * w
                                val by = dbToNormY(if (b.type.usesGain) b.gainDb else 0f) * h
                                hypot(start.x - bx, start.y - by)
                            }
                            if (nearest != null) {
                                val bx = freqToNormX(nearest.freqHz) * w
                                val by = dbToNormY(if (nearest.type.usesGain) nearest.gainDb else 0f) * h
                                if (hypot(start.x - bx, start.y - by) <= 68f) {
                                    draggingId = nearest.id
                                    currentOnSelectBand(nearest.id)
                                }
                            }
                        },
                        onDragEnd = { draggingId = -1 },
                        onDragCancel = { draggingId = -1 },
                    ) { change, _ ->
                        val id = draggingId
                        if (id >= 0) {
                            change.consume()
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            val f = round(normXToFreq(change.position.x / w) * 10f) / 10f
                            val g = round(normYToDb(change.position.y / h) * 10f) / 10f
                            currentOnDragBand(id, f, g)
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val yZero = dbToNormY(0f) * h

            val dbLines = if (dbRange > 16f) listOf(-24f, -12f, 0f, 12f, 24f) else listOf(-15f, -10f, -5f, 0f, 5f, 10f, 15f)
            for (g in dbLines) {
                val y = dbToNormY(g) * h
                drawLine(
                    color = scheme.onSurface.copy(alpha = if (g == 0f) 0.30f else 0.10f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (g == 0f) 2f else 1f,
                )
            }
            for (hz in GRAPH_GRID_FREQS) {
                val x = freqToNormX(hz) * w
                drawLine(
                    color = scheme.onSurface.copy(alpha = 0.08f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
            }

            if (abs(effectivePreampDb) >= 0.05f) {
                val py = dbToNormY(effectivePreampDb) * h
                drawLine(
                    color = preampColor,
                    start = Offset(0f, py),
                    end = Offset(w, py),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
            }

            if (responseDb.size == freqsHz.size && responseDb.size >= 2) {
                val strokePath = Path()
                val fillPath = Path()
                for (i in freqsHz.indices) {
                    val x = freqToNormX(freqsHz[i]) * w
                    val y = dbToNormY(responseDb[i]) * h
                    if (i == 0) {
                        strokePath.moveTo(x, y)
                        fillPath.moveTo(x, yZero)
                        fillPath.lineTo(x, y)
                    } else {
                        strokePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(w, yZero)
                fillPath.close()
                drawPath(fillPath, curveColor.copy(alpha = 0.18f))
                drawPath(
                    path = strokePath,
                    color = curveColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            profile.bands.forEach { band ->
                val cx = freqToNormX(band.freqHz) * w
                val cy = dbToNormY(if (band.type.usesGain) band.gainDb else 0f) * h
                val isSel = band.id == selectedBandId
                val nodeColor = when {
                    !band.enabled -> scheme.outline.copy(alpha = 0.5f)
                    band.channel == PeqChannel.LEFT -> leftColor
                    band.channel == PeqChannel.RIGHT -> rightColor
                    else -> curveColor
                }
                if (isSel) {
                    drawCircle(nodeColor.copy(alpha = 0.25f), radius = 17f, center = Offset(cx, cy))
                }
                drawCircle(scheme.surfaceContainerHigh, radius = if (isSel) 10f else 8f, center = Offset(cx, cy))
                drawCircle(nodeColor, radius = if (isSel) 7.5f else 5.5f, center = Offset(cx, cy))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("20", "100", "500", "1k", "5k", "20k").forEach { lbl ->
                Text(lbl, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Text("±${dbRange.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
        }
    }
}

@Composable
internal fun BandPrecisionControls(
    band: ParametricBand,
    title: String?,
    canDelete: Boolean,
    onChange: (ParametricBand) -> Unit,
    onDelete: () -> Unit,
    onEditNumbers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lnMin = remember { ln(10.0) }
    val lnSpan = remember { ln(24000.0) - lnMin }
    val qMin = remember { ln(0.1) }
    val qSpan = remember { ln(20.0) - qMin }

    val freqPos = ((ln(band.freqHz.coerceIn(10f, 24000f).toDouble()) - lnMin) / lnSpan).toFloat().coerceIn(0f, 1f)
    val qPos = ((ln(band.q.coerceIn(0.1f, 20f).toDouble()) - qMin) / qSpan).toFloat().coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        if (title != null) {
            SettingsRow(
                title = title,
                icon = Icons.Rounded.GraphicEq,
                subtitle = "${band.type.label} · ${band.channel.label}",
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEditNumbers) {
                        AppIcon(Icons.Rounded.Edit, "Enter exact values")
                    }
                    Switch(
                        checked = band.enabled,
                        onCheckedChange = { onChange(band.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "Enable band" },
                    )
                    if (canDelete) {
                        IconButton(onClick = onDelete) {
                            AppIcon(Icons.Rounded.Delete, "Delete band")
                        }
                    }
                }
            }
            RowDivider()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onEditNumbers) {
                    AppIcon(Icons.Rounded.Edit, "Enter exact values")
                    Spacer(Modifier.width(6.dp))
                    Text("Exact values")
                }
            }
        }

        SectionLabel("Filter type")
        ChoiceRow(
            options = PeqFilterType.entries,
            selected = band.type,
            label = { it.shortLabel },
            onSelect = { onChange(band.copy(type = it)) },
        )

        SectionLabel("Channel")
        ChoiceRow(
            options = PeqChannel.entries,
            selected = band.channel,
            label = { it.label },
            onSelect = { onChange(band.copy(channel = it)) },
        )

        RowDivider()

        SliderRow(
            title = "Frequency: ${fmtHz(band.freqHz)}",
            value = freqPos,
            range = 0f..1f,
            onChange = { p ->
                val hz = exp(lnMin + p.toDouble() * lnSpan).toFloat()
                val snapped = if (hz < 100f) round(hz * 2f) / 2f else round(hz)
                onChange(band.copy(freqHz = snapped.coerceIn(10f, 24000f)))
            },
        )

        if (band.type.usesGain) {
            SliderRow(
                title = "Gain: ${fmtDb(band.gainDb)} dB",
                value = band.gainDb,
                range = -24f..24f,
                onChange = { g -> onChange(band.copy(gainDb = round(g * 10f) / 10f)) },
            )
        }

        SliderRow(
            title = "Q factor: ${fmtQ(band.q)}",
            value = qPos,
            range = 0f..1f,
            onChange = { p ->
                val q = exp(qMin + p.toDouble() * qSpan).toFloat()
                onChange(band.copy(q = (round(q * 100f) / 100f).coerceIn(0.1f, 20f)))
            },
        )
    }
}

internal fun fmtHz(hz: Float): String = when {
    hz >= 1000f -> {
        val k = round(hz / 10f) / 100f
        if (k % 1f == 0f) "${k.toInt()} kHz" else "$k kHz"
    }
    hz % 1f == 0f -> "${hz.toInt()} Hz"
    else -> "${round(hz * 10f) / 10f} Hz"
}

internal fun fmtDb(v: Float): String {
    val t = round(v * 10f) / 10f
    val s = if (t % 1f == 0f) t.toInt().toString() else t.toString()
    return if (t > 0f) "+$s" else s
}

internal fun fmtQ(q: Float): String {
    val t = round(q * 100f) / 100f
    return t.toString()
}

internal fun fmtRawNum(v: Float): String {
    val t = round(v * 100f) / 100f
    return if (t % 1f == 0f) t.toInt().toString() else t.toString()
}
