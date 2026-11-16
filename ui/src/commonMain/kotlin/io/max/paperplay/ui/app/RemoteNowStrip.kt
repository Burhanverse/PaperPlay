package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage
import kotlinx.coroutines.launch

/**
 * A slim strip, "Playing on <device>: title - artist", shown while a linked device plays and this one is not driving it.
 * Tapping it moves playback over to that device. Nothing is shown when no linked device is playing.
 */
@Composable
fun RemoteNowStrip(backend: CoreBackend, modifier: Modifier = Modifier) {
    val lan = LocalLanSync.current ?: return
    val connected by lan.connected.collectAsState()
    val now by lan.remoteNow.collectAsState()
    val active by backend.activeOutput.collectAsState()
    val scope = rememberCoroutineScope()
    if (active != null) return
    val nowMs = System.currentTimeMillis()
    val d = connected.firstOrNull { now[it.id]?.playingNow(nowMs) == true && now[it.id]?.title != null } ?: return
    val n = now[d.id] ?: return
    val cover = n.uniqueId?.let { backend.songCover(it) }
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = scheme.secondaryContainer) {
        Row(
            Modifier.clickable(enabled = d.paired) { backend.switchOutput(d.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(cover, n.uniqueId ?: d.id, size = 36.dp, radius = 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(if (d.desktop) Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid, null, Modifier.size(14.dp), tint = scheme.onSecondaryContainer)
                    Text("Playing on ${d.name}" + (n.outName?.let { " via $it" } ?: ""), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall, color = scheme.onSecondaryContainer, maxLines = 1)
                }
                Text(
                    n.title.orEmpty() + (n.artist?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (d.paired) IconButton({ scope.launch { lan.sendControl(d.id, if (n.playing) "pause" else "play") } }, Modifier.size(36.dp)) {
                AppIcon(if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (n.playing) "Pause on ${d.name}" else "Play on ${d.name}", Modifier.size(22.dp), tint = scheme.onSecondaryContainer)
            }
        }
    }
}
