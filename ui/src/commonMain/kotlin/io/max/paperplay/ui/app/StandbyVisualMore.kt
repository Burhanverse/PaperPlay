package io.max.paperplay.ui.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The second batch of standby styles: spectrum variants, particles and text-art ones. Same inputs as the first batch. */

internal fun DrawScope.drawMirror(sm: Smoother, idle: Boolean, color: Color) {
    val halfSlots = 32
    val slots = halfSlots * 2
    val slot = size.width / slots
    val w = slot * 0.62f
    val r = CornerRadius(w * 0.5f, w * 0.5f)
    for (j in 0 until slots) {
        val bandPos = if (j < halfSlots) halfSlots - 1 - j else j - halfSlots
        val idx = (bandPos * (sm.displayBands - 1)) / (halfSlots - 1)
        val a = if (idle) 0.04f else sm.v64[idx]
        val h = size.height * a.coerceIn(0.025f, 0.95f)
        val left = slot * (j + 0.5f) - w * 0.5f
        if (!idle && a > 0.08f) {
            val gw = slot * 0.92f
            drawRoundRect(
                color = color.copy(alpha = 0.13f * a),
                topLeft = Offset(slot * (j + 0.5f) - gw * 0.5f, size.height - h - w * 0.25f),
                size = Size(gw, (h + w * 0.25f).coerceAtMost(size.height)),
                cornerRadius = CornerRadius(gw * 0.5f, gw * 0.5f),
            )
        }
        drawRoundRect(
            color = color.copy(alpha = if (idle) 0.28f else 0.34f + 0.60f * a),
            topLeft = Offset(left, size.height - h),
            size = Size(w, h),
            cornerRadius = r,
        )
        if (!idle) {
            val pk = sm.peak64[idx]
            if (pk > a + 0.03f && pk > 0.06f) {
                val pkY = size.height * (1f - pk.coerceIn(0.03f, 0.97f))
                val capH = (w * 0.5f).coerceAtLeast(3f)
                drawRoundRect(color.copy(alpha = 0.85f), Offset(left, pkY), Size(w, capH), r)
            }
        }
    }
}

internal fun DrawScope.drawPeaks(sm: Smoother, idle: Boolean, color: Color) {
    val cols = 48
    val rows = 28
    val slot = size.width / cols
    val cell = size.height / rows.toFloat()
    val w = slot * 0.68f
    val h = (cell - 2f).coerceAtLeast(1.5f)
    val boxSize = Size(w, h)
    val r = CornerRadius(h * 0.35f, h * 0.35f)
    for (i in 0 until cols) {
        val idx = (i * (sm.displayBands - 1)) / (cols - 1)
        val a = if (idle) 0.04f else sm.v64[idx]
        val x = slot * i + (slot - w) * 0.5f
        val lit = (a * rows).toInt().coerceIn(0, rows)
        for (c in 0 until lit) {
            drawRoundRect(
                color = color.copy(alpha = 0.34f + 0.58f * c / rows),
                topLeft = Offset(x, size.height - (c + 1) * cell + 1f),
                size = boxSize,
                cornerRadius = r,
            )
        }
        if (!idle) {
            val cap = (sm.peak64[idx] * rows).toInt().coerceIn(0, rows - 1)
            if (cap > 0) {
                drawRoundRect(color.copy(alpha = 0.95f), Offset(x, size.height - (cap + 1) * cell + 1f), boxSize, r)
            }
        }
    }
}

internal fun DrawScope.drawCircleBars(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val spokes = 64
    val halfSpokes = spokes / 2
    val r0 = max * (0.29f + 0.05f * (if (idle) 0f else sm.bass) + 0.03f * (if (idle) 0f else sm.onset + 0.5f * sm.bassPunch))
    val barW = (2f * PI.toFloat() * r0 / spokes * 0.55f).coerceIn(3f, 8f)
    for (k in 0 until spokes) {
        val mirrorIdx = if (k < halfSpokes) k else spokes - 1 - k
        val idx = (mirrorIdx * (sm.displayBands - 1)) / (halfSpokes - 1)
        val a = if (idle) 0.04f else sm.v64[idx]
        val ang = (2 * PI * k / spokes + sm.motionPhase * 0.15).toFloat()
        val d = Offset(cos(ang), sin(ang))
        val len = max * 0.64f * a.coerceAtLeast(0.03f)
        if (!idle && a > 0.10f) {
            drawLine(color.copy(alpha = 0.15f * a), c + d * r0, c + d * (r0 + len), barW * 1.65f, StrokeCap.Round)
        }
        drawLine(color.copy(alpha = if (idle) 0.28f else 0.34f + 0.60f * a), c + d * r0, c + d * (r0 + len), barW, StrokeCap.Round)
        if (!idle) {
            val pk = sm.peak64[idx]
            if (pk > a + 0.04f && pk > 0.07f) {
                val pkR = r0 + max * 0.64f * pk.coerceIn(0.03f, 1f)
                drawCircle(color.copy(alpha = 0.85f), barW * 0.55f, c + d * pkR)
            }
        }
    }
    if (!idle) drawCircle(color.copy(alpha = 0.18f), r0 * 0.90f, c, style = Stroke(8f))
    drawCircle(color.copy(alpha = 0.48f), r0 * 0.90f, c, style = Stroke(2.2f))
}

