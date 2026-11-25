package io.max.paperplay.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import io.max.paperplay.player.AudioTap
import io.max.paperplay.ui.rememberAppForeground
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

enum class StandbyStyle(val label: String) {
    Bars("Bars"), Wave("Wave"), Rings("Rings"), Orb("Orb"),
    Mirror("Mirror"), Peaks("Peaks"), Circle("Circle"), Particles("Particles"),
    AsciiBars("ASCII bars"), Matrix("Matrix"), AsciiPulse("ASCII pulse"),
    /** Scrolling lyrics in the animation area; bars while the song has no synced lyrics. */
    Lyrics("Lyrics"),
}

/**
 * A music visual that follows the live audio: [AudioTap] provides a playout-synchronized spectrum and every style
 * reads it through a smoother (fast attack, slow release) updated each frame. When there is no live audio (paused,
 * silent, or DoP/DSD), it settles into a calm idle state with no invented motion. The frame loop runs only while
 * [playing] and the app is in front; the animated values are read in the draw phase so nothing recomposes.
 */
@Composable
fun StandbyVisual(style: StandbyStyle, color: Color, playing: Boolean, volume: Float, seed: String, modifier: Modifier = Modifier) {
    val foreground = rememberAppForeground()
    val running = playing && foreground && !io.max.paperplay.ui.theme.LocalReduceMotion.current
    var frame by remember { mutableIntStateOf(0) }
    val sm = remember(seed) { Smoother() }
    LaunchedEffect(running, seed, style) {
        if (!running) { sm.settle(); frame++; return@LaunchedEffect }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                sm.update(dt, now, style)
                frame++
            }
        }
    }
    val idle = !running
    val tm = rememberTextMeasurer(cacheSize = 256)
    Canvas(modifier) {
        frame // read here so only drawing is redone
        when (style) {
            StandbyStyle.Bars, StandbyStyle.Lyrics -> drawBars(sm, idle, color)
            StandbyStyle.Wave -> drawWave(sm, idle, color)
            StandbyStyle.Rings -> drawRings(sm, idle, color)
            StandbyStyle.Orb -> drawOrb(sm, idle, color)
            StandbyStyle.Mirror -> drawMirror(sm, idle, color)
            StandbyStyle.Peaks -> drawPeaks(sm, idle, color)
            StandbyStyle.Circle -> drawCircleBars(sm, idle, color)
            StandbyStyle.Particles -> drawParticles(sm, idle, color)
            StandbyStyle.AsciiBars -> drawAsciiBars(sm, idle, color, tm)
            StandbyStyle.Matrix -> drawMatrix(sm, idle, color, tm)
            StandbyStyle.AsciiPulse -> drawAsciiPulse(sm, idle, color, tm)
        }
    }
}

internal data class StandbySmoothingProfile(val attackSec: Float, val releaseSec: Float)

internal fun smoothingProfileFor(style: StandbyStyle): StandbySmoothingProfile = when (style) {
    StandbyStyle.Wave, StandbyStyle.Rings, StandbyStyle.Orb, StandbyStyle.Particles, StandbyStyle.AsciiPulse ->
        StandbySmoothingProfile(attackSec = 0.022f, releaseSec = 0.165f)
    else ->
        StandbySmoothingProfile(attackSec = 0.014f, releaseSec = 0.115f)
}

/**
 * Display-rate asymmetric smoother reading playout-interpolated [AudioTap.TapFrame]
 * (multi-res spectrum, time-domain 140 Hz bass envelope, and fixed-hop onset).
 * Zero per-frame allocations.
 */
internal class Smoother {
    val n = AudioTap.BANDS
    val v = FloatArray(n)
    private val tapFrame = AudioTap.TapFrame(FloatArray(n))
    private val peakHold = FloatArray(n)
    private var lastBeatSeq = 0
    var bass = 0f
    var energy = 0f
    var onset = 0f
    var time = 0f
    var motionPhase = 0f
    var ring = 0f
    var liveSignal = false
    val peak = FloatArray(n)
    val drop = FloatArray(64) { (it * 0.37f) % 1f }
    // Particles: x and y in 0..1 (y grows downward), speed, life in seconds.
    val px = FloatArray(140); val py = FloatArray(140); val pv = FloatArray(140); val pl = FloatArray(140); val ps = FloatArray(140)
    private var spawn = 0
    val wavePaths = arrayOfNulls<Path>(3)
    val orbOuterColors = ArrayList<Color>(2).apply { add(Color.Transparent); add(Color.Transparent) }
    val orbInnerColors = ArrayList<Color>(2).apply { add(Color.Transparent); add(Color.Transparent) }
    val asciiSb = StringBuilder(64)

    /** A spark leaves the bottom at the x of band [band], rising faster the louder that band is. */
    private fun emit(band: Int, level: Float) {
        val rnd = kotlin.random.Random
        val i = spawn++ % px.size
        px[i] = (band + 0.5f + (rnd.nextFloat() - 0.5f)) / n
        py[i] = 1f; pv[i] = 0.30f + 0.95f * level; pl[i] = 0.65f + 0.85f * level
        ps[i] = 2f + 3f * rnd.nextFloat()
    }

    fun settle() {
        v.fill(0f); peak.fill(0f); peakHold.fill(0f); pl.fill(0f)
        bass = 0f; energy = 0f; onset = 0f; liveSignal = false
        lastBeatSeq = 0
    }

