package io.max.paperplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.theme.LocalAmbientClock
import io.max.paperplay.ui.theme.LocalReduceMotion
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.PaperPlayTheme
import io.max.paperplay.ui.theme.motionEffectTween
import io.max.paperplay.ui.theme.motionPopSpring
import io.max.paperplay.ui.theme.motionSpatialSpring
import io.max.paperplay.ui.theme.rememberAmbientClock
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/** Fades and rises into place after [index] steps; later items arrive a moment after earlier ones. */
@Composable
internal fun Staggered(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = LocalReduceMotion.current
    val p = remember { Animatable(if (reduced) 1f else 0f) }
    val spec = motionEffectTween<Float>(Motion.Spatial.StaggerEnterMs, easing = Motion.Emphasized)
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        delay(index * Motion.Spatial.StaggerStepMs)
        p.animateTo(1f, spec)
    }
    Box(modifier.graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationY = (1f - p.value) * 48f }) { content() }
}

/** A rounded badge with an icon: its corners breathe between a circle and a soft square and it wiggles gently. */
@Composable
internal fun ExpressiveHero(
    icon: ImageVector, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 96.dp,
    /** Moves the glyph sideways inside the badge for icons whose weight sits off centre, like the paper plane. */
    nudge: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val reduced = LocalReduceMotion.current
    val clock = LocalAmbientClock.current ?: rememberAmbientClock(active = !reduced)
    clock.subscribe(active = !reduced)
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    val popSpec = motionPopSpring<Float>()
    LaunchedEffect(Unit) { if (!reduced) pop.animateTo(1f, popSpec) }
    Box(
        modifier.size(size).graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
            rotationZ = if (reduced) 0f else -6f + 12f * clock.pingPong(Motion.Ambient.HeroWigglePeriodMs)
        }
            .clip(RoundedCornerShape(38))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.offset(x = nudge).size(size / 2), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** One drifting shape of the tour background: where it starts, how fast it rises (whole loops, so the wrap is seamless) and how it sways. */
private class Drifter(
    val x: Float, val start: Float, val loops: Int, val size: Float, val note: Boolean, val tertiary: Boolean,
    val swayLoops: Int, val swayPhase: Float, val swayAmount: Float, val alpha: Float,
)

private const val BACKDROP_LOOP_MS = 60_000

/**
 * The colour wash behind the whole tour: two soft colour blobs that drift, and about 14 music notes and dots that rise slowly and
 * sway, in the theme's accent colours. Everything comes from one time value that is only read while drawing. With Reduce motion
 * on, only the still wash is drawn.
 */
@Composable
internal fun GradientBackdrop(modifier: Modifier = Modifier) {
    val reduced = LocalReduceMotion.current
    val clock = LocalAmbientClock.current ?: rememberAmbientClock(active = !reduced)
    clock.subscribe(active = !reduced)
    val a = MaterialTheme.colorScheme.primaryContainer
    val b = MaterialTheme.colorScheme.tertiaryContainer
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val notePainter = androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Rounded.MusicNote)
    val drifters = remember {
        val r = kotlin.random.Random(5)
        List(14) { i ->
            Drifter(
                x = r.nextFloat(), start = r.nextFloat(), loops = 1 + r.nextInt(3), size = 0.018f + r.nextFloat() * 0.03f,
                note = i % 2 == 0, tertiary = r.nextBoolean(), swayLoops = 3 + r.nextInt(4), swayPhase = r.nextFloat(),
                swayAmount = 0.02f + r.nextFloat() * 0.04f, alpha = 0.12f + r.nextFloat() * 0.12f,
            )
        }
    }
    Box(
        modifier.fillMaxSize().background(base).drawBehind {
            val drift = if (reduced) 0.3f else clock.pingPong(Motion.Ambient.OnboardingDriftMs)
            val loop = if (reduced) 0f else clock.phase(Motion.Ambient.OnboardingRiseMs)
            drawRect(
                Brush.radialGradient(
                    listOf(a.copy(alpha = 0.55f), a.copy(alpha = 0f)),
                    center = Offset(size.width * (0.15f + 0.4f * drift), size.height * 0.05f), radius = size.maxDimension * 0.8f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    listOf(b.copy(alpha = 0.4f), b.copy(alpha = 0f)),
                    center = Offset(size.width * (0.95f - 0.4f * drift), size.height * 0.95f), radius = size.maxDimension * 0.7f,
                ),
            )
            if (reduced) return@drawBehind
            for (d in drifters) {
                val progress = (d.start + loop * d.loops) % 1f
                val cx = size.width * (d.x + d.swayAmount * kotlin.math.sin(2f * kotlin.math.PI.toFloat() * (loop * d.swayLoops + d.swayPhase)))
                val cy = size.height * (1.08f - 1.16f * progress)
                // Fades in from the bottom and out at the top.
                val alpha = d.alpha * kotlin.math.sin(progress * kotlin.math.PI.toFloat())
                val color = (if (d.tertiary) tertiary else primary).copy(alpha = alpha)
                val w = size.minDimension * d.size * 2f
                if (d.note) {
                    translate(cx - w / 2, cy - w / 2) {
                        with(notePainter) { draw(androidx.compose.ui.geometry.Size(w, w), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)) }
                    }
                } else drawCircle(color, radius = w / 4, center = Offset(cx, cy))
            }
        },
    )
}

/** The progress dots; the current one stretches into a pill with a spring. */
@Composable
internal fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 28.dp else 8.dp, motionSpatialSpring(), label = "dot")
            Box(
                Modifier.padding(horizontal = 3.dp).size(width = w, height = 8.dp).clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/** Shared frame of a tour page: hero badge, title and lead, then the content, each arriving in turn. */
@Composable
internal fun OnboardingPage(
    title: String, lead: String, icon: ImageVector? = null, logo: Boolean = false, nudge: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        if (logo) {
            AnimatedLogo()
            Spacer(Modifier.height(20.dp))
        } else if (icon != null) {
            ExpressiveHero(icon, nudge = nudge)
            Spacer(Modifier.height(20.dp))
        }
        Staggered(0) { Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(10.dp))
        Staggered(1) { Text(lead, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(24.dp))
        Staggered(2) { Column { content() } }
    }
}

@Composable
internal fun Point(head: String, text: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(head, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Hero and page", widthDp = 420, heightDp = 520, showBackground = true)
@Composable
private fun OnboardingPagePreview() = PaperPlayTheme {
    Box(Modifier.fillMaxSize()) {
        GradientBackdrop()
        OnboardingPage("Paper Play", "Your Telegram chats are your music library.", logo = true) {
            Point("A bot reads your chat", "You add a bot to a chat or channel with your music.")
        }
    }
}