internal fun DrawScope.drawParticles(sm: Smoother, idle: Boolean, color: Color) {
    if (idle) return
    // A faint floor line shows where the sparks come from; each spark fades as it rises.
    drawLine(color.copy(alpha = 0.18f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2.5f)
    for (i in sm.px.indices) {
        val life = sm.pl[i]
        if (life <= 0f) continue
        val f = (life / 1.2f).coerceIn(0f, 1f)
        val center = Offset(sm.px[i] * size.width, sm.py[i] * size.height)
        val radius = sm.ps[i] * (0.5f + 0.5f * f)
        drawCircle(color.copy(alpha = f * 0.22f), radius * 2.1f, center)
        drawCircle(color.copy(alpha = f * 0.92f), radius, center)
    }
}

private val mono = TextStyle(fontFamily = FontFamily.Monospace)

private class Grid(val fontPx: Float, val cols: Int, val rows: Int, val cw: Float)

private fun DrawScope.grid(rows: Int, maxCols: Int = 64): Grid {
    val px = size.height / rows / 1.15f
    val cw = px * 0.6f
    return Grid(px, (size.width / cw).toInt().coerceIn(8, maxCols), rows, cw)
}

internal fun DrawScope.drawAsciiBars(sm: Smoother, idle: Boolean, color: Color, tm: TextMeasurer) {
    val g = grid(14)
    val style = mono.copy(fontSize = g.fontPx.toSp())
    val lineH = size.height / g.rows
    val left = (size.width - g.cols * g.cw) / 2
    val sb = sm.asciiSb
    for (r in 0 until g.rows) {
        sb.setLength(0)
        val from = g.rows - 1 - r
        for (c in 0 until g.cols) {
            val level = ((if (idle) 0.07f else sm.v[c * sm.n / g.cols]) * g.rows).toInt()
            sb.append(if (from == level - 1 && level > 0) ':' else if (from < level) '#' else if (from == 0) '.' else ' ')
        }
        drawText(tm.measure(sb.toString(), style), color.copy(alpha = 0.45f + 0.5f * r / g.rows), Offset(left, r * lineH))
    }
}

private const val RAIN = "01ABCDEFXYZ<>/|+=*#@"

internal fun DrawScope.drawMatrix(sm: Smoother, idle: Boolean, color: Color, tm: TextMeasurer) {
    val g = grid(14, sm.drop.size)
    val style = mono.copy(fontSize = g.fontPx.toSp())
    val lineH = size.height / g.rows
    val left = (size.width - g.cols * g.cw) / 2
    val tick = if (idle) 0 else (sm.motionPhase * 6f).toInt()
    for (c in 0 until g.cols) {
        val head = sm.drop[c] * (g.rows + 6)
        val strength = if (idle) 0.1f else 0.35f + 0.65f * sm.v[c * sm.n / g.cols]
        for (r in 0 until g.rows) {
            val d = head - r
            if (d < 0f || d > 6f) continue
            val ch = RAIN[(c * 31 + r * 17 + (if (d < 1f) tick else 0)).mod(RAIN.length)]
            val col = if (d < 1f) Color.White.copy(alpha = strength) else color.copy(alpha = (1f - d / 6f) * strength)
            drawText(tm.measure(ch.toString(), style), col, Offset(left + c * g.cw, r * lineH))
        }
    }
}

private const val SHADES = " .:-=+*#%@"

internal fun DrawScope.drawAsciiPulse(sm: Smoother, idle: Boolean, color: Color, tm: TextMeasurer) {
    val g = grid(16)
    val style = mono.copy(fontSize = g.fontPx.toSp())
    val lineH = size.height / g.rows
    val left = (size.width - g.cols * g.cw) / 2
    val bass = if (idle) 0.03f else sm.bass
    val energy = if (idle) 0f else sm.energy
    val sb = sm.asciiSb
    for (r in 0 until g.rows) {
        sb.setLength(0)
        val y = (r + 0.5f) / g.rows * 2f - 1f
        for (c in 0 until g.cols) {
            // Aspect corrected so the shape is round.
            val x = ((c + 0.5f) / g.cols * 2f - 1f) * (g.cols * g.cw) / size.height
            val d = sqrt(x * x + y * y)
            val v = 1f - abs(d - (0.35f + 0.35f * bass)) * 3f + 0.35f * sin(d * 14f - sm.motionPhase * 3f) * energy
            sb.append(SHADES[(v.coerceIn(0f, 0.999f) * SHADES.length).toInt()])
        }
        drawText(tm.measure(sb.toString(), style), color.copy(alpha = 0.85f), Offset(left, r * lineH))
    }
}
