package io.max.paperplay.ui.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import io.max.paperplay.player.OutputInfo
import io.max.paperplay.ui.LocalGlassBackdrop
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.LocalCoverLoader
import io.max.paperplay.ui.components.TrueAudioIcon
import io.max.paperplay.ui.glassEffectsSupported
import io.max.paperplay.ui.glassLensSupported
import io.max.paperplay.ui.isPhone
import io.max.paperplay.ui.rememberAppForeground
import io.max.paperplay.ui.state.PlaybackUiState
import io.max.paperplay.ui.state.SongUi
import io.max.paperplay.ui.theme.LocalReduceMotion
import io.max.paperplay.ui.theme.LocalReducedTransparency
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.motionTween
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.sin

/** Warm gold accent for the HI-RES badge outline, waveform glyph, and label on dark glass. */
internal val ModernHiResGold = Color(0xFFD9A441)

/** Clamped frosted dark glass fill and rim alphas so white/near-white controls remain legible on any cover. */
private const val GLASS_DARK_TINT_ALPHA = 0.44f
private const val GLASS_FALLBACK_ALPHA = 0.72f
private const val BLOOM_FRAME_NANOS = 33_000_000L

private val LOSSLESS_CODECS = setOf("FLAC", "ALAC", "WAV", "AIFF", "DSD", "APE", "WV")
private val KNOWN_CODECS = LOSSLESS_CODECS + setOf("MP3", "AAC", "M4A", "OGG", "OPUS", "WMA", "AC3", "EAC3", "EC3", "AC4", "TRUEHD", "MLP")
private val QUALITY_DEPTH_RATE_REGEX = Regex("""(\d+)/([\d.]+)""")

internal data class QualityBadgeSpec(
    val isHiRes: Boolean,
    val codec: String?,
    val isBitPerfect: Boolean,
)

/**
 * Derives real-data quality badges for the Modern player from [song] and live [outputInfo].
 *
 * Hi-Res rule: `isHiRes` is true ONLY when the track is lossless (`song.lossless` or a lossless codec)
 * AND its real audio format exceeds CD quality (`bitDepth > 16` OR `sampleRateHz > 44100`).
 * Real depth/rate are read from [outputInfo] when available, falling back to `(\d+)/([\d.]+)` in
 * [SongUi.qualityLabel] (e.g. `"FLAC 24/96"`). Unknown depth and rate never produce `isHiRes = true`.
 * `codec` is taken from [outputInfo.codec] or the leading codec token of [song.qualityLabel] when known.
 */
internal fun resolveQualityBadges(song: SongUi, outputInfo: OutputInfo?): QualityBadgeSpec {
    val labelCodec = song.qualityLabel
        ?.trim()
        ?.substringBefore(' ')
        ?.uppercase()
        ?.takeIf { it in KNOWN_CODECS }
    val outCodec = outputInfo?.codec
        ?.trim()
        ?.substringBefore(' ')
        ?.uppercase()
        ?.takeIf { it.isNotEmpty() && it != "PCM" }
    val codec = outCodec ?: labelCodec

    val isLossless = song.lossless || (codec != null && codec in LOSSLESS_CODECS)
    val match = QUALITY_DEPTH_RATE_REGEX.find(song.qualityLabel.orEmpty())
    val parsedDepth = match?.groupValues?.get(1)?.toIntOrNull()
    val parsedRateHz = match?.groupValues?.get(2)?.toDoubleOrNull()?.let { kHz -> (kHz * 1000.0).toInt() }

    val bitDepth = outputInfo?.bitDepth?.takeIf { it > 0 } ?: parsedDepth
    val sampleRateHz = outputInfo?.sampleRateHz?.takeIf { it > 0 } ?: parsedRateHz
    val isHiRes = isLossless && (
        (bitDepth != null && bitDepth > 16) ||
            (sampleRateHz != null && sampleRateHz > 44_100)
        )

    return QualityBadgeSpec(
        isHiRes = isHiRes,
        codec = codec,
        isBitPerfect = outputInfo?.bitPerfect == true,
    )
}