    fun update(dt: Float, nowNs: Long = System.nanoTime(), style: StandbyStyle = StandbyStyle.Bars) {
        val safeDt = dt.coerceIn(0.001f, 0.1f)
        time += safeDt
        val live = AudioTap.sampleFrame(tapFrame, nowNs)
        var beatHit = false
        if (live) {
            onset = tapFrame.onset
            if (tapFrame.beatSeq != lastBeatSeq) {
                beatHit = true
                lastBeatSeq = tapFrame.beatSeq
            }
        } else {
            onset = 0f
            lastBeatSeq = 0
        }

        val profile = smoothingProfileFor(style)
        val atkAlpha = 1f - exp(-safeDt / profile.attackSec)
        val relAlpha = 1f - exp(-safeDt / profile.releaseSec)

        var sum = 0f
        for (i in 0 until n) {
            val target = if (live) tapFrame.bands[i] else 0f
            val alpha = if (target > v[i]) atkAlpha else relAlpha
            v[i] += (target - v[i]) * alpha
            if (v[i] < 0.0005f) v[i] = 0f
            sum += v[i]
        }

        val bassTarget = if (live) tapFrame.bass else 0f
        val bassTau = if (bassTarget > bass) 0.010f else 0.085f
        bass += (bassTarget - bass) * (1f - exp(-safeDt / bassTau))
        if (bass < 0.0005f) bass = 0f

        energy = sum / n
        liveSignal = live && (energy > 0.002f || bass > 0.004f)

        // No audio means no motion: phases, rings, drops, and particle emission advance only with real sound.
        if (liveSignal) {
            motionPhase += safeDt * (0.45f + 1.35f * energy)
            ring = (ring + safeDt * (0.50f * energy + 0.15f * bass) + (if (beatHit) 0.09f * onset else 0f)) % 1f
            for (i in drop.indices) drop[i] = (drop[i] + safeDt * (0.04f + 0.95f * v[i * n / drop.size])) % 1f
            for (i in 0 until n) {
                if (v[i] > 0.26f && kotlin.random.Random.nextFloat() < v[i] * v[i] * safeDt * 16f) emit(i, v[i])
            }
            if (beatHit) {
                for (i in 0 until 8) {
                    if (v[i] > 0.25f) emit(i, maxOf(v[i], bass))
                }
            }
        }
        for (i in 0 until n) {
            if (v[i] >= peak[i]) {
                peak[i] = v[i]
                peakHold[i] = 0.12f
            } else if (peakHold[i] > 0f) {
                peakHold[i] = (peakHold[i] - safeDt).coerceAtLeast(0f)
            } else {
                peak[i] = (peak[i] - safeDt * 0.55f).coerceAtLeast(0f)
            }
        }
        for (i in px.indices) if (pl[i] > 0f) {
            pl[i] = (pl[i] - safeDt).coerceAtLeast(0f)
            py[i] -= pv[i] * safeDt
            px[i] += sin(motionPhase * 3f + i) * safeDt * 0.02f
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(sm: Smoother, idle: Boolean, color: Color) {
    val n = sm.n
    val slot = size.width / n
    val w = slot * 0.55f
    for (i in 0 until n) {
        val a = if (idle) 0.05f else sm.v[i]
        val half = size.height * 0.5f * a.coerceIn(0.03f, 1f)
        val x = slot * (i + 0.5f)
        drawLine(color.copy(alpha = 0.3f + 0.6f * a), Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), w, StrokeCap.Round)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(sm: Smoother, idle: Boolean, color: Color) {
    val mid = size.height / 2
    val steps = 64
    val ripple = if (idle) 0f else (4f * sm.energy.coerceIn(0f, 1f))
    for (layer in 0 until 3) {
        val path = (sm.wavePaths[layer] ?: Path().also { sm.wavePaths[layer] = it }).apply { rewind() }
        for (s in 0..steps) {
            val u = s.toFloat() / steps
            val env = sin(u * PI.toFloat())
            // Each layer reads its own part of the spectrum, so the curve takes the shape of the sound.
            val lo = layer * sm.n / 3; val hi = lo + sm.n / 3
            val idx = lo + (u * (hi - lo - 1)).toInt()
            val a = if (idle) 0.04f else sm.v[idx]
            val sign = if ((s + layer) % 2 == 0) 1f else -1f
            val y = mid + sign * size.height * 0.42f * env * a * (1f - layer * 0.2f) + ripple * env * sin(u * 9f + sm.motionPhase * (1f + layer))
            if (s == 0) path.moveTo(0f, y) else path.lineTo(size.width * u, y)
        }
        drawPath(path, color.copy(alpha = 0.85f - layer * 0.25f), style = Stroke(width = 4f + (2 - layer) * 2f, cap = StrokeCap.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRings(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val punch = if (idle) 0f else (0.65f * sm.bass + 0.55f * sm.onset).coerceIn(0f, 1.2f)
    for (i in 0 until 4) {
        val p = if (idle) (i + 1) / 5f else (sm.ring + i / 4f) % 1f
        val radius = max * (0.25f + 0.7f * p) * (1f + 0.08f * punch)
        drawCircle(color.copy(alpha = (1f - p) * (0.25f + 0.6f * (if (idle) 0.3f else sm.energy.coerceAtLeast(0.3f)))), radius, c, style = Stroke(width = 3f + 5f * (1f - p) + 4f * punch))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val bass = if (idle) 0.04f else sm.bass
    val glow = if (idle) 0.2f else sm.energy
    val hit = if (idle) 0f else sm.onset
    val core = max * (0.42f + 0.28f * bass + 0.10f * hit)
    sm.orbOuterColors[0] = color.copy(alpha = 0.3f + 0.4f * glow)
    sm.orbOuterColors[1] = Color.Transparent
    sm.orbInnerColors[0] = color.copy(alpha = 0.95f)
    sm.orbInnerColors[1] = color.copy(alpha = 0.35f)
    drawCircle(Brush.radialGradient(sm.orbOuterColors, c, max), max * (0.85f + 0.3f * glow), c)
    drawCircle(Brush.radialGradient(sm.orbInnerColors, c, core), core, c)
}
