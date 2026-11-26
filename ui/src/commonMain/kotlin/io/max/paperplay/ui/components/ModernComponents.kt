package io.max.paperplay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SplitButtonShapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.TonalToggleButton
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Cubic
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import io.max.paperplay.ui.LocalGlassBackdrop
import io.max.paperplay.ui.app.CornerArea
import io.max.paperplay.ui.app.GlassStyle
import io.max.paperplay.ui.app.LocalUiPrefs
import io.max.paperplay.ui.app.Look
import io.max.paperplay.ui.app.LookGroup
import io.max.paperplay.ui.app.UiStyle
import io.max.paperplay.ui.app.appShape
import io.max.paperplay.ui.app.blockTouchesBehind
import io.max.paperplay.ui.glassLensSupported
import io.max.paperplay.ui.isPhone
import io.max.paperplay.ui.rememberAppForeground
import io.max.paperplay.ui.theme.LocalAmbientClock
import io.max.paperplay.ui.theme.LocalModernTokens
import io.max.paperplay.ui.theme.LocalReduceMotion
import io.max.paperplay.ui.theme.LocalReducedTransparency
import io.max.paperplay.ui.theme.PaperPlayTheme
import io.max.paperplay.ui.theme.motionModernPressSpec
import io.max.paperplay.ui.theme.motionMorphSpec
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** True while inside an outer Modern card/bar/overlay; prevents nested glass/surface layers. */
internal val LocalInsideModernSurface = compositionLocalOf { false }

/** Surface depth tiers for Modern UI. [Row] intentionally never uses live backdrop blur. */
enum class ModernSurfaceTier {
    Chrome,
    Card,
    Row,
    Overlay,
}

/**
 * Removes all shadows/elevation in Modern: layers separate strictly by tonal contrast and blur.
 */
internal fun modernSurfaceStatic(fill: Color, shape: Shape): Modifier =
    Modifier.clip(shape).background(fill)

/**
 * Shared horizontal gradient brush for both [ModernMiniPlayer] and [NavBar] so the floating
 * docks always share the exact same cover-tinted fill and alpha.
 */
internal fun modernDockBrush(
    tokens: io.max.paperplay.ui.theme.ModernTokens,
    cover: androidx.compose.material3.ColorScheme?,
): Brush {
    val d = tokens.dock
    val left = if (cover != null) {
        lerp(d.fill, cover.primaryContainer.copy(alpha = d.fill.alpha), d.coverTintWeight)
    } else {
        d.fill
    }
    val right = if (cover != null) {
        lerp(d.fillAlt, cover.tertiaryContainer.copy(alpha = d.fillAlt.alpha), d.coverTintWeight * 0.72f)
    } else {
        d.fillAlt
    }
    return Brush.horizontalGradient(listOf(left, right))
}

/**
 * Applies the borderless, shadowless Modern frosted-glass or stepped tonal surface for [tier] when
 * [LocalModernTokens] is enabled. Separates layers strictly via tonal fills and blur — zero outlines,
 * zero shadows, and zero nested surfaces.
 */
