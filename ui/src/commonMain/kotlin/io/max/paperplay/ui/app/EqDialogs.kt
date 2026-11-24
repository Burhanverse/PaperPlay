package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqCodec
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.player.PeqChannel
import io.max.paperplay.player.PeqFilterType
import io.max.paperplay.ui.components.AppIcon

internal val builtInParametricPresets: List<ParametricEqProfile> = listOf(
    ParametricEqProfile.flat10("builtin-flat-10", "Flat (10-Band)"),
    ParametricEqProfile.iso31Preset("builtin-iso-31", "31-Band Graphic (ISO)"),
    ParametricEqProfile(
        id = "builtin-bass-boost",
        name = "Deep Bass",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.HP, PeqChannel.STEREO, 22f, 0f, 0.71f),
            ParametricBand(2, true, PeqFilterType.LSC, PeqChannel.STEREO, 95f, 6.5f, 0.71f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 200f, -1.5f, 1.2f),
            ParametricBand(4, true, PeqFilterType.PK, PeqChannel.STEREO, 2800f, 1.2f, 1.8f),
            ParametricBand(5, true, PeqFilterType.HSC, PeqChannel.STEREO, 10000f, 1.0f, 0.71f),
        ),
    ),
    ParametricEqProfile(
        id = "builtin-vocal-clarity",
        name = "Vocal Clarity",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.LSC, PeqChannel.STEREO, 100f, -1.5f, 0.71f),
            ParametricBand(2, true, PeqFilterType.PK, PeqChannel.STEREO, 250f, -2.0f, 1.1f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 1800f, 2.5f, 1.4f),
            ParametricBand(4, true, PeqFilterType.PK, PeqChannel.STEREO, 3500f, 2.0f, 1.6f),
            ParametricBand(5, true, PeqFilterType.HSC, PeqChannel.STEREO, 8500f, -0.5f, 0.71f),
        ),
    ),
    ParametricEqProfile(
        id = "builtin-harman-warm",
        name = "Warm Harman Tilt",
        source = "Built-in",
        preampDb = 0f,
        autoPreamp = true,
        limiterEnabled = true,
        bands = listOf(
            ParametricBand(1, true, PeqFilterType.LSC, PeqChannel.STEREO, 105f, 4.0f, 0.71f),
            ParametricBand(2, true, PeqFilterType.PK, PeqChannel.STEREO, 220f, -1.0f, 1.0f),
            ParametricBand(3, true, PeqFilterType.PK, PeqChannel.STEREO, 3000f, 1.5f, 1.5f),
            ParametricBand(4, true, PeqFilterType.HSC, PeqChannel.STEREO, 9500f, -1.5f, 0.71f),
        ),
    ),
)

