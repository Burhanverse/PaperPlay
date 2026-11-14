package io.max.paperplay.ui.app

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
    val n = sm.n
    val slots = n * 2
    val slot = size.width / slots
    for (j in 0 until slots) {
        val a = if (idle) 0.05f else sm.v[if (j < n) n - 1 - j else j - n]
        val h = size.height * a.coerceIn(0.03f, 1f)
        val x = slot * (j + 0.5f)
        drawLine(color.copy(alpha = 0.3f + 0.6f * a), Offset(x, size.height), Offset(x, size.height - h), slot * 0.6f, StrokeCap.Round)
    }
}

internal fun DrawScope.drawPeaks(sm: Smoother, idle: Boolean, color: Color) {
    val n = sm.n
    val slot = size.width / n
    val cell = size.height / 24f
    val box = { w: Float -> Size(w, cell - 2f) }
    for (i in 0 until n) {
        val a = if (idle) 0.04f else sm.v[i]
        val x = slot * i + slot * 0.15f
        val w = slot * 0.7f
        for (c in 0 until (a * 24).toInt()) drawRect(color.copy(alpha = 0.35f + 0.5f * c / 24f), Offset(x, size.height - (c + 1) * cell + 1f), box(w))
        val cap = (sm.peak[i] * 24).toInt().coerceIn(0, 23)
        if (!idle) drawRect(color, Offset(x, size.height - (cap + 1) * cell + 1f), box(w))
    }
}

internal fun DrawScope.drawCircleBars(sm: Smoother, idle: Boolean, color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    val max = min(size.width, size.height) / 2
    val spokes = sm.n * 2
    val r0 = max * (0.3f + 0.05f * (if (idle) 0f else sm.bass))
    for (k in 0 until spokes) {
        val a = if (idle) 0.04f else sm.v[if (k < sm.n) k else spokes - 1 - k]
        val ang = (2 * PI * k / spokes + sm.time * 0.15).toFloat()
        val d = Offset(cos(ang), sin(ang))
        drawLine(color.copy(alpha = 0.3f + 0.6f * a), c + d * r0, c + d * (r0 + max * 0.65f * a.coerceAtLeast(0.03f)), 5f, StrokeCap.Round)
    }
    drawCircle(color.copy(alpha = 0.4f), r0 * 0.9f, c, style = Stroke(2f))
}

internal fun DrawScope.drawParticles(sm: Smoother, idle: Boolean, color: Color) {
    if (idle) return
    // A faint floor line shows where the sparks come from; each spark fades as it rises.
    drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
    for (i in sm.px.indices) {
        val life = sm.pl[i]
        if (life <= 0f) continue
        val f = (life / 1.2f).coerceIn(0f, 1f)
        drawCircle(color.copy(alpha = f * 0.9f), sm.ps[i] * (0.5f + 0.5f * f), Offset(sm.px[i] * size.width, sm.py[i] * size.height))
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
    for (r in 0 until g.rows) {
        val sb = StringBuilder(g.cols)
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
    val tick = (sm.time * 6f).toInt()
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
    val energy = if (idle) 0.1f else sm.energy
    for (r in 0 until g.rows) {
        val sb = StringBuilder(g.cols)
        val y = (r + 0.5f) / g.rows * 2f - 1f
        for (c in 0 until g.cols) {
            // Aspect corrected so the shape is round.
            val x = ((c + 0.5f) / g.cols * 2f - 1f) * (g.cols * g.cw) / size.height
            val d = sqrt(x * x + y * y)
            val v = 1f - abs(d - (0.35f + 0.35f * bass)) * 3f + 0.35f * sin(d * 14f - sm.time * 3f) * energy
            sb.append(SHADES[(v.coerceIn(0f, 0.999f) * SHADES.length).toInt()])
        }
        drawText(tm.measure(sb.toString(), style), color.copy(alpha = 0.85f), Offset(left, r * lineH))
    }
}