@Composable
fun Modifier.modernSurface(
    tier: ModernSurfaceTier,
    shape: Shape,
    lookGroup: LookGroup = LookGroup.Cards,
    active: Boolean = false,
    selected: Boolean = false,
    accentColor: Color = Color.Unspecified,
    gradientBrush: Brush? = null,
    blurOverride: Dp? = null,
): Modifier {
    val tokens = LocalModernTokens.current
    if (!tokens.enabled || LocalInsideModernSurface.current) return this

    val prefs = LocalUiPrefs.current
    val backdrop = LocalGlassBackdrop.current
    val reduced = LocalReducedTransparency.current

    val baseFill = when (tier) {
        ModernSurfaceTier.Chrome -> tokens.chromeBg
        ModernSurfaceTier.Card -> tokens.cardBg
        ModernSurfaceTier.Row -> when {
            selected -> tokens.rowSelectedBg
            active -> tokens.rowCurrentBg
            else -> tokens.rowBg
        }
        ModernSurfaceTier.Overlay -> tokens.overlayBg
    }
    val fill = if (active && accentColor != Color.Unspecified && tier != ModernSurfaceTier.Row) {
        lerp(baseFill, accentColor.copy(alpha = baseFill.alpha), 0.22f)
    } else {
        baseFill
    }

    // Hard rule: Row never calls drawBackdrop (no live blur in scrolling lists).
    val useLiveBlur = tier != ModernSurfaceTier.Row &&
        tier != ModernSurfaceTier.Overlay &&
        backdrop != null &&
        !reduced &&
        isPhone &&
        prefs?.look(lookGroup) != Look.Material

    if (!useLiveBlur) {
        return this
            .clip(shape)
            .then(if (gradientBrush != null) Modifier.background(gradientBrush) else Modifier.background(fill))
    }

    val g = prefs?.glass ?: GlassStyle()
    val blurDp = blurOverride ?: g.blur.coerceAtLeast(8f).dp
    return this
        .clip(shape)
        .drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                if (g.saturation != 1f) colorControls(saturation = g.saturation)
                blur(blurDp.toPx())
                if (glassLensSupported() && tier == ModernSurfaceTier.Chrome) {
                    lens(g.lensHeight.dp.toPx(), g.lensAmount.dp.toPx(), g.depth, g.chromatic)
                }
            },
            highlight = null,
            shadow = null,
            innerShadow = null,
            onDrawSurface = {
                if (gradientBrush != null) drawRect(gradientBrush) else drawRect(fill)
            },
        )
}

/**
 * Expressive Play/Pause button morphing between [MaterialShapes.Circle] and [MaterialShapes.Cookie9Sided]
 * using a low-bounce spring and press scale. Allocates `Path` lazily inside `DrawScope` for headless test safety.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    contentDescription: String = if (playing) "Pause" else "Play",
) {
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = motionModernPressSpec(),
        label = "modernPlayPress",
    )
    val morphProgress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = motionMorphSpec(),
        label = "modernPlayMorph",
    )
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            // Allocate Path lazily inside draw scope so non-Skia unit tests never touch native Path.
            val path = Path()
            val matrix = Matrix().apply {
                scale(this@Canvas.size.width, this@Canvas.size.height)
            }
            val p = morphProgress.coerceIn(0f, 1f)
            morph.forEachCubic(p) { c: Cubic ->
                if (path.isEmpty) path.moveTo(c.anchor0X, c.anchor0Y)
                path.cubicTo(
                    c.control0X, c.control0Y,
                    c.control1X, c.control1Y,
                    c.anchor1X, c.anchor1Y,
                )
            }
            path.close()
            path.transform(matrix)
            drawPath(path = path, color = containerColor)
        }
        AppIcon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(size * 0.54f),
            tint = contentColor,
        )
    }
}

internal const val WAVY_RING_LOBES = 10

/**
 * Single parametric contour shared by BOTH the scalloped play/pause button and the surrounding
 * wavy progress ring (`r(theta) = R + A * cos(N * (theta + pi/2))`), so every lobe peak aligns
 * radially at 12 o'clock and every angle with a visually even gap.
 */
internal fun scallopedPolarRadius(
    baseRadius: Float,
    amplitudePx: Float,
    angleRad: Float,
    lobes: Int = WAVY_RING_LOBES,
): Float = baseRadius + amplitudePx * cos(lobes * (angleRad + (PI / 2.0).toFloat()))

/** Generates `[x0, y0, x1, y1, ...]` vertices along a closed [scallopedPolarRadius] contour. */
internal fun buildScallopedPoints(
    cx: Float,
    cy: Float,
    baseRadius: Float,
    amplitudePx: Float,
    lobes: Int = WAVY_RING_LOBES,
    steps: Int = 180,
): FloatArray {
    val pts = FloatArray(steps * 2)
    val twoPi = (2.0 * PI).toFloat()
    for (i in 0 until steps) {
        val angle = twoPi * i / steps
        val r = scallopedPolarRadius(baseRadius, amplitudePx, angle, lobes)
        pts[i * 2] = cx + r * cos(angle)
        pts[i * 2 + 1] = cy + r * sin(angle)
    }
    return pts
}

