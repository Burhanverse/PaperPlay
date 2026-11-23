package io.max.paperplay.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.update.InstallBlocked
import io.max.paperplay.core.update.ShizukuControl
import io.max.paperplay.core.update.UpdateService
import io.max.paperplay.core.update.UpdateState
import io.max.paperplay.ui.StaticLogo
import kotlinx.coroutines.launch

private const val REPO = "https://github.com/maxcodl/PaperPlay"

/** Settings > About: the app and its update status with one button, three update choices, and the project links. */
@Composable
internal fun AboutPage(backend: CoreBackend) {
    val updates = backend.services.updates
    val uri = LocalUriHandler.current
    val devUnlocked by backend.devOptionsUnlocked.collectAsState()
    UpdateHeader(updates, devUnlocked = devUnlocked, onUnlockDevOptions = backend::unlockDevOptions)
    SectionLabel("Updates")
    var beta by remember { mutableStateOf(updates.prefs.beta) }
    DropdownRow(
        "Update channel", beta, listOf(false, true), { if (it) "Beta pre-releases" else "Stable" },
        onSelect = { updates.prefs.beta = it; beta = it },
        icon = Icons.Rounded.NewReleases,
        subtitle = if (beta) "Pre-releases arrive sooner and may have rough edges" else "Only finished releases",
    )
    InstallMethod(updates)
    var auto by remember { mutableStateOf(updates.prefs.auto) }
    SwitchRow("Check automatically", auto, { updates.prefs.auto = it; auto = it }, subtitle = "At most once a day, when the app starts")
    SectionLabel("Project")
    SettingsRow("Source code", icon = Icons.Rounded.Code, subtitle = "github.com/maxcodl/PaperPlay", onClick = { uri.openUri(REPO) })
    SettingsRow("Releases", icon = Icons.Rounded.SystemUpdate, onClick = { uri.openUri("$REPO/releases") })
    SettingsRow("Change log", icon = Icons.Rounded.History, onClick = { uri.openUri("$REPO/blob/main/CHANGELOG.md") })
    MadeBy()
}

/** Android: System installer or Shizuku in one drop-down row; Shizuku's state and its button show in the row once Shizuku is chosen. */
@Composable
private fun InstallMethod(updates: UpdateService) {
    val shizuku = updates.shizuku ?: return
    var useShizuku by remember { mutableStateOf(updates.prefs.shizuku) }
    var problem by remember { mutableStateOf<String?>(null) }
    // Shizuku can be started or allowed in another app, so its state is read again while the page is open.
    var status by remember { mutableStateOf(shizukuStatus(shizuku)) }
    LaunchedEffect(shizuku) {
        while (true) {
            status = shizukuStatus(shizuku)
            useShizuku = updates.prefs.shizuku // the install button can switch back to the system installer
            kotlinx.coroutines.delay(3000)
        }
    }
    DropdownRow(
        "Installation method", useShizuku, listOf(false, true), { if (it) "Shizuku" else "System installer" },
        onSelect = { chosen ->
            if (chosen && !shizuku.installed) {
                problem = "Install Shizuku first"
            } else {
                problem = null
                useShizuku = chosen
                updates.prefs.shizuku = chosen
                if (chosen && !shizuku.allowed) shizuku.ask()
            }
        },
        icon = Icons.Rounded.Bolt,
        subtitle = problem ?: if (useShizuku) "Shizuku: ${status.first}" else "Android asks you to confirm each update",
        extra = { if (useShizuku) status.second?.let { label -> OutlinedButton({ shizuku.ask() }) { Text(label) } } },
    )
}

/** What Shizuku's state means for the person, and the button that moves it on (null when nothing is left to do). */
private fun shizukuStatus(s: ShizukuControl): Pair<String, String?> = when {
    !s.installed -> "not installed" to null
    !s.running -> "not running" to "Open Shizuku"
    !s.allowed -> "running, PaperPlay not allowed yet" to "Allow"
    else -> "running and allowed" to null
}