/** Derives a quiet "Playing from" source label from the active queue. */
internal fun resolvePlayingFrom(state: PlaybackUiState): String {
    val current = state.current ?: return "All"
    val queue = state.queue
    if (queue.isEmpty()) return current.album.ifBlank { current.artist.ifBlank { "All" } }
    val albums = queue.mapNotNull { it.album.trim().takeIf(String::isNotEmpty) }.distinct()
    if (albums.size == 1 && queue.all { it.album.trim().equals(albums[0], ignoreCase = true) }) {
        return albums[0]
    }
    val artists = queue.mapNotNull { it.artist.trim().takeIf(String::isNotEmpty) }.distinct()
    if (artists.size == 1) return artists[0]
    return "All"
}

/** Modern player UI is available on Android builds only; desktop always uses [PlayerStyle.Default]. */
internal fun resolveEffectivePlayerStyle(prefs: UiPrefs?, isAndroid: Boolean = isPhone): PlayerStyle =
    if (!isAndroid) PlayerStyle.Default else (prefs?.playerStyle ?: PlayerStyle.Default)

/** Maps the user's backdrop blur setting (`0..40 dp`) to a cached cover decode size. */
internal fun modernBackdropDecodePx(blurDp: Float): Int = when {
    blurDp <= 2f -> 256
    blurDp <= 10f -> 128
    blurDp <= 20f -> 64
    blurDp <= 32f -> 36
    else -> 24
}

/**
 * Low-cost blurred, darkened, and slightly desaturated cover backdrop with slow drifting
 * cover-palette light blooms and a luminance-clamped dark scrim.
 *
 * Uses a single 36 px cached thumbnail upscaled via `ContentScale.Crop` instead of a per-frame
 * GPU blur pass, and animates blooms via a single 30 fps draw-phase float that pauses when
 * paused, backgrounded, or when Reduce motion / Reduced transparency is active.
 */
@Composable
internal fun ModernPlayerBackdrop(
    coverUri: String?,
    scheme: ColorScheme,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val loader = LocalCoverLoader.current
    val foreground = rememberAppForeground()
    val reduceMotion = LocalReduceMotion.current
    val reducedTransparency = LocalReducedTransparency.current
    val blurDp = LocalUiPrefs.current?.modernPlayerBlur ?: 24f
    val decodePx = modernBackdropDecodePx(blurDp)
    val palette = remember(coverUri, scheme.primary) {
        val raw = CoverSeeds.palette(coverUri)
        if (raw.isNotEmpty()) raw.map(::Color) else listOf(scheme.primary, scheme.tertiary)
    }
    val desatFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.76f) })
    }
    val time = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing, foreground, reduceMotion, reducedTransparency) {
        if (!playing || !foreground || reduceMotion || reducedTransparency) return@LaunchedEffect
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (last == 0L) last = now
                val dt = now - last
                if (dt >= BLOOM_FRAME_NANOS) {
                    time.floatValue = (time.floatValue + dt / 1_000_000_000f) % 100_000f
                    last = now
                }
            }
        }
    }

    val fallbackTop = lerp(scheme.primaryContainer, Color.Black, 0.35f)
    val fallbackMid = lerp(scheme.secondaryContainer, Color.Black, 0.65f)
    val fallbackBot = lerp(scheme.surfaceContainerLowest, Color.Black, 0.85f)

    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (!reducedTransparency) {
            Crossfade(
                targetState = coverUri,
                modifier = Modifier.fillMaxSize(),
                animationSpec = motionTween(Motion.EMPHASIZED),
                label = "modernBackdrop",
            ) { uri ->
                val painter = if (uri != null) loader?.painter(uri, decodePx) else null
                if (painter != null) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer { scaleX = 1.25f; scaleY = 1.25f }
                            .then(if (blurDp > 0.5f && glassEffectsSupported()) Modifier.blur((blurDp * 0.6f).dp) else Modifier),
                        contentScale = ContentScale.Crop,
                        colorFilter = desatFilter,
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().drawBehind {
                            drawRect(Brush.verticalGradient(0f to fallbackTop, 0.55f to fallbackMid, 1f to fallbackBot))
                        },
                    )
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    drawRect(Brush.verticalGradient(0f to fallbackTop, 0.55f to fallbackMid, 1f to fallbackBot))
                },
            )
        }

        // Slow drifting radial blooms echoing the cover's brightest palette hues.
        if (!reducedTransparency) {
            val c1 = palette[0]
            val c2 = palette.getOrElse(1) { scheme.tertiary }
            Canvas(Modifier.fillMaxSize()) {
                val t = time.floatValue
                val w = size.width
                val h = size.height
                val x1 = w * (0.28f + 0.14f * sin(t * 0.22f))
                val y1 = h * (0.26f + 0.08f * cos(t * 0.17f))
                val r1 = maxOf(w, h) * 0.55f
                drawCircle(
                    brush = Brush.radialGradient(listOf(c1.copy(alpha = 0.24f), Color.Transparent), Offset(x1, y1), r1),
                    radius = r1,
                    center = Offset(x1, y1),
                )
                val x2 = w * (0.74f + 0.12f * cos(t * 0.19f))
                val y2 = h * (0.62f + 0.09f * sin(t * 0.15f))
                val r2 = maxOf(w, h) * 0.50f
                drawCircle(
                    brush = Brush.radialGradient(listOf(c2.copy(alpha = 0.20f), Color.Transparent), Offset(x2, y2), r2),
                    radius = r2,
                    center = Offset(x2, y2),
                )
            }
        }

        // Luminance-clamped scrim so white controls and text stay legible on bright, dark, or missing covers.
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.54f),
                        0.42f to Color.Black.copy(alpha = 0.36f),
                        0.76f to Color.Black.copy(alpha = 0.68f),
                        1.0f to Color.Black.copy(alpha = 0.86f),
                    ),
                )
            },
        )
    }
}