private fun FloatArray.toClosedPath(): Path = Path().apply {
    moveTo(this@toClosedPath[0], this@toClosedPath[1])
    var i = 2
    while (i < this@toClosedPath.size) {
        lineTo(this@toClosedPath[i], this@toClosedPath[i + 1])
        i += 2
    }
    close()
}

/**
 * Floating mini-player control: a static scalloped play/pause button centered inside a matching
 * concentric dual-layer circular wavy progress ring (`N = 10` aligned lobes):
 * - Both button and ring share [scallopedPolarRadius] with the same lobe count and phase so the
 *   gap between button and ring is uniform at every angle.
 * - Track layer: static full 360° wavy path in [trackColor].
 * - Active layer: identical static wavy path in [ringColor] (dimmed slightly when paused),
 *   masked by an angular sweep from 12 o'clock to current progress, with rounded start and tip caps.
 * - Zero rotation or phase animation in any state: only the progress sweep advances.
 * - Paths are built once per size via `drawWithCache`; progress is read in draw phase with zero per-frame allocations.
 */
@Composable
fun ModernCircularPlayProgress(
    playing: Boolean,
    progress: () -> Float,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
    size: Dp = 50.dp,
    ringColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.22f),
    buttonContainerColor: Color = Color.White.copy(alpha = 0.22f),
    buttonContentColor: Color = Color.White,
) {
    val reduceMotion = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.94f else 1f,
        animationSpec = motionModernPressSpec(),
        label = "modernPlayPress",
    )

    val density = LocalDensity.current
    val strokePx = with(density) { 2.6.dp.toPx() }
    val amplitudePx = with(density) { 1.4.dp.toPx() }
    val gapPx = with(density) { 3.0.dp.toPx() }

    // Retain covered/active parameters for call-site compatibility; wave is strictly static in all states.
    @Suppress("UNUSED_EXPRESSION")
    active && !covered

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .drawWithCache {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val ringBaseRadius = (minOf(this.size.width, this.size.height) / 2f) - strokePx / 2f - amplitudePx
                    val buttonBaseRadius = (ringBaseRadius - strokePx / 2f - gapPx).coerceAtLeast(1f)
                    val wavyRingPath = buildScallopedPoints(cx, cy, ringBaseRadius, amplitudePx, WAVY_RING_LOBES, 180).toClosedPath()
                    val buttonPath = buildScallopedPoints(cx, cy, buttonBaseRadius, amplitudePx, WAVY_RING_LOBES, 180).toClosedPath()
                    val wedgePath = Path()
                    val stroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val pad = strokePx * 2f
                    val outerArcRect = Rect(-pad, -pad, this.size.width + pad, this.size.height + pad)
                    val capRadius = strokePx / 2f
                    val twoPi = (2.0 * PI).toFloat()

                    onDrawBehind {
                        // Concentric scalloped button fill (scaled around center on press)
                        if (pressScale != 1f) {
                            withTransform({
                                scale(scaleX = pressScale, scaleY = pressScale, pivot = Offset(cx, cy))
                            }) {
                                drawPath(path = buttonPath, color = buttonContainerColor)
                            }
                        } else {
                            drawPath(path = buttonPath, color = buttonContainerColor)
                        }

                        // 1. Static full 360° wavy track
                        drawPath(path = wavyRingPath, color = trackColor, style = stroke)

                        // 2. Identical static wavy path masked by angular sweep from 12 o'clock (-90°)
                        val p = progress().coerceIn(0f, 1f)
                        val activeCol = if (playing) ringColor else ringColor.copy(alpha = 0.62f)
                        if (p >= 0.999f) {
                            drawPath(path = wavyRingPath, color = activeCol, style = stroke)
                        } else if (p > 0.001f) {
                            val sweepDeg = 360f * p
                            wedgePath.reset()
                            wedgePath.moveTo(cx, cy)
                            wedgePath.arcTo(outerArcRect, startAngleDegrees = -90f, sweepAngleDegrees = sweepDeg, forceMoveTo = false)
                            wedgePath.close()

                            clipPath(wedgePath) {
                                drawPath(path = wavyRingPath, color = activeCol, style = stroke)
                            }

                            // Rounded caps at 12 o'clock start and progress tip on the exact scalloped contour
                            val startAngle = (-PI / 2.0).toFloat()
                            val endAngle = startAngle + twoPi * p
                            val rStart = scallopedPolarRadius(ringBaseRadius, amplitudePx, startAngle, WAVY_RING_LOBES)
                            val rEnd = scallopedPolarRadius(ringBaseRadius, amplitudePx, endAngle, WAVY_RING_LOBES)
                            drawCircle(
                                color = activeCol,
                                radius = capRadius,
                                center = Offset(cx + rStart * cos(startAngle), cy + rStart * sin(startAngle)),
                            )
                            drawCircle(
                                color = activeCol,
                                radius = capRadius,
                                center = Offset(cx + rEnd * cos(endAngle), cy + rEnd * sin(endAngle)),
                            )
                        }
                    }
                }
                .semantics { this.contentDescription = if (playing) "Pause" else "Play" }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(size * 0.40f).graphicsLayer { scaleX = pressScale; scaleY = pressScale },
                tint = buttonContentColor,
            )
        }
    }
}

