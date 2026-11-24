package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.components.AppIcon

/**
 * The desktop's bar over the pages: back, home, and the profile picture with its menu
 * (profile, recently played, settings and a private session that leaves no trace in Recently played or in what mixes learn).
 */
@Composable
internal fun DesktopTopBar(
    prefs: UiPrefs, backend: CoreBackend, canBack: Boolean, onBack: () -> Unit, onGo: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val private by backend.privateSession.collectAsState()
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onBack, enabled = canBack, modifier = Modifier.size(40.dp)) { AppIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Box(Modifier.weight(1f))
        IconButton({ onGo("home") }, Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHigh)) { AppIcon(Icons.Rounded.Home, "Home") }
        Box(Modifier.weight(1f))
        Box {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { menu = true }, contentAlignment = Alignment.Center) {
                Avatar(prefs.profilePhoto, prefs.profileName, 34.dp)
                // A small dot says the session is private.
                if (private) Box(Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape).background(scheme.primary))
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Profile") }, { menu = false; onGo("profile") })
                DropdownMenuItem({ Text("Recently played") }, { menu = false; onGo("recent") })
                DropdownMenuItem({ Text("Settings") }, { menu = false; onGo("settings") })
                DropdownMenuItem(
                    { Text("Private session") },
                    { backend.setPrivateSession(!private) },
                    trailingIcon = { if (private) AppIcon(Icons.Rounded.Check, "On", Modifier.size(18.dp), tint = scheme.primary) },
                )
            }
        }
    }
}