/**
 * Frosted dark glass surface honoring [LookGroup.Player], [GlassStyle], [LocalGlassBackdrop],
 * [LocalReducedTransparency], and [appShape]. Falls back to a coherent dark translucent fill
 * when Look=Material or reduced transparency is active.
 */
@Composable
internal fun Modifier.modernGlassSurface(
    shape: Shape,
    active: Boolean = false,
    accentColor: Color = Color.Unspecified,
): Modifier {
    val prefs = LocalUiPrefs.current
    val scheme = MaterialTheme.colorScheme
    val backdrop = LocalGlassBackdrop.current
    val reduced = LocalReducedTransparency.current
    val useRealGlass = prefs?.look(LookGroup.Player) != Look.Material &&
        backdrop != null && !reduced && (isPhone || prefs?.desktopBlur == true)

    val resolvedTint = if (active && accentColor != Color.Unspecified) {
        lerp(Color.Black, accentColor, 0.28f)
    } else {
        lerp(scheme.surfaceContainerLow, Color.Black, 0.58f)
    }

    if (!useRealGlass) {
        return shadow(if (reduced) 2.dp else 8.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.28f))
            .clip(shape)
            .background(resolvedTint.copy(alpha = if (reduced) 0.86f else GLASS_FALLBACK_ALPHA))
    }

    val g = prefs?.glass ?: GlassStyle()
    val tintAlpha = (0.28f + g.tint.coerceIn(0f, 1f) * 0.45f).coerceIn(GLASS_DARK_TINT_ALPHA, 0.78f)
    return clip(shape).drawBackdrop(
        backdrop = backdrop!!,
        shape = { shape },
        effects = {
            if (g.saturation != 1f) colorControls(saturation = g.saturation)
            blur(g.blur.coerceAtLeast(6f).dp.toPx())
            if (glassLensSupported()) lens(g.lensHeight.dp.toPx(), g.lensAmount.dp.toPx(), g.depth, g.chromatic)
        },
        highlight = null,
        shadow = if (g.shadowRadius > 0f && g.shadowAlpha > 0f) ({ Shadow(radius = g.shadowRadius.dp, color = Color.Black.copy(alpha = g.shadowAlpha)) }) else null,
        innerShadow = if (g.innerAlpha > 0f) ({ InnerShadow(radius = g.innerRadius.dp, color = Color.White.copy(alpha = g.innerAlpha)) }) else null,
        onDrawSurface = { drawRect(resolvedTint.copy(alpha = tintAlpha)) },
    )
}

