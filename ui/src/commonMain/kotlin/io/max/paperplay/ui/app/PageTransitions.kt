package io.max.paperplay.ui.app

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.desktopPageEnterSpec
import io.max.paperplay.ui.theme.desktopPageExitSpec
import io.max.paperplay.ui.theme.desktopPaneEnterSpec
import io.max.paperplay.ui.theme.desktopPaneExitSpec

/** How the page changes: one level deeper, one level back, or a sideways switch such as another tab. */
internal enum class NavMove { Forward, Back, Switch }

internal fun navMove(fromLevel: Int, toLevel: Int) = when {
    toLevel > fromLevel -> NavMove.Forward
    toLevel < fromLevel -> NavMove.Back
    else -> NavMove.Switch
}

private fun reducedCrossfade(): ContentTransform =
    fadeIn(tween(Motion.Effects.ReducedFadeMs, easing = Motion.Standard)) togetherWith // @MotionRawAllowed
        fadeOut(tween(Motion.Effects.ReducedFadeMs, easing = Motion.Standard)) // @MotionRawAllowed

/**
 * The transition between two pages. Going deeper slides the new page in from the right over the old one, which drifts
 * left; going back reverses that with the leaving page on top; a switch fades through with a slight scale.
 * [reduced] (Settings > Reduce motion or OS animator scale 0) replaces movement with a short crossfade.
 */
internal fun pageTransition(move: NavMove, reduced: Boolean): ContentTransform {
    if (reduced) return reducedCrossfade()
    // @MotionRawAllowed
    val enterSlide = tween<IntOffset>(Motion.Spatial.PageEnterMs, easing = Motion.Emphasized)
    // @MotionRawAllowed
    val exitSlide = tween<IntOffset>(Motion.Spatial.PageExitMs, easing = Motion.Emphasized)
    // @MotionRawAllowed
    val fade = tween<Float>(Motion.Effects.FadeStandardMs, easing = Motion.Standard)
    // @MotionRawAllowed
    val quick = tween<Float>(Motion.Effects.FadeFastMs, easing = Motion.Standard)
    return when (move) {
        NavMove.Forward -> (slideInHorizontally(enterSlide) { it / 4 } + fadeIn(fade)) togetherWith
            (slideOutHorizontally(exitSlide) { -it / 8 } + fadeOut(quick))
        NavMove.Back -> ContentTransform(
            targetContentEnter = slideInHorizontally(enterSlide) { -it / 8 } + fadeIn(fade),
            initialContentExit = slideOutHorizontally(exitSlide) { it / 4 } + fadeOut(quick),
            targetContentZIndex = -1f,
        )
        NavMove.Switch -> (
            // @MotionRawAllowed
            fadeIn(tween(Motion.Spatial.TabSwitchMs, delayMillis = Motion.Spatial.TabDelayMs, easing = Motion.Standard)) +
                // @MotionRawAllowed
                scaleIn(tween(Motion.Spatial.TabSwitchMs, delayMillis = Motion.Spatial.TabDelayMs, easing = Motion.Standard), initialScale = 0.98f)
            ) togetherWith
            // @MotionRawAllowed
            fadeOut(tween(Motion.Spatial.TabDelayMs, easing = Motion.Standard))
    }
}

/**
 * Subtle desktop content-card transition. Sidebar and top bar stay fixed while only the card contents
 * crossfade with a short, density-scaled shift.
 */
internal fun desktopPageTransition(
    move: NavMove,
    reduced: Boolean,
    shiftPx: Int,
    sideShiftPx: Int = (shiftPx / 2).coerceAtLeast(1),
): ContentTransform {
    if (reduced) return reducedCrossfade()
    val enterOffsetSpec = desktopPageEnterSpec<IntOffset>(reduced = false)
    val exitOffsetSpec = desktopPageExitSpec<IntOffset>(reduced = false)
    val enterAlphaSpec = desktopPageEnterSpec<Float>(reduced = false)
    val exitAlphaSpec = desktopPageExitSpec<Float>(reduced = false)
    return when (move) {
        NavMove.Forward -> (
            slideInHorizontally(enterOffsetSpec) { shiftPx } +
                fadeIn(enterAlphaSpec)
            ) togetherWith (
            slideOutHorizontally(exitOffsetSpec) { -shiftPx / 2 } +
                fadeOut(exitAlphaSpec)
            )
        NavMove.Back -> ContentTransform(
            targetContentEnter = slideInHorizontally(enterOffsetSpec) { -shiftPx / 2 } + fadeIn(enterAlphaSpec),
            initialContentExit = slideOutHorizontally(exitOffsetSpec) { shiftPx } + fadeOut(exitAlphaSpec),
            targetContentZIndex = -1f,
        )
        NavMove.Switch -> (
            slideInVertically(enterOffsetSpec) { sideShiftPx } +
                fadeIn(enterAlphaSpec)
            ) togetherWith fadeOut(exitAlphaSpec)
    }
}

