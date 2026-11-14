package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.components.CoverImage

/** A track a list wants that the library does not have. [art] gives a cover file (or null) when asked; [openUrl] is its Spotify page. */
class GhostSong(
    val key: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val openUrl: String?,
    /** How many songs of the list that the library has come before this one. */
    val after: Int,
    val art: suspend () -> String?,
)

/** A song that is not in the library: its art and name, greyed out. Tapping it opens it in Spotify. */
@Composable
fun GhostRow(g: GhostSong, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val art by produceState<String?>(null, g.key) { value = runCatching { g.art() }.getOrNull() }
    Row(
        modifier.fillMaxWidth().alpha(0.5f).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = g.openUrl != null, onClickLabel = "Open in Spotify") { g.openUrl?.let { runCatching { uri.openUri(it) } } }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(art, g.key, size = 48.dp, radius = 8.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(g.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (g.openUrl != null) AppIcon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in Spotify", Modifier.size(20.dp))
    }
}