@Composable
internal fun GlassPillButton(
    onClick: () -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    shape: Shape = appShape(22.dp, CornerArea.Cards),
    active: Boolean = false,
    enabled: Boolean = true,
    stateDesc: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = motionTween(Motion.FAST),
        label = "glassPress",
    )
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .modernGlassSurface(shape = shape, active = active, accentColor = accent)
            .semantics {
                contentDescription = description
                if (stateDesc != null) stateDescription = stateDesc
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun GlassSplitPill(
    leftDescription: String,
    rightDescription: String,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier,
    leftStateDesc: String? = null,
    rightStateDesc: String? = null,
    leftContent: @Composable BoxScope.() -> Unit,
    rightContent: @Composable BoxScope.() -> Unit,
) {
    val outer = appShape(24.dp, CornerArea.Cards)
    val inner = appShape(6.dp, CornerArea.Chips)
    val leftShape = RoundedCornerShape(
        topStart = outer.topStart,
        bottomStart = outer.bottomStart,
        topEnd = inner.topEnd,
        bottomEnd = inner.bottomEnd,
    )
    val rightShape = RoundedCornerShape(
        topStart = inner.topStart,
        bottomStart = inner.bottomStart,
        topEnd = outer.topEnd,
        bottomEnd = outer.bottomEnd,
    )
    Row(
        modifier = modifier.heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassPillButton(
            onClick = onLeftClick,
            description = leftDescription,
            stateDesc = leftStateDesc,
            shape = leftShape,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            content = leftContent,
        )
        GlassPillButton(
            onClick = onRightClick,
            description = rightDescription,
            stateDesc = rightStateDesc,
            shape = rightShape,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            content = rightContent,
        )
    }
}

@Composable
internal fun GlassChip(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = appShape(50.dp, CornerArea.Chips)
    val baseBg = Color.Black.copy(alpha = 0.48f)
    Row(
        modifier = modifier
            .clip(shape)
            .background(baseBg)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        content = content,
    )
}

@Composable
internal fun QualityBadgesRow(
    badges: QualityBadgeSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!badges.isHiRes && badges.codec == null && !badges.isBitPerfect) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (badges.isHiRes) {
            GlassChip(
                borderColor = ModernHiResGold.copy(alpha = 0.78f),
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = "Hi-Res audio, tap for output details" },
            ) {
                Canvas(Modifier.size(12.dp, 9.dp)) {
                    val bars = floatArrayOf(0.42f, 0.82f, 1f, 0.68f, 0.38f)
                    val bw = size.width / (bars.size * 2 - 1)
                    bars.forEachIndexed { i, f ->
                        val bh = size.height * f
                        drawRoundRect(
                            color = ModernHiResGold,
                            topLeft = Offset(i * 2 * bw, (size.height - bh) / 2f),
                            size = Size(bw, bh),
                            cornerRadius = CornerRadius(bw / 2f),
                        )
                    }
                }
                Text(
                    "HI-RES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                    ),
                    color = ModernHiResGold,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        badges.codec?.let { codec ->
            GlassChip(
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = "Audio format $codec, tap for output details" },
            ) {
                Text(
                    codec,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = Color.White.copy(alpha = 0.90f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        if (badges.isBitPerfect) {
            GlassChip(
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                onClick = onClick,
                modifier = Modifier.semantics { contentDescription = "Bit-perfect output active, tap for output details" },
            ) {
                AppIcon(TrueAudioIcon, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    "BIT-PERFECT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
