package io.max.paperplay.ui.components

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuroraSeekTest {
    @Test
    fun longPressWithoutLabelClearsDraggingAfterCleanup() = runBlocking {
        val state = AuroraSeekInteractionState()
        state.onLongPress(fraction = 0.62f, label = null)

        assertEquals(0.62f, state.dragging)
        assertNull(state.longPressText)
        assertTrue(state.longPressToken > 0, "cleanup effect must still be scheduled when label is null")

        state.runLongPressCleanup(timeoutMs = 1L)

        assertNull(state.dragging, "temporary thumb position must clear even when no label is provided")
        assertNull(state.longPressText)
        assertEquals(0, state.longPressToken)
    }

    @Test
    fun longPressWithLabelClearsLabelAndDraggingAfterCleanup() = runBlocking {
        val state = AuroraSeekInteractionState()
        state.onLongPress(fraction = 0.4f, label = "1:20")

        assertEquals(0.4f, state.dragging)
        assertEquals("1:20", state.longPressText)

        state.runLongPressCleanup(timeoutMs = 1L)

        assertNull(state.dragging)
        assertNull(state.longPressText)
        assertEquals(0, state.longPressToken)
    }

    @Test
    fun dragStartCancelsPendingLongPressCleanup() = runBlocking {
        val state = AuroraSeekInteractionState()
        state.onLongPress(fraction = 0.3f, label = null)
        state.onDragStart(fraction = 0.5f)

        assertEquals(0, state.longPressToken)
        state.runLongPressCleanup(timeoutMs = 1L)
        assertEquals(0.5f, state.dragging)

        state.onDrag(0.75f)
        assertEquals(0.75f, state.onDragEnd())
        assertNull(state.dragging)
    }

    @Test
    fun waveEnvelopeIsSmoothSymmetricAndZeroAtEdges() {
        assertEquals(0f, auroraWaveEnvelope(0f), 1e-6f)
        assertEquals(0f, auroraWaveEnvelope(1f), 1e-6f)
        assertEquals(1f, auroraWaveEnvelope(0.5f), 1e-6f)

        val steps = 200
        val du = 1f / steps
        val values = FloatArray(steps + 1) { auroraWaveEnvelope(it * du) }
        for (i in 0..steps) {
            assertEquals(values[i], values[steps - i], 1e-5f, "envelope must be symmetric")
        }
        // Check bounded 2nd-derivative jump (no piecewise curvature kink like smoothstep(0, 0.14, u) had).
        var maxCurvatureJump = 0f
        for (i in 2 until steps - 1) {
            val kPrev = (values[i] - 2f * values[i - 1] + values[i - 2]) / (du * du)
            val kCurr = (values[i + 1] - 2f * values[i] + values[i - 1]) / (du * du)
            maxCurvatureJump = maxOf(maxCurvatureJump, kotlin.math.abs(kCurr - kPrev))
        }
        assertTrue(maxCurvatureJump < 2.0f, "curvature jump too sharp: $maxCurvatureJump")
    }
}