/** Small static scalloped play badge for Modern Daily Mix cards (allocates Path lazily in DrawScope). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernScallopedBadge(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    containerColor: Color = Color.White.copy(alpha = 0.90f),
    contentColor: Color = Color(0xFF141418),
    polygon: RoundedPolygon = MaterialShapes.Cookie9Sided,
) {
    val morph = remember(polygon) { Morph(polygon, polygon) }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().drawWithCache {
                val path = Path()
                val matrix = Matrix().apply { scale(this@drawWithCache.size.width, this@drawWithCache.size.height) }
                morph.forEachCubic(0f) { c: Cubic ->
                    if (path.isEmpty) path.moveTo(c.anchor0X, c.anchor0Y)
                    path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
                }
                path.close()
                path.transform(matrix)
                onDrawBehind {
                    drawPath(path = path, color = containerColor)
                }
            },
        )
        AppIcon(Icons.Rounded.PlayArrow, null, Modifier.size(size * 0.56f), tint = contentColor)
    }
}

/** Wrapped M3 Expressive [TonalToggleButton] with Modern borderless glass styling and morphing corners. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val tokens = LocalModernTokens.current
    val scheme = MaterialTheme.colorScheme
    val shapes = ToggleButtonShapes(
        shape = appShape(18.dp, CornerArea.Chips),
        pressedShape = appShape(10.dp, CornerArea.Chips),
        checkedShape = appShape(24.dp, CornerArea.Chips),
    )
    val colors = if (tokens.enabled) {
        ToggleButtonDefaults.tonalToggleButtonColors(
            containerColor = tokens.cardBgElevated,
            contentColor = tokens.onGlass,
            checkedContainerColor = scheme.primaryContainer,
            checkedContentColor = scheme.onPrimaryContainer,
        )
    } else {
        ToggleButtonDefaults.tonalToggleButtonColors()
    }
    TonalToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        shapes = shapes,
        colors = colors,
    ) {
        if (icon != null) {
            AppIcon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, maxLines = 1)
    }
}

/** Wrapped M3 Expressive [SplitButtonLayout] with outer round and inner tighter corners. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernSplitButton(
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryContentDescription: String,
    onSecondaryClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryIcon: ImageVector? = null,
) {
    val outer = appShape(24.dp, CornerArea.Cards)
    val inner = appShape(6.dp, CornerArea.Chips)
    val leadingShape = RoundedCornerShape(
        topStart = outer.topStart,
        bottomStart = outer.bottomStart,
        topEnd = inner.topEnd,
        bottomEnd = inner.bottomEnd,
    )
    val trailingShape = RoundedCornerShape(
        topStart = inner.topStart,
        bottomStart = inner.bottomStart,
        topEnd = outer.topEnd,
        bottomEnd = outer.bottomEnd,
    )
    val pressedShape = appShape(12.dp, CornerArea.Chips)
    val leadingShapes = SplitButtonShapes(shape = leadingShape, pressedShape = pressedShape, checkedShape = leadingShape)
    val trailingShapes = SplitButtonShapes(shape = trailingShape, pressedShape = pressedShape, checkedShape = outer)
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = onPrimaryClick,
                shapes = leadingShapes,
            ) {
                if (primaryIcon != null) {
                    AppIcon(primaryIcon, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(primaryLabel, maxLines = 1)
            }
        },
        trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = false,
                onCheckedChange = { onSecondaryClick() },
                shapes = trailingShapes,
                modifier = Modifier.semantics { contentDescription = secondaryContentDescription },
            ) {
                AppIcon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp))
            }
        },
    )
}

/** Wrapped M3 Expressive [ButtonGroup] for connected action pills. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernButtonGroup(
    modifier: Modifier = Modifier,
    content: ButtonGroupScope.() -> Unit,
) {
    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState)
        },
        modifier = modifier,
        content = content,
    )
}

/** Wrapped M3 Expressive [HorizontalFloatingToolbar] with touch-blocking and borderless glass styling. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernFloatingToolbar(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = LocalModernTokens.current
    val colors = if (tokens.enabled) {
        FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = tokens.chromeBg,
            toolbarContentColor = tokens.onGlass,
        )
    } else {
        FloatingToolbarDefaults.standardFloatingToolbarColors()
    }
    HorizontalFloatingToolbar(
        expanded = expanded,
        modifier = modifier.blockTouchesBehind(),
        colors = colors,
        content = content,
    )
}

/** Wrapped M3 Expressive [LoadingIndicator] / [ContainedLoadingIndicator]; static under Reduce Motion. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernLoadingIndicator(
    modifier: Modifier = Modifier,
    contained: Boolean = false,
    size: Dp = 36.dp,
) {
    val tokens = LocalModernTokens.current
    val reduceMotion = LocalReduceMotion.current
    if (!tokens.enabled) {
        CircularProgressIndicator(modifier.size(size), strokeWidth = 3.dp)
        return
    }
    if (reduceMotion) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = 3.dp,
            color = tokens.accentOnGlass,
        )
        return
    }
    if (contained) {
        ContainedLoadingIndicator(
            modifier = modifier.size(size),
            containerColor = tokens.cardBgElevated,
            indicatorColor = tokens.accentOnGlass,
        )
    } else {
        LoadingIndicator(
            modifier = modifier.size(size),
            color = tokens.accentOnGlass,
        )
    }
}

/**
 * Wrapped M3 Expressive [LinearWavyProgressIndicator].
 * Pauses wave motion when [!active], [covered], backgrounded, or [LocalReduceMotion] is true,
 * dropping to a zero-cost flat line so idle/hidden screens pay zero frame overhead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModernWavyProgress(
    progress: () -> Float,
    active: Boolean,
    modifier: Modifier = Modifier,
    covered: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = 0.20f),
) {
    val tokens = LocalModernTokens.current
    val reduceMotion = LocalReduceMotion.current
    val inForeground = rememberAppForeground()
    val animateWave = tokens.enabled && active && !covered && inForeground && !reduceMotion
    if (!animateWave) {
        LinearProgressIndicator(
            progress = { progress().coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(3.dp),
            color = color,
            trackColor = trackColor,
        )
        return
    }
    LinearWavyProgressIndicator(
        progress = { progress().coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = trackColor,
        amplitude = { p -> if (p > 0.01f) 0.85f else 0f },
        wavelength = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
        waveSpeed = WavyProgressIndicatorDefaults.LinearDeterminateWavelength * 0.65f,
    )
}

/** Borderless filled text field for Modern dialogs and settings. */
@Composable
fun ModernFilledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val tokens = LocalModernTokens.current
    val shape = appShape(16.dp, CornerArea.Chips)
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = singleLine,
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = if (tokens.enabled) tokens.fieldBg else MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = if (tokens.enabled) tokens.fieldBg else MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/** Compact live gallery shown in Settings > Appearance when Modern is active so all wrapped APIs can be tested. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModernControlsShowcase(modifier: Modifier = Modifier) {
    val tokens = LocalModernTokens.current
    if (!tokens.enabled) return
    var playing by remember { mutableStateOf(true) }
    var toggled by remember { mutableStateOf(true) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .modernSurface(ModernSurfaceTier.Card, appShape(22.dp, CornerArea.Cards), LookGroup.Cards)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CompositionLocalProvider(LocalInsideModernSurface provides true) {
            Text("Expressive controls", style = MaterialTheme.typography.titleSmall, color = tokens.onGlass)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModernCircularPlayProgress(
                    playing = playing,
                    progress = { 0.64f },
                    active = playing,
                    onClick = { playing = !playing },
                    ringColor = tokens.accentOnGlass,
                    trackColor = tokens.onGlass.copy(alpha = 0.18f),
                    buttonContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    buttonContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                ModernToggleButton(checked = toggled, onCheckedChange = { toggled = it }, label = if (toggled) "Glass" else "Muted", icon = Icons.Rounded.Tune)
                ModernLoadingIndicator(size = 32.dp)
            }
            ModernSplitButton(
                primaryLabel = "Shuffle",
                primaryIcon = Icons.Rounded.Shuffle,
                onPrimaryClick = { playing = !playing },
                secondaryContentDescription = "More actions",
                onSecondaryClick = { toggled = !toggled },
            )
            ModernButtonGroup {
                clickableItem(onClick = { playing = true }, label = "Play")
                clickableItem(onClick = { playing = false }, label = "Pause")
            }
            ModernFloatingToolbar(expanded = toggled) {
                androidx.compose.material3.IconButton(onClick = { playing = !playing }) {
                    AppIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Toggle")
                }
                androidx.compose.material3.IconButton(onClick = { toggled = !toggled }) {
                    AppIcon(Icons.Rounded.Tune, "Mode")
                }
            }
            ModernWavyProgress(progress = { 0.62f }, active = playing, color = tokens.accentOnGlass)
        }
    }
}

@Preview(name = "Wavy Ring Progress Sweep", showBackground = true)
@Composable
private fun ModernCircularPlayProgressSweepPreview() {
    PaperPlayTheme(dark = true, amoled = false, uiStyle = UiStyle.Modern) {
        Row(
            Modifier.background(Color(0xFF14141A)).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(0f, 0.05f, 0.25f, 0.50f, 0.90f).forEach { p ->
                ModernCircularPlayProgress(playing = true, progress = { p }, active = true, onClick = {})
            }
            ModernCircularPlayProgress(playing = false, progress = { 0.50f }, active = false, onClick = {})
        }
    }
}

@Preview(name = "Modern Components - Dark", showBackground = true)
@Composable
private fun ModernComponentsDarkPreview() {
    PaperPlayTheme(dark = true, amoled = false, uiStyle = UiStyle.Modern) {
        Box(Modifier.background(Color(0xFF121318)).padding(16.dp)) {
            ModernControlsShowcase()
        }
    }
}

@Preview(name = "Modern Components - Frosted Light", showBackground = true)
@Composable
private fun ModernComponentsLightPreview() {
    PaperPlayTheme(dark = false, amoled = false, uiStyle = UiStyle.Modern) {
        Box(Modifier.background(Color(0xFFEAEFF8)).padding(16.dp)) {
            ModernControlsShowcase()
        }
    }
}

@Preview(name = "Modern Components - True Black", showBackground = true)
@Composable
private fun ModernComponentsAmoledPreview() {
    PaperPlayTheme(dark = true, amoled = true, uiStyle = UiStyle.Modern) {
        Box(Modifier.background(Color.Black).padding(16.dp)) {
            ModernControlsShowcase()
        }
    }
}

@Preview(name = "Modern Components - 200% Font Scale", showBackground = true)
@Composable
private fun ModernComponentsLargeFontPreview() {
    val cur = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(cur.density, fontScale = 2.0f)) {
        PaperPlayTheme(dark = true, amoled = false, uiStyle = UiStyle.Modern) {
            Box(Modifier.background(Color(0xFF121318)).padding(16.dp)) {
                ModernControlsShowcase()
            }
        }
    }
}