/** Smooth pane swap inside the desktop Now Playing view (Queue <-> Lyrics, or player <-> full lyrics). */
internal fun desktopPaneTransition(
    forward: Boolean,
    reduced: Boolean,
    shiftPx: Int,
): ContentTransform {
    if (reduced) return reducedCrossfade()
    val enterOffset = desktopPaneEnterSpec<IntOffset>(reduced = false)
    val exitOffset = desktopPaneExitSpec<IntOffset>(reduced = false)
    val enterAlpha = desktopPaneEnterSpec<Float>(reduced = false)
    val exitAlpha = desktopPaneExitSpec<Float>(reduced = false)
    val dir = if (forward) 1 else -1
    return (
        slideInHorizontally(enterOffset) { dir * shiftPx } +
            fadeIn(enterAlpha)
        ) togetherWith (
        slideOutHorizontally(exitOffset) { -dir * (shiftPx / 2) } +
            fadeOut(exitAlpha)
        )
}

/** What the phone's page area shows. A different key is a page change; [level] is how deep in the navigation it is. */
internal data class PageKey(val settings: Boolean, val detail: Detail?, val tab: Int, val tasks: Boolean) {
    val level get() = if (settings || detail != null || tasks) 1 else 0
}

/** What the desktop's page card shows. */
internal data class DeskKey(val now: Boolean, val settings: Boolean, val detail: Detail?, val page: String) {
    val level get() = if (now || settings || detail != null) 1 else 0
}

/** Enter transition for full-screen overlay pages launched from the player, matching [pageTransition] Forward. */
internal fun overlayPageEnter(reduced: Boolean): EnterTransition {
    // @MotionRawAllowed
    if (reduced) return fadeIn(tween(Motion.Effects.ReducedFadeMs, easing = Motion.Standard))
    // @MotionRawAllowed
    val slide = tween<IntOffset>(Motion.Spatial.PageEnterMs, easing = Motion.Emphasized)
    // @MotionRawAllowed
    val fade = tween<Float>(Motion.Effects.FadeStandardMs, easing = Motion.Standard)
    return slideInHorizontally(slide) { it / 4 } + fadeIn(fade)
}

/** Exit transition for full-screen overlay pages launched from the player, matching [pageTransition] Back. */
internal fun overlayPageExit(reduced: Boolean): ExitTransition {
    // @MotionRawAllowed
    if (reduced) return fadeOut(tween(Motion.Effects.ReducedFadeMs, easing = Motion.Standard))
    // @MotionRawAllowed
    val slide = tween<IntOffset>(Motion.Spatial.PageExitMs, easing = Motion.Emphasized)
    // @MotionRawAllowed
    val quick = tween<Float>(Motion.Effects.FadeFastMs, easing = Motion.Standard)
    return slideOutHorizontally(slide) { it / 4 } + fadeOut(quick)
}

/** Expand transition for collapsible sections and inline panels. */
internal fun expandSectionEnter(reduced: Boolean): EnterTransition =
    if (reduced) EnterTransition.None
    else {
        // @MotionRawAllowed
        expandVertically(tween<IntSize>(Motion.Spatial.ExpandMs, easing = Motion.Emphasized)) +
            // @MotionRawAllowed
            fadeIn(tween<Float>(Motion.Effects.FadeStandardMs, easing = Motion.Standard))
    }

/** Collapse transition for collapsible sections and inline panels. */
internal fun expandSectionExit(reduced: Boolean): ExitTransition =
    if (reduced) ExitTransition.None
    else {
        // @MotionRawAllowed
        shrinkVertically(tween<IntSize>(Motion.Spatial.ExpandMs, easing = Motion.Emphasized)) +
            // @MotionRawAllowed
            fadeOut(tween<Float>(Motion.Effects.FadeFastMs, easing = Motion.Standard))
    }

/** Short fade-in/out for overlays, floating banners, and shortcut dialogs. */
internal fun overlayFadeEnter(reduced: Boolean): EnterTransition =
    // @MotionRawAllowed
    if (reduced) fadeIn(tween(Motion.Effects.ReducedFadeMs))
    // @MotionRawAllowed
    else fadeIn(tween(Motion.Effects.FadeStandardMs, easing = Motion.Standard))

internal fun overlayFadeExit(reduced: Boolean): ExitTransition =
    // @MotionRawAllowed
    if (reduced) fadeOut(tween(Motion.Effects.ReducedFadeMs))
    // @MotionRawAllowed
    else fadeOut(tween(Motion.Effects.FadeFastMs, easing = Motion.Standard))

internal data class PageBackTransform(
    val translationX: Float,
    val scale: Float,
    val alpha: Float,
)

/** Layer-phase transform for predictive back gestures on non-player pages. */
internal fun pageBackTransform(progress: Float, widthPx: Float, reduceMotion: Boolean): PageBackTransform {
    val p = progress.coerceIn(0f, 1f)
    if (reduceMotion) {
        return PageBackTransform(
            translationX = 0f,
            scale = 1f,
            alpha = 1f - 0.35f * p,
        )
    }
    return PageBackTransform(
        translationX = widthPx * 0.14f * p,
        scale = 1f - 0.05f * p,
        alpha = 1f - 0.18f * p,
    )
}
