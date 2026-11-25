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
import kotlin.math.abs
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
 * A music visual that follows the audio: the player's [AudioTap] gives a live spectrum and every style reads it through
 * a smoother (fast attack, slow release) updated each frame. Where no live audio arrives (desktop, or the tap is idle)
 * it falls back to a slow procedural motion seeded by the song id. The frame loop runs only while [playing] and the
 * app is in front; the animated values are read in the draw phase so nothing recomposes.
 */
@Composable
fun StandbyVisual(style: StandbyStyle, color: Color, playing: Boolean, volume: Float, seed: String, modifier: Modifier = Modifier) {
    val foreground = rememberAppForeground()
    val running = playing && foreground && !io.max.paperplay.ui.theme.LocalReduceMotion.current
    var frame by remember { mutableIntStateOf(0) }
    val rhythm = remember(seed) { Rhythm(seed) }
    val sm = remember(seed) { Smoother() }
    LaunchedEffect(running, seed) {
        if (!running) { sm.settle(); frame++; return@LaunchedEffect }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                sm.update(dt, rhythm, volume)
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

/** Smoothed spectrum, bass and energy with fast attack and slow release, plus phases that advance with the music. */
internal class Smoother {
    val n = AudioTap.BANDS
    val v = FloatArray(n)
    var bass = 0f
    var energy = 0f
    var time = 0f
    var ring = 0f
    private var prevBass = 0f
    val peak = FloatArray(n)
    val drop = FloatArray(64) { (it * 0.37f) % 1f }
    // Particles: x and y in 0..1 (y grows downward), speed, life in seconds.
    val px = FloatArray(140); val py = FloatArray(140); val pv = FloatArray(140); val pl = FloatArray(140); val ps = FloatArray(140)
    private var spawn = 0

    /** A spark leaves the bottom at the x of band [band], rising faster the louder that band is. */
    private fun emit(band: Int, level: Float) {
        val rnd = kotlin.random.Random
        val i = spawn++ % px.size
        px[i] = (band + 0.5f + (rnd.nextFloat() - 0.5f)) / n
        py[i] = 1f; pv[i] = 0.25f + 0.9f * level; pl[i] = 0.7f + 0.9f * level
        ps[i] = 2f + 3f * rnd.nextFloat()
    }

    fun settle() { v.fill(0f); peak.fill(0f); bass = 0f; energy = 0f; pl.fill(0f) }

    fun update(dt: Float, r: Rhythm, volume: Float) {
        time += dt
        val live = AudioTap.fresh()
        // No audio means no motion: a quiet baseline, never invented movement.
        val src = if (live) AudioTap.read(DELAY_MS) else null
        var sum = 0f; var b = 0f
        for (i in 0 until n) {
            val target = src?.get(i) ?: 0.03f
            val tau = if (target > v[i]) 0.035f else 0.22f
            v[i] += (target - v[i]) * (1f - exp(-dt / tau))
            sum += v[i]
            if (i < 4) b += v[i]
        }
        bass = b / 4f
        energy = sum / n
        // Rings travel faster on loud passages and jump forward on a bass hit.
        ring = (ring + dt * (0.08f + 0.5f * energy) + (if (bass - prevBass > 0.08f) 0.06f else 0f)) % 1f
        for (i in 0 until n) peak[i] = maxOf(v[i], peak[i] - dt * 0.45f)
        for (i in drop.indices) drop[i] = (drop[i] + dt * (0.12f + 0.9f * v[i * n / drop.size])) % 1f
        for (i in 0 until n) if (v[i] > 0.3f && kotlin.random.Random.nextFloat() < v[i] * v[i] * dt * 14f) emit(i, v[i])
        for (i in px.indices) if (pl[i] > 0f) { pl[i] -= dt; py[i] -= pv[i] * dt; px[i] += kotlin.math.sin(time * 3f + i) * dt * 0.02f }
        prevBass = bass
    }
}

/** Tempo and per-band phases and speeds derived from the song id, for the fallback motion. */
private const val DELAY_MS = 120L

internal class Rhythm(seed: String) {
    private val h = seed.fold(7L) { a, c -> a * 31 + c.code }.let { abs(it) }
    val bpm = 84f + (h % 56)
    private fun frac(n: Int): Float = (((h * (n + 3) * 2654435761L) ushr 7) % 1000L) / 1000f
    val phase = FloatArray(48) { frac(it) * 2f * PI.toFloat() }
    val speed = FloatArray(48) { 1.2f + frac(it + 60) * 3.2f }
    fun kick(t: Float): Float { val p = (t * bpm / 60f) % 1f; return exp(-p * 5f) }
    /** 0..1 for band [i] of [n]; low bands follow the kick more. */
    fun band(i: Int, n: Int, t: Float, kick: Float): Float {
        val k = i % 48
        val wob = 0.5f + 0.5f * sin(t * speed[k] + phase[k])
        val low = 1f - i.toFloat() / n
        return (0.12f + 0.88f * wob * (0.5f + 0.5f * (kick * low + (1f - low) * 0.6f))).coerceIn(0f, 1f)
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
    for (layer in 0 until 3) {
        val path = Path()
        for (s in 0..steps) {
            val u = s.toFloat() / steps
            val env = sin(u * PI.toFloat())
            // Each layer reads its own part of the spectrum, so the curve takes the shape of the sound.
            val lo = layer * sm.n / 3; val hi = lo + sm.n / 3
            val idx = lo + (u * (hi - lo - 1)).toInt()
            val a = if (idle) 0.04f else sm.v[idx]
            val sign = if ((s + layer) % 2 == 0) 1f else -1f
            val y = mid + sign * size.height * 0.42f * env * a * (1f - layer * 0.2f) + 4f * env * sin(u * 9f + sm.time * (1f + layer))
            if (s == 0) path.moveTo(0f, y) else path.lineTo(size.width * u, y)
        }
        drawPath(path, color.copy(alpha = 0.85f - layer * 0.25f), style = Stroke(width = 4f + (2 - layer) * 2f, cap = StrokeCap.Round))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRings(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val punch = if (idle) 0f else sm.bass
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
    val core = max * (0.42f + 0.28f * bass + 0.03f * sin(sm.time * 1.3f))
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.3f + 0.4f * glow), Color.Transparent), c, max), max * (0.85f + 0.3f * glow), c)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f)), c, core), core, c)
}
