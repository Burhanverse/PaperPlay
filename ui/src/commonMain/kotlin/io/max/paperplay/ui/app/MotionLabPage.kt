package io.max.paperplay.ui.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.PreviewFrame
import io.max.paperplay.ui.isPhone
import io.max.paperplay.ui.theme.DesktopFrameMonitor
import io.max.paperplay.ui.theme.DesktopFrameOverlay
import io.max.paperplay.ui.theme.LocalMotionTuning
import io.max.paperplay.ui.theme.LocalReduceMotion
import io.max.paperplay.ui.theme.Motion
import io.max.paperplay.ui.theme.MotionTuningState
import io.max.paperplay.ui.theme.animatedWidth
import io.max.paperplay.ui.theme.motionCrossfadeSpec
import io.max.paperplay.ui.theme.motionSpatialSpring
import io.max.paperplay.ui.theme.pressScale
import io.max.paperplay.ui.theme.rememberAmbientClock
import kotlinx.coroutines.isActive
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlin.math.roundToInt

/**
 * Developer-only Motion Lab for live-tuning [MotionTuning] tokens and inspecting every motion
 * pattern alongside a live frame-time readout.
 */
@Composable
internal fun MotionLabPage(modifier: Modifier = Modifier) {
    val tuning = LocalMotionTuning.current
    val reduceMotion = LocalReduceMotion.current
    val clock = rememberAmbientClock(active = true, fps = tuning.ambientFps)
    clock.subscribe(active = true)

    var lastFrameMs by remember { mutableFloatStateOf(0f) }
    var maxFrameMs by remember { mutableFloatStateOf(0f) }
    var over16Count by remember { mutableIntStateOf(0) }
    var over20Count by remember { mutableIntStateOf(0) }
    var frameCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var prevNs = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (prevNs != 0L) {
                    val dtMs = (now - prevNs) / 1_000_000f
                    lastFrameMs = dtMs
                    if (dtMs > maxFrameMs) maxFrameMs = dtMs
                    if (dtMs > 16.67f) over16Count++
                    if (dtMs > 20f) over20Count++
                    frameCount++
                }
                prevNs = now
            }
        }
    }

    var pageStep by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var switchOn by remember { mutableStateOf(false) }
    var crossfadeIdx by remember { mutableIntStateOf(0) }
    val items = remember { mutableStateListOf("Track A", "Track B", "Track C") }
    var nextId by remember { mutableIntStateOf(4) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Live frame-time readout
        Column(
            Modifier
                .fillMaxWidth()
                .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerHigh, appShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Live Frame Readout", style = MaterialTheme.typography.titleSmall)
            Text(
                "last=${(lastFrameMs * 10f).roundToInt() / 10f} ms  max=${(maxFrameMs * 10f).roundToInt() / 10f} ms  " +
                    ">16.7ms=$over16Count  >20ms=$over20Count  frames=$frameCount",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (reduceMotion) "Reduce motion: ON (all motion collapses to snap/fade)" else "Reduce motion: OFF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Live token sliders
        Column(
            Modifier
                .fillMaxWidth()
                .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, appShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Live Tokens", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = {
                    MotionTuningState.reset()
                    maxFrameMs = 0f
                    over16Count = 0
                    over20Count = 0
                }) { Text("Reset") }
            }
            Text("Duration scale: ${(tuning.durationScale * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = tuning.durationScale,
                onValueChange = { MotionTuningState.current = tuning.copy(durationScale = it) },
                valueRange = 0.25f..2.0f,
            )
            Text("Spatial stiffness scale: ${(tuning.spatialStiffnessScale * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = tuning.spatialStiffnessScale,
                onValueChange = { MotionTuningState.current = tuning.copy(spatialStiffnessScale = it) },
                valueRange = 0.5f..2.0f,
            )
            Text("Press scale: ${(tuning.pressScale * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = tuning.pressScale,
                onValueChange = { MotionTuningState.current = tuning.copy(pressScale = it) },
                valueRange = 0.88f..0.99f,
            )
        }

        // Pattern showcase: press, toggle, expand, page transition, crossfade, skeleton, ambient loop
        Column(
            Modifier
                .fillMaxWidth()
                .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, appShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Patterns", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val pressSrc = remember { MutableInteractionSource() }
                Button(
                    onClick = { pageStep++ },
                    interactionSource = pressSrc,
                    modifier = Modifier.pressScale(pressSrc),
                ) { Text("Press + Page") }
                Switch(checked = switchOn, onCheckedChange = { switchOn = it })
                OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Expand") }
            }
            AnimatedContent(
                targetState = pageStep,
                transitionSpec = { pageTransition(NavMove.Forward, reduceMotion) },
                label = "labPage",
            ) { step ->
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
                ) {
                    Text("Page state #$step", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandSectionEnter(reduceMotion),
                exit = expandSectionExit(reduceMotion),
            ) {
                Text("Expanded section using Motion.Spatial.ExpandMs.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { crossfadeIdx = (crossfadeIdx + 1) % 3 }) { Text("Crossfade") }
                Crossfade(crossfadeIdx, animationSpec = motionCrossfadeSpec(), label = "labCrossfade") { idx ->
                    Text("Cover tone #$idx", style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Shared-clock skeleton shimmer + ambient orb
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(24.dp).skeleton(RoundedCornerShape(8.dp)))
                Box(
                    Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            val p = clock.pingPong(Motion.Ambient.CallPulsePeriodMs)
                            alpha = 0.35f + 0.65f * p
                            scaleX = 0.85f + 0.15f * p
                            scaleY = 0.85f + 0.15f * p
                        }
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            // Dynamic list items
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { items.add(0, "Track ${nextId++}") }) { Text("Add item") }
                OutlinedButton(onClick = { if (items.isNotEmpty()) items.removeAt(0) }) { Text("Remove item") }
            }
            Column(Modifier.animateContentSize(motionSpatialSpring()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { label ->
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!isPhone) {
            DesktopMotionLabSection(reduceMotion)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DesktopMotionLabSection(reduceMotion: Boolean) {
    val monitor = remember { DesktopFrameMonitor() }
    var overlayOn by remember { mutableStateOf(false) }
    var deskPageStep by remember { mutableIntStateOf(0) }
    var deskMove by remember { mutableStateOf(NavMove.Forward) }
    var sidebarWide by remember { mutableStateOf(true) }
    val wheelState = rememberLazyListState()
    val shiftPx = with(LocalDensity.current) { Motion.Desktop.PageShiftDp.dp.roundToPx() }
    val sideShiftPx = with(LocalDensity.current) { Motion.Desktop.PageSideShiftDp.dp.roundToPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, appShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Desktop Motion Suite", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Frame timing overlay", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { monitor.reset() }) { Text("Reset stats") }
            Switch(checked = overlayOn, onCheckedChange = { overlayOn = it })
        }
        DesktopFrameOverlay(monitor = monitor, enabled = overlayOn)
        Text(
            "Skiko FPS flags: -Dskiko.fps.enabled=true -Dskiko.fps.periodSeconds=1.0 -Dskiko.fps.longFrames.show=true",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DesktopTooltip("Fast non-bouncy hover, press, and focus ring") {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .desktopInteractive(RoundedCornerShape(10.dp))
                        .clickable { deskMove = NavMove.Forward; deskPageStep++ }
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("Card Forward", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedButton(onClick = { deskMove = NavMove.Back; deskPageStep-- }) { Text("Card Back") }
            OutlinedButton(onClick = { deskMove = NavMove.Switch; deskPageStep++ }) { Text("Card Switch") }
            OutlinedButton(onClick = { sidebarWide = !sidebarWide }) { Text(if (sidebarWide) "Narrow bar" else "Wide bar") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .animatedWidth(if (sidebarWide) 180.dp else 72.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (sidebarWide) "Sidebar 180dp" else "72dp", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
            }
            AnimatedContent(
                targetState = deskPageStep,
                modifier = Modifier.weight(1f),
                transitionSpec = { desktopPageTransition(deskMove, reduceMotion, shiftPx, sideShiftPx) },
                label = "labDeskPage",
            ) { step ->
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("Desktop card state #$step (${deskMove.name})", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("Smooth wheel scroll + fading scrollbar (120 items)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DesktopScrollHost(wheelState, Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) { scrollMod ->
            LazyColumn(scrollMod, state = wheelState) {
                items(120) { idx ->
                    Row(
                        Modifier.fillMaxWidth().desktopInteractive(RoundedCornerShape(8.dp)).clickable {}.padding(horizontal = 12.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Sample track #${idx + 1}", style = MaterialTheme.typography.bodySmall)
                        Text("3:${(10 + idx % 49).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Preview(name = "Motion Lab", widthDp = 390, heightDp = 800, showBackground = true)
@Composable
private fun MotionLabPreview() = PreviewFrame(dark = true) {
    MotionLabPage()
}