@Composable
internal fun BandNumericInputDialog(
    band: ParametricBand,
    onSave: (ParametricBand) -> Unit,
    onClose: () -> Unit,
) {
    var freqText by remember(band.id) { mutableStateOf(fmtRawNum(band.freqHz)) }
    var gainText by remember(band.id) { mutableStateOf(fmtRawNum(band.gainDb)) }
    var qText by remember(band.id) { mutableStateOf(fmtRawNum(band.q)) }
    var error by remember { mutableStateOf<String?>(null) }
    val shape = appShape(28.dp, CornerArea.Dialogs)

    AlertDialog(
        onDismissRequest = onClose,
        modifier = overlayRim(shape),
        shape = shape,
        containerColor = overlayColor(),
        title = { Text("Edit band parameters") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = freqText,
                    onValueChange = { freqText = it; error = null },
                    label = { Text("Frequency (10 - 24000 Hz)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (band.type.usesGain) {
                    OutlinedTextField(
                        value = gainText,
                        onValueChange = { gainText = it; error = null },
                        label = { Text("Gain (-24 to +24 dB)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = qText,
                    onValueChange = { qText = it; error = null },
                    label = { Text("Q factor (0.1 - 20.0)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val f = freqText.trim().toFloatOrNull()
                    val g = if (band.type.usesGain) gainText.trim().toFloatOrNull() else band.gainDb
                    val q = qText.trim().toFloatOrNull()
                    if (f == null || g == null || q == null) {
                        error = "Enter valid numeric values."
                    } else {
                        onSave(band.copy(freqHz = f, gainDb = g, q = q).clamped())
                    }
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
internal fun PresetsPickerDialog(
    userPresets: List<ParametricEqProfile>,
    currentId: String,
    onSelect: (ParametricEqProfile) -> Unit,
    onDeleteUser: (String) -> Unit,
    onClose: () -> Unit,
) {
    val shape = appShape(28.dp, CornerArea.Dialogs)
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        modifier = overlayRim(shape),
        shape = shape,
        containerColor = overlayColor(),
        title = { Text("EQ presets") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                item { SectionLabel("Built-in") }
                itemsIndexed(builtInParametricPresets, key = { _, p -> p.id }) { idx, p ->
                    val current = p.id == currentId
                    if (idx > 0) RowDivider()
                    SettingsRow(
                        title = p.name,
                        subtitle = "${p.bands.size} bands",
                        titleColor = if (current) scheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                        onClick = { onSelect(p) },
                    ) {
                        if (current) AppIcon(Icons.Rounded.Check, "Current", tint = scheme.primary)
                    }
                }
                if (userPresets.isNotEmpty()) {
                    item { SectionLabel("Saved presets") }
                    itemsIndexed(userPresets, key = { _, p -> p.id }) { idx, p ->
                        val current = p.id == currentId
                        if (idx > 0) RowDivider()
                        SettingsRow(
                            title = p.name,
                            subtitle = "${p.source} · ${p.bands.size} bands",
                            titleColor = if (current) scheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                            onClick = { onSelect(p) },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (current) AppIcon(Icons.Rounded.Check, "Current", tint = scheme.primary)
                                IconButton(onClick = { onDeleteUser(p.id) }) {
                                    AppIcon(Icons.Rounded.Delete, "Delete preset")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
internal fun SavePresetDialog(
    initialName: String,
    title: String = "Save EQ preset",
    confirmLabel: String = "Save",
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val shape = appShape(28.dp, CornerArea.Dialogs)
    AlertDialog(
        onDismissRequest = onClose,
        modifier = overlayRim(shape),
        shape = shape,
        containerColor = overlayColor(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Preset name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
internal fun ImportEqDialog(
    onImport: (ParametricEqProfile) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val shape = appShape(28.dp, CornerArea.Dialogs)

    AlertDialog(
        onDismissRequest = onClose,
        modifier = overlayRim(shape),
        shape = shape,
        containerColor = overlayColor(),
        title = { Text("Import APO / Poweramp EQ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste Equalizer APO ParametricEQ.txt or Poweramp Equalizer .json below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Optional preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("APO text or Poweramp JSON") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val trimmed = text.trim()
                    val parsed = if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                        ParametricEqCodec.parsePowerampJson(trimmed)
                    } else {
                        ParametricEqCodec.parseEqualizerApo(trimmed, name = name.trim().ifEmpty { "Imported APO" })
                    }
                    if (parsed == null) {
                        error = "Could not parse APO ParametricEQ.txt or Poweramp JSON."
                    } else {
                        val finalProf = if (name.isNotBlank()) parsed.copy(name = name.trim()) else parsed
                        onImport(finalProf)
                    }
                },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
internal fun ExportEqDialog(
    profile: ParametricEqProfile,
    onClose: () -> Unit,
) {
    var format by remember { mutableIntStateOf(0) } // 0 = Equalizer APO .txt, 1 = Poweramp .json
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val exported = remember(profile, format) {
        if (format == 0) ParametricEqCodec.exportEqualizerApo(profile)
        else ParametricEqCodec.exportPowerampJson(profile, includePaperPlayMeta = false)
    }
    val shape = appShape(28.dp, CornerArea.Dialogs)

    AlertDialog(
        onDismissRequest = onClose,
        modifier = overlayRim(shape),
        shape = shape,
        containerColor = overlayColor(),
        title = { Text("Export equalizer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val formats = listOf("Equalizer APO .txt", "Poweramp .json")
                ChoiceRow(
                    options = formats,
                    selected = formats[format],
                    label = { it },
                    onSelect = { chosen ->
                        format = formats.indexOf(chosen).coerceAtLeast(0)
                        copied = false
                    },
                )
                OutlinedTextField(
                    value = exported,
                    onValueChange = {},
                    readOnly = true,
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (copied) {
                    Text("Copied to clipboard.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(exported))
                    copied = true
                },
            ) {
                AppIcon(Icons.Rounded.ContentCopy, "Copy")
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
