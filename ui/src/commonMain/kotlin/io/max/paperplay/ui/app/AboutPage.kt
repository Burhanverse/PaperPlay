package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.update.ChangelogBullet
import io.max.paperplay.core.update.ChangelogGroup
import io.max.paperplay.core.update.ChangelogParser
import io.max.paperplay.core.update.ChangelogState
import io.max.paperplay.core.update.ChangelogVersionEntry
import io.max.paperplay.core.update.InstallBlocked
import io.max.paperplay.core.update.ReleaseNotesDoc
import io.max.paperplay.core.update.ShizukuControl
import io.max.paperplay.core.update.UpdateService
import io.max.paperplay.core.update.UpdateState
import io.max.paperplay.ui.StaticLogo
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.launch

private const val REPO = "https://github.com/maxcodl/PaperPlay"

/** Settings > About: the app and its update status with one button, three update choices, and the project links. */
@Composable
internal fun AboutPage(backend: CoreBackend, onOpenChangelog: () -> Unit = {}) {
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
    NavRow("Change log", "What changed in every release", Icons.Rounded.History, onClick = onOpenChangelog)
    SettingsRow("Source code", icon = Icons.Rounded.Code, subtitle = "github.com/maxcodl/PaperPlay", onClick = { uri.openUri(REPO) })
    SettingsRow("Releases", icon = Icons.Rounded.SystemUpdate, subtitle = "Downloads and assets on GitHub", onClick = { uri.openUri("$REPO/releases") })
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

/** Formatted release notes card used in the About updater section and on the in-app Change log page. */
@Composable
internal fun ReleaseNotes(notes: String, modifier: Modifier = Modifier) {
    val doc = remember(notes) { ChangelogParser.parseReleaseBody(notes) }
    if (doc.isEmpty) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .partSurface(LookGroup.Cards, MaterialTheme.colorScheme.surfaceContainerLow, appShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ReleaseNotesBody(doc)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseNotesBody(doc: ReleaseNotesDoc) {
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    doc.groups.forEachIndexed { idx, group ->
        if (idx > 0) {
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 2.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GroupBadge(group.heading)
            group.items.forEach { item ->
                ChangelogBulletRow(item, onOpenCommit = { url -> uri.openUri(url) })
            }
        }
    }
    val compareLabel = doc.compareLabel
    val compareUrl = doc.compareUrl
    if (compareLabel != null && compareUrl != null) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { uri.openUri(compareUrl) }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Full changes: $compareLabel",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                fontWeight = FontWeight.Medium,
            )
            AppIcon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(14.dp), tint = scheme.primary)
        }
    }
}

@Composable
private fun GroupBadge(heading: String) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (heading.lowercase()) {
        "breaking changes" -> scheme.errorContainer to scheme.onErrorContainer
        "added" -> scheme.primaryContainer to scheme.onPrimaryContainer
        "fixed" -> scheme.secondaryContainer to scheme.onSecondaryContainer
        "improved" -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        else -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bg,
        contentColor = fg,
    ) {
        Text(
            text = heading,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ChangelogBulletRow(item: ChangelogBullet, onOpenCommit: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(scheme.primary),
        )
        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
        )
        val sha = item.commitSha
        if (sha != null) {
            val url = item.commitUrl
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = scheme.surfaceContainerHighest,
                modifier = if (url != null) Modifier.clip(RoundedCornerShape(6.dp)).clickable { onOpenCommit(url) } else Modifier,
            ) {
                Text(
                    text = sha,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (url != null) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Settings > About > Change log: full release history rendered in-app with search and channel filter. */
@Composable
internal fun ChangelogPage(backend: CoreBackend) {
    val updates = backend.services.updates
    val state by updates.changelog.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var query by remember { mutableStateOf("") }
    var showBetas by remember { mutableStateOf(updates.prefs.beta) }

    LaunchedEffect(Unit) {
        updates.loadChangelog()
    }

    val installedVer = updates.installed.toString()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillField(
                value = query,
                onChange = { query = it },
                hint = "Search releases and changes",
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { scope.launch { updates.loadChangelog(force = true) } },
                enabled = state !is ChangelogState.Loading,
            ) {
                AppIcon(Icons.Rounded.Refresh, "Refresh change log")
            }
        }

        when (val s = state) {
            ChangelogState.Idle, ChangelogState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Loading change log...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is ChangelogState.Failed -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ scope.launch { updates.loadChangelog(force = true) } }) { Text("Try again") }
                        OutlinedButton({ uri.openUri("$REPO/blob/main/CHANGELOG.md") }) { Text("Open on GitHub") }
                    }
                }
            }
            is ChangelogState.Ready -> {
                val hasBetas = remember(s.entries) { s.entries.any { it.prerelease } }
                val q = query.trim().lowercase()
                val filtered = remember(s.entries, q, showBetas) {
                    s.entries.mapNotNull { entry ->
                        if (!showBetas && entry.prerelease) return@mapNotNull null
                        if (q.isEmpty()) return@mapNotNull entry
                        val versionMatches = entry.version.lowercase().contains(q) || (entry.date?.lowercase()?.contains(q) == true)
                        if (versionMatches) return@mapNotNull entry
                        val matchingGroups = entry.notes.groups.mapNotNull { g ->
                            val items = g.items.filter {
                                it.text.lowercase().contains(q) || (it.commitSha?.lowercase()?.contains(q) == true)
                            }
                            if (items.isNotEmpty() || g.heading.lowercase().contains(q)) ChangelogGroup(g.heading, if (items.isNotEmpty()) items else g.items)
                            else null
                        }
                        if (matchingGroups.isNotEmpty()) entry.copy(notes = entry.notes.copy(groups = matchingGroups))
                        else null
                    }
                }

                if (hasBetas) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${filtered.size} ${if (filtered.size == 1) "release" else "releases"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton({ showBetas = !showBetas }) {
                            Text(if (showBetas) "Hide beta builds" else "Show beta builds")
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    Text(
                        "No releases match \"$query\"",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = LocalPageBottomPadding.current + 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filtered, key = { it.version }) { entry ->
                            ChangelogVersionCard(
                                entry = entry,
                                isInstalled = entry.version.removePrefix("v") == installedVer.removePrefix("v"),
                                onOpenRelease = { url -> uri.openUri(url) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangelogVersionCard(
    entry: ChangelogVersionEntry,
    isInstalled: Boolean,
    onOpenRelease: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val title = if (entry.version.equals("Unreleased", ignoreCase = true)) "Unreleased" else "Version ${entry.version.removePrefix("v")}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .partSurface(LookGroup.Cards, scheme.surfaceContainerLow, appShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (isInstalled) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                        ) {
                            Text(
                                "Installed",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (entry.prerelease) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                        ) {
                            Text(
                                "Beta",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                entry.date?.let { date ->
                    Text(date, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            val pageUrl = entry.pageUrl
            if (pageUrl != null) {
                IconButton(onClick = { onOpenRelease(pageUrl) }, modifier = Modifier.size(32.dp)) {
                    AppIcon(Icons.AutoMirrored.Rounded.OpenInNew, "Open release on GitHub", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        ReleaseNotesBody(entry.notes)
    }
}
