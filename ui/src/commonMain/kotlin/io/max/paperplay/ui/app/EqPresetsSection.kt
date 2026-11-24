package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.eq.DeviceEqAssignment
import io.max.paperplay.player.OutputInfo
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.ui.components.AppIcon
import io.max.paperplay.ui.isPhone
import kotlin.math.round

@Composable
internal fun EqPresetsSection(
    backend: PlayerControls,
    profile: ParametricEqProfile,
    savedPresets: List<ParametricEqProfile>,
    deviceAssignments: List<DeviceEqAssignment>,
    eqDeviceKey: String?,
    eqOutput: String?,
    outputInfo: OutputInfo?,
    eqDeviceAssigned: Boolean,
    allowEqInUsb: Boolean,
    listState: LazyListState,
    bottomPadding: Dp,
    onToggleAssignDevice: () -> Unit,
    onSelectPreset: (ParametricEqProfile) -> Unit,
    onOpenSave: () -> Unit,
    onOpenRename: (ParametricEqProfile) -> Unit,
    onDeletePreset: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenExport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val devName = eqOutput ?: outputInfo?.device ?: "Current output"
    val devIcon = when {
        eqDeviceKey?.startsWith("usb:") == true -> Icons.Rounded.Usb
        eqDeviceKey?.startsWith("bt:") == true -> Icons.Rounded.Bluetooth
        eqDeviceKey?.startsWith("wired:") == true -> Icons.Rounded.Headphones
        else -> Icons.Rounded.Speaker
    }
    val eqOn by backend.eqOn.collectAsState()
    val bass by backend.eqBass.collectAsState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = bottomPadding + 24.dp),
    ) {
        item(key = "device") {
            SettingsSection("Output device") {
                SettingsRow(
                    title = devName,
                    icon = devIcon,
                    subtitle = if (eqDeviceAssigned) "Auto-loads profile on connect" else eqDeviceKey ?: "Play audio to bind this output device",
                    iconTint = scheme.primary,
                ) {
                    if (eqDeviceAssigned) {
                        FilledTonalButton(enabled = eqDeviceKey != null, onClick = onToggleAssignDevice) {
                            AppIcon(Icons.Rounded.Check, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Assigned", maxLines = 1)
                        }
                    } else {
                        OutlinedButton(enabled = eqDeviceKey != null, onClick = onToggleAssignDevice) {
                            Text("Assign", maxLines = 1)
                        }
                    }
                }
                if (isPhone) {
                    RowDivider()
                    SwitchRow(
                        title = "Allow EQ in USB exclusive mode",
                        checked = allowEqInUsb,
                        onChange = backend::setAllowEqInUsbExclusive,
                        icon = Icons.Rounded.Usb,
                        subtitle = "Keeps native sample rate and bit depth via direct USB output while running 64-bit EQ with TPDF dither. Off enforces strict bit-perfect bypass.",
                    )
                }
            }
        }

        if (deviceAssignments.isNotEmpty()) {
            item(key = "assignments") {
                SettingsSection("Assigned devices (${deviceAssignments.size})") {
                    deviceAssignments.forEachIndexed { idx, assignment ->
                        if (idx > 0) RowDivider()
                        SettingsRow(
                            title = assignment.deviceLabel,
                            subtitle = "${assignment.profile.name} · ${assignment.deviceKey}",
                        ) {
                            IconButton(onClick = { backend.removeDeviceEqAssignment(assignment.deviceKey) }) {
                                AppIcon(Icons.Rounded.Delete, "Remove assignment")
                            }
                        }
                    }
                }
            }
        }

        item(key = "io") {
            SettingsSection("Import and export") {
                SettingsRow(
                    title = "Save current profile",
                    icon = Icons.Rounded.Save,
                    subtitle = "Save active bands, preamp and limiter as a reusable preset",
                    onClick = onOpenSave,
                ) {
                    AppIcon(Icons.Rounded.ChevronRight, null)
                }
                RowDivider()
                SettingsRow(
                    title = "Import APO or Poweramp",
                    icon = Icons.Rounded.Upload,
                    subtitle = "Paste Equalizer APO ParametricEQ.txt or Poweramp .json",
                    onClick = onOpenImport,
                ) {
                    AppIcon(Icons.Rounded.ChevronRight, null)
                }
                RowDivider()
                SettingsRow(
                    title = "Export current profile",
                    icon = Icons.Rounded.Download,
                    subtitle = "Copy as Equalizer APO .txt or Poweramp .json",
                    onClick = onOpenExport,
                ) {
                    AppIcon(Icons.Rounded.ChevronRight, null)
                }
            }
        }

        item(key = "saved") {
            SettingsSection("Saved presets (${savedPresets.size})") {
                if (savedPresets.isEmpty()) {
                    SettingsRow(
                        title = "No saved presets yet",
                        subtitle = "Save the current profile or import an APO or Poweramp file above.",
                    )
                } else {
                    savedPresets.forEachIndexed { idx, p ->
                        val isCurrent = p.id == profile.id
                        if (idx > 0) RowDivider()
                        SettingsRow(
                            title = p.name,
                            subtitle = "${p.source} · ${p.bands.size} bands",
                            titleColor = if (isCurrent) scheme.primary else Color.Unspecified,
                            onClick = { onSelectPreset(p) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                if (isCurrent) {
                                    AppIcon(Icons.Rounded.Check, "Active preset", tint = scheme.primary)
                                }
                                IconButton(onClick = { onOpenRename(p) }) {
                                    AppIcon(Icons.Rounded.Edit, "Rename preset")
                                }
                                IconButton(onClick = { onDeletePreset(p.id) }) {
                                    AppIcon(Icons.Rounded.Delete, "Delete preset")
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "builtin") {
            SettingsSection("Built-in presets") {
                builtInParametricPresets.forEachIndexed { idx, p ->
                    val isCurrent = p.id == profile.id
                    if (idx > 0) RowDivider()
                    SettingsRow(
                        title = p.name,
                        subtitle = "${p.bands.size} bands",
                        titleColor = if (isCurrent) scheme.primary else Color.Unspecified,
                        onClick = { onSelectPreset(p) },
                    ) {
                        if (isCurrent) {
                            AppIcon(Icons.Rounded.Check, "Active preset", tint = scheme.primary)
                        }
                    }
                }
            }
        }

        if (backend.hasBass) {
            item(key = "bass") {
                val bassText = if (bass < 0.05f) "Off" else "+${round(bass * 10f) / 10f} dB"
                SettingsSection("Bass boost") {
                    SliderRow(
                        title = "Bass: $bassText",
                        value = bass,
                        range = 0f..12f,
                        steps = 23,
                        enabled = eqOn,
                        hint = "Sub-bass lift below 100 Hz with subsonic cut.",
                        onChange = { backend.setEqBass(round(it * 2f) / 2f) },
                    )
                }
            }
        }

        item(key = "extras") {
            EngineExtras(backend)
        }
    }
}
