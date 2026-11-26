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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
    // Louder plays bigger; at volume 0 nothing moves.
    sm.gain = volume.coerceIn(0f, 1f)
    val diag = remember(style) { StandbyTelemetry(style) }
    androidx.compose.runtime.DisposableEffect(diag) {
        onDispose { diag.flushIfNeeded() }
    }
    LaunchedEffect(running, seed, style) {
        if (!running) { sm.settle(); diag.reset(); frame++; return@LaunchedEffect }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                val rawDtMs = if (last == 0L) 0f else (now - last) / 1_000_000f
                last = now
                sm.update(dt, now, style)
                if (AudioTap.telemetryEnabled) {
                    diag.record(now, rawDtMs, sm.lastTapFrame, sm.lastBeatHit)
                }
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
        StandbySmoothingProfile(attackSec = 0.018f, releaseSec = 0.155f)
    else ->
        StandbySmoothingProfile(attackSec = 0.011f, releaseSec = 0.105f)
}

/**
 * Display-rate asymmetric smoother reading playout-interpolated [AudioTap.TapFrame]
 * (multi-res spectrum, time-domain 140 Hz bass envelope, and fixed-hop onset).
 * Zero per-frame allocations.
 */
internal class Smoother {
    val n = AudioTap.BANDS
    val displayBands = 64
    val v = FloatArray(n)
    val v64 = FloatArray(displayBands)
    val peak64 = FloatArray(displayBands)
    val peakVel64 = FloatArray(displayBands)
    val peakHold64 = FloatArray(displayBands)
    internal val lastTapFrame = AudioTap.TapFrame(FloatArray(n))
    private val peakHold = FloatArray(n)
    private var lastBeatSeq = 0
    internal var lastBeatHit = false
        private set
    var bass = 0f
    var bassPunch = 0f
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
    var waveFillPath: Path? = null
    val waveYs = FloatArray(65)
    val orbOuterColors = ArrayList<Color>(2).apply { add(Color.Transparent); add(Color.Transparent) }
    val orbInnerColors = ArrayList<Color>(2).apply { add(Color.Transparent); add(Color.Transparent) }
    val orbRimColors = ArrayList<Color>(2).apply { add(Color.Transparent); add(Color.Transparent) }
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
        v.fill(0f); v64.fill(0f)
        peak.fill(0f); peakHold.fill(0f)
        peak64.fill(0f); peakVel64.fill(0f); peakHold64.fill(0f)
        pl.fill(0f)
        bass = 0f; bassPunch = 0f; energy = 0f; onset = 0f; liveSignal = false
        lastBeatSeq = 0
    }

    /** The playback volume, 0 to 1: the analysis evens out loudness, so this is what makes a quiet setting look quiet. */
    @Volatile var gain = 1f

    fun update(dt: Float, nowNs: Long = System.nanoTime(), style: StandbyStyle = StandbyStyle.Bars) {
        val safeDt = dt.coerceIn(0.001f, 0.1f)
        time += safeDt
        val live = AudioTap.sampleFrame(lastTapFrame, nowNs)
        var beatHit = false
        if (live) {
            onset = lastTapFrame.onset * gain
            if (lastTapFrame.beatSeq != lastBeatSeq) {
                beatHit = true
                lastBeatSeq = lastTapFrame.beatSeq
            }
        } else {
            onset = 0f
            lastBeatSeq = 0
        }
        lastBeatHit = beatHit

        val profile = smoothingProfileFor(style)
        val atkAlpha = 1f - exp(-safeDt / profile.attackSec)
        val relAlpha = 1f - exp(-safeDt / profile.releaseSec)

        var sum = 0f
        for (i in 0 until n) {
            val target = if (live) lastTapFrame.bands[i] * gain else 0f
            val alpha = if (target > v[i]) atkAlpha else relAlpha
            v[i] += (target - v[i]) * alpha
            if (v[i] < 0.0005f) v[i] = 0f
            sum += v[i]
        }

        val bassTarget = if (live) lastTapFrame.bass * gain else 0f
        val bassTau = if (bassTarget > bass) 0.010f else 0.085f
        bass += (bassTarget - bass) * (1f - exp(-safeDt / bassTau))
        if (bass < 0.0005f) bass = 0f

        energy = sum / n
        liveSignal = live && (energy > 0.002f || bass > 0.004f)

        if (liveSignal && beatHit) {
            bassPunch = maxOf(bassPunch, (0.35f + 0.65f * onset).coerceIn(0f, 1f) * gain)
        }
        bassPunch *= exp(-safeDt / 0.065f)
        if (bassPunch < 0.0005f) bassPunch = 0f

        // Upsample 32 bands -> 64 display bands via clamped Catmull-Rom spline (zero allocations).
        if (!liveSignal && energy == 0f) {
            v64.fill(0f)
        } else {
            val maxIdx = n - 1
            for (j in 0 until displayBands) {
                val pos = j * maxIdx.toFloat() / (displayBands - 1)
                val i1 = pos.toInt().coerceIn(0, maxIdx)
                val t = pos - i1
                val i0 = (i1 - 1).coerceAtLeast(0)
                val i2 = (i1 + 1).coerceAtMost(maxIdx)
                val i3 = (i1 + 2).coerceAtMost(maxIdx)
                val p0 = v[i0]; val p1 = v[i1]; val p2 = v[i2]; val p3 = v[i3]
                val t2 = t * t
                val t3 = t2 * t
                val catmull = 0.5f * (
                    (2f * p1) +
                        (-p0 + p2) * t +
                        (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                        (-p0 + 3f * p1 - 3f * p2 + p3) * t3
                    )
                val lo = minOf(p1, p2) * 0.9f
                val hi = maxOf(p1, p2) * 1.1f
                v64[j] = catmull.coerceIn(lo, hi).coerceIn(0f, 1f)
            }
        }

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
        for (i in 0 until displayBands) {
            if (v64[i] >= peak64[i]) {
                peak64[i] = v64[i]
                peakHold64[i] = 0.10f
                peakVel64[i] = 0f
            } else if (peakHold64[i] > 0f) {
                peakHold64[i] = (peakHold64[i] - safeDt).coerceAtLeast(0f)
            } else {
                peakVel64[i] += 2.6f * safeDt
                peak64[i] = (peak64[i] - peakVel64[i] * safeDt).coerceAtLeast(v64[i])
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
    val bars = 48
    val slot = size.width / bars
    val w = slot * 0.56f
    val r = CornerRadius(w * 0.5f, w * 0.5f)
    val glowW = slot * 0.90f
    val glowR = CornerRadius(glowW * 0.5f, glowW * 0.5f)
    val midY = size.height * 0.5f
    for (i in 0 until bars) {
        val idx = (i * (sm.displayBands - 1)) / (bars - 1)
        val a = if (idle) 0.04f else sm.v64[idx]
        val half = midY * a.coerceIn(0.025f, 0.94f)
        val left = slot * (i + 0.5f) - w * 0.5f
        if (!idle && a > 0.08f) {
            val gHalf = (half + w * 0.35f).coerceAtMost(midY)
            drawRoundRect(
                color = color.copy(alpha = 0.13f * a),
                topLeft = Offset(slot * (i + 0.5f) - glowW * 0.5f, midY - gHalf),
                size = Size(glowW, gHalf * 2f),
                cornerRadius = glowR,
            )
        }
        drawRoundRect(
            color = color.copy(alpha = if (idle) 0.28f else 0.34f + 0.60f * a),
            topLeft = Offset(left, midY - half),
            size = Size(w, half * 2f),
            cornerRadius = r,
        )
        if (!idle && a > 0.25f) {
            val innerW = w * 0.44f
            val innerHalf = half * 0.72f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f * a),
                topLeft = Offset(slot * (i + 0.5f) - innerW * 0.5f, midY - innerHalf),
                size = Size(innerW, innerHalf * 2f),
                cornerRadius = CornerRadius(innerW * 0.5f, innerW * 0.5f),
            )
        }
        if (!idle) {
            val pk = sm.peak64[idx]
            if (pk > a + 0.03f && pk > 0.06f) {
                val pkHalf = midY * pk.coerceIn(0.03f, 0.96f)
                val capH = (w * 0.55f).coerceAtLeast(3f)
                val capColor = color.copy(alpha = 0.85f)
                drawRoundRect(capColor, Offset(left, midY - pkHalf - capH * 0.5f), Size(w, capH), r)
                drawRoundRect(capColor, Offset(left, midY + pkHalf - capH * 0.5f), Size(w, capH), r)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWave(sm: Smoother, idle: Boolean, color: Color) {
    val mid = size.height * 0.5f
    val steps = 64
    val dx = size.width / steps
    val ys = sm.waveYs
    val phaseBase = sm.motionPhase
    val punch = if (idle) 0f else sm.bassPunch
    for (layer in 0 until 3) {
        val path = (sm.wavePaths[layer] ?: Path().also { sm.wavePaths[layer] = it }).apply { rewind() }
        val lo = layer * sm.displayBands / 3
        val span = sm.displayBands / 3 - 1
        val sign = if (layer == 1) -1f else 1f
        val freq = when (layer) { 0 -> 2.0f; 1 -> 3.5f; else -> 5.0f }
        val layerScale = (1f - layer * 0.18f) * (1f + if (layer == 0) 0.16f * punch else 0f)
        for (s in 0..steps) {
            val u = s.toFloat() / steps
            val env = sin(u * PI.toFloat())
            val fIdx = lo + u * span
            val i0 = fIdx.toInt().coerceIn(0, sm.displayBands - 1)
            val i1 = (i0 + 1).coerceAtMost(sm.displayBands - 1)
            val frac = fIdx - i0
            val a = if (idle) 0.025f else (sm.v64[i0] * (1f - frac) + sm.v64[i1] * frac)
            val carrier = sin(u * PI.toFloat() * freq + phaseBase * (1.2f + layer * 0.7f) + layer * 1.1f)
            val shimmer = if (idle) 0f else 0.20f * sin(u * PI.toFloat() * (freq * 2.1f) - phaseBase * 2.3f)
            ys[s] = mid + sign * size.height * 0.38f * env * a * layerScale * (0.68f * carrier + 0.32f + shimmer)
        }
        path.moveTo(0f, ys[0])
        for (s in 0 until steps) {
            val y0 = ys[(s - 1).coerceAtLeast(0)]
            val y1 = ys[s]
            val y2 = ys[s + 1]
            val y3 = ys[(s + 2).coerceAtMost(steps)]
            val x1 = s * dx
            val x2 = (s + 1) * dx
            val c1x = x1 + dx / 3f
            val c1y = y1 + (y2 - y0) / 6f
            val c2x = x2 - dx / 3f
            val c2y = y2 - (y3 - y1) / 6f
            path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
        }
        if (layer == 0 && !idle) {
            val fill = (sm.waveFillPath ?: Path().also { sm.waveFillPath = it }).apply { rewind() }
            fill.addPath(path)
            fill.lineTo(size.width, mid)
            fill.lineTo(0f, mid)
            fill.close()
            drawPath(fill, color.copy(alpha = 0.08f + 0.08f * sm.energy))
        }
        if (!idle) {
            drawPath(
                path,
                color.copy(alpha = 0.18f - layer * 0.04f),
                style = Stroke(width = 11f + (2 - layer) * 2.5f, cap = StrokeCap.Round),
            )
        }
        drawPath(
            path,
            color.copy(alpha = if (idle) 0.35f - layer * 0.08f else 0.90f - layer * 0.22f),
            style = Stroke(width = 2.6f + (2 - layer) * 1.0f, cap = StrokeCap.Round),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRings(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val punch = if (idle) 0f else (0.65f * sm.bass + 0.45f * sm.onset + 0.35f * sm.bassPunch).coerceIn(0f, 1.3f)
    val rings = 5
    for (i in 0 until rings) {
        val p = if (idle) (i + 1).toFloat() / (rings + 1) else (sm.ring + i.toFloat() / rings) % 1f
        val radius = max * (0.20f + 0.75f * p) * (1f + 0.075f * punch)
        val alpha = (1f - p) * (0.28f + 0.62f * (if (idle) 0.25f else sm.energy.coerceAtLeast(0.28f)))
        if (!idle) {
            drawCircle(color.copy(alpha = alpha * 0.28f), radius, c, style = Stroke(width = 10f + 6f * (1f - p)))
        }
        drawCircle(color.copy(alpha = alpha), radius, c, style = Stroke(width = 2.5f + 4.5f * (1f - p) + 3.5f * punch))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val bass = if (idle) 0.04f else (sm.bass + 0.25f * sm.bassPunch).coerceIn(0f, 1.2f)
    val glow = if (idle) 0.2f else sm.energy
    val hit = if (idle) 0f else sm.onset
    val core = max * (0.40f + 0.26f * bass + 0.09f * hit)
    sm.orbOuterColors[0] = color.copy(alpha = 0.32f + 0.44f * glow)
    sm.orbOuterColors[1] = Color.Transparent
    sm.orbInnerColors[0] = color.copy(alpha = 0.96f)
    sm.orbInnerColors[1] = color.copy(alpha = 0.32f)
    sm.orbRimColors[0] = Color.White.copy(alpha = if (idle) 0.08f else 0.18f + 0.25f * bass)
    sm.orbRimColors[1] = Color.Transparent
    drawCircle(Brush.radialGradient(sm.orbOuterColors, c, max), max * (0.85f + 0.3f * glow), c)
    drawCircle(Brush.radialGradient(sm.orbInnerColors, c, core), core, c)
    if (!idle) {
        drawCircle(color.copy(alpha = 0.35f + 0.35f * hit), core * 1.06f, c, style = Stroke(width = 2.5f))
    }
}

/** Flag-gated 10s window telemetry recorder for live device verification. Zero allocations per frame. */
private class StandbyTelemetry(private val style: StandbyStyle) {
    private val cap = 2048
    private val dtArr = FloatArray(cap)
    private val latArr = FloatArray(cap)
    private val pushArr = FloatArray(cap)
    private val scratch = FloatArray(cap)
    private var count = 0
    private var latCount = 0
    private var winStartNs = 0L
    private var prevHopSeq = -1L
    private var prevAlpha = -1f
    private var distinctHops = 0
    private var sameDataDraws = 0
    private var interpDraws = 0
    private var clampedDraws = 0
    private var beatHits = 0
    private var skippedHops = 0

    fun reset() {
        count = 0
        latCount = 0
        winStartNs = 0L
        prevHopSeq = -1L
        prevAlpha = -1f
        distinctHops = 0
        sameDataDraws = 0
        interpDraws = 0
        clampedDraws = 0
        beatHits = 0
        skippedHops = 0
    }

    fun record(nowNs: Long, dtMs: Float, frame: AudioTap.TapFrame, beatHit: Boolean) {
        if (winStartNs == 0L) winStartNs = nowNs
        if (dtMs > 0f && count < cap) dtArr[count++] = dtMs
        if (frame.hopSeq > 0L) {
            if (frame.hopSeq != prevHopSeq) {
                if (prevHopSeq > 0L && frame.hopSeq > prevHopSeq + 1L) {
                    skippedHops += (frame.hopSeq - prevHopSeq - 1L).toInt()
                }
                distinctHops++
            } else if (!frame.interpolated || kotlin.math.abs(frame.interpAlpha - prevAlpha) < 0.001f) {
                sameDataDraws++
            }
            prevHopSeq = frame.hopSeq
            prevAlpha = frame.interpAlpha
            if (frame.interpolated) interpDraws++ else clampedDraws++
            if (frame.framePlayoutNs > 0L && latCount < cap) {
                latArr[latCount] = (nowNs - frame.framePlayoutNs) / 1_000_000f
                pushArr[latCount] = if (frame.framePushWallNs > 0L) (nowNs - frame.framePushWallNs) / 1_000_000f else 0f
                latCount++
            }
        }
        if (beatHit) beatHits++
        if (nowNs - winStartNs >= 10_000_000_000L) {
            emit((nowNs - winStartNs) / 1e9f)
            reset()
        }
    }

    fun flushIfNeeded() {
        if (count >= 60 && winStartNs > 0L) {
            emit(((System.nanoTime() - winStartNs) / 1e9f).coerceAtLeast(1f))
            reset()
        }
    }

    private fun emit(sec: Float) {
        val dtP50 = pct(dtArr, count, 0.50f)
        val dtP95 = pct(dtArr, count, 0.95f)
        val dtMax = pct(dtArr, count, 1.0f)
        val latP50 = pct(latArr, latCount, 0.50f)
        val latP95 = pct(latArr, latCount, 0.95f)
        val latP05 = pct(latArr, latCount, 0.05f)
        val pushP50 = pct(pushArr, latCount, 0.50f)
        val msg = "style=${style.name} sec=${"%.1f".format(sec)} drawFps=${"%.1f".format(count / sec)} " +
            "hopsPerSec=${"%.1f".format(distinctHops / sec)} skippedHops=$skippedHops sameDraws=$sameDataDraws " +
            "interp=$interpDraws clamped=$clampedDraws dtP50=${"%.1f".format(dtP50)}ms dtP95=${"%.1f".format(dtP95)}ms " +
            "dtMax=${"%.1f".format(dtMax)}ms latP50=${"%.1f".format(latP50)}ms latP95=${"%.1f".format(latP95)}ms " +
            "jitter=${"%.1f".format(latP95 - latP05)}ms pushToDrawP50=${"%.1f".format(pushP50)}ms " +
            "cpuUs=${AudioTap.analysisMicrosPerHop} beats=$beatHits"
        io.max.paperplay.player.PlayLog.log("standby-diag", msg)
        System.err.println("StandbyDiag: $msg")
    }

    private fun pct(arr: FloatArray, n: Int, p: Float): Float {
        if (n <= 0) return 0f
        arr.copyInto(scratch, 0, 0, n)
        scratch.sort(0, n)
        return scratch[((n - 1) * p).toInt().coerceIn(0, n - 1)]
    }
}