/** The app, its version, the update status in one line, and the one button that fits the state. */
@Composable
private fun UpdateHeader(
    updates: UpdateService,
    devUnlocked: Boolean = false,
    onUnlockDevOptions: () -> Unit = {},
) {
    val state by updates.state.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var blocked by remember { mutableStateOf<InstallBlocked?>(null) }
    var versionTaps by remember { mutableStateOf(0) }
    // While the install waits for a permission, look for it once a second and carry on as soon as it is granted.
    val hooks = io.max.paperplay.ui.LocalAppEnv.current?.permissions
    LaunchedEffect(blocked) {
        if (blocked == null || hooks == null) return@LaunchedEffect
        while (hooks.check().firstOrNull { it.id == "install" }?.granted == false) kotlinx.coroutines.delay(1000)
        blocked = updates.install()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.clickable {
                if (!devUnlocked) {
                    val next = versionTaps + 1
                    versionTaps = next
                    if (next >= 7) onUnlockDevOptions()
                }
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StaticLogo(size = 48.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text("PaperPlay", style = MaterialTheme.typography.titleLarge)
                Text("Version ${updates.installed}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (devUnlocked) {
                    Text("Developer options enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (versionTaps in 3..6) {
                    Text("${7 - versionTaps} more taps for developer options", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        when (val s = state) {
            UpdateState.Idle -> CheckButton("Check for updates") { scope.launch { updates.check() } }
            UpdateState.Checking -> { Text("Checking for updates"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is UpdateState.UpToDate -> {
                Text("You have the latest version", color = MaterialTheme.colorScheme.primary)
                CheckButton("Check again") { scope.launch { updates.check() } }
            }
            is UpdateState.Failed -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                CheckButton("Try again") { scope.launch { updates.check() } }
            }
            is UpdateState.Available -> {
                Text("Version ${s.release.version} is available", style = MaterialTheme.typography.titleMedium)
                // The buttons come before the notes so a long list never pushes them under the bottom bar.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.asset != null) Button({ scope.launch { updates.download() } }) { Text("Download") }
                    else Button({ uri.openUri(s.release.pageUrl) }) { Text("Open release page") }
                    OutlinedButton({ updates.skip() }) { Text("Skip this version") }
                }
                ReleaseNotes(s.release.notes)
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${s.release.version}")
                LinearProgressIndicator({ s.fraction }, Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> {
                Text("Version ${s.release.version} is downloaded and checked", style = MaterialTheme.typography.titleMedium)
                Button({ blocked = null; scope.launch { blocked = updates.install() } }) { Text("Install") }
                blocked?.let { b ->
                    Text(b.message, color = MaterialTheme.colorScheme.error)
                    b.action?.let { act -> OutlinedButton({ act() }) { Text(b.actionLabel ?: "Open settings") } }
                }
            }
        }
    }
}

@Composable
private fun CheckButton(text: String, onClick: () -> Unit) = OutlinedButton(onClick) { Text(text) }

/** The release notes for people, not developers: no commit links, no maintenance section, headings bold, bullets as dots. */
@Composable
private fun ReleaseNotes(notes: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        userNotes(notes).take(30).forEach { (heading, text) ->
            if (heading) Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            else Text("•  $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val HIDDEN_SECTIONS = setOf("maintenance", "documentation")
private val COMMIT_LINK = Regex("""\s*\(\[`?[0-9a-f]{7,40}`?]\([^)]*\)\)""")
private val MD_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

/** Markdown release notes as (isHeading, text) lines, with commit links, the compare link and internal sections left out. */
internal fun userNotes(notes: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    var hidden = false
    for (raw in notes.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("**Full changes")) continue
        if (line.startsWith("#")) {
            val heading = line.trimStart('#', ' ')
            hidden = heading.lowercase() in HIDDEN_SECTIONS
            if (!hidden) out += true to heading
            continue
        }
        if (hidden) continue
        val text = line.removePrefix("- ").removePrefix("* ")
            .replace(COMMIT_LINK, "").replace(MD_LINK, "$1").replace("`", "").replace("**", "").trim()
        if (text.isNotEmpty()) out += false to text
    }
    // A heading whose items were all removed is dropped too.
    return out.filterIndexed { i, (h, _) -> !h || out.getOrNull(i + 1)?.first == false }
}
