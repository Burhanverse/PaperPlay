package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Settings > Audio > USB probe:
 * Dedicated debug and diagnostic tool for inspecting connected USB DACs,
 * dumping raw and parsed descriptors, testing Android 14 bit-perfect mixer attributes,
 * executing safe direct control transfer claim tests, and exporting diagnostic debug logs.
 */
@Composable
internal fun UsbProbePage(backend: CoreBackend) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val lastFallbackPath = remember { backend.getLastUsbFallbackLogPath() }

    UsbProbeContent(
        report = report,
        isRunning = isRunning,
        note = note,
        lastFallbackPath = lastFallbackPath,
        onRunProbe = {
            isRunning = true
            note = "Running USB hardware probe..."
            scope.launch {
                try {
                    val result = backend.runUsbProbe()
                    report = result
                    note = "Probe completed successfully. Report exported to app storage."
                } catch (e: Throwable) {
                    report = "Error running USB probe: ${e.message}\n${e.stackTraceToString()}"
                    note = "Probe execution failed"
                } finally {
                    isRunning = false
                }
            }
        },
        onExportDebugLog = {
            scope.launch {
                note = "Exporting USB debug log..."
                try {
                    val path = backend.exportUsbDebugLog()
                    if (path.startsWith("/")) {
                        val fileName = path.substringAfterLast('/')
                        val share = BackupFiles.shareFilePath
                        if (share != null) {
                            share(fileName, path)
                            note = "Exported debug log and opened share sheet."
                        } else {
                            note = "Debug log written to $path"
                        }
                    } else {
                        note = path
                    }
                } catch (e: Throwable) {
                    note = "Failed to export debug log: ${e.message}"
                }
            }
        },
        onShareLastFallback = if (lastFallbackPath != null) {
            {
                val fileName = lastFallbackPath.substringAfterLast('/')
                val share = BackupFiles.shareFilePath
                if (share != null) {
                    share(fileName, lastFallbackPath)
                    note = "Opened share sheet for last failure log ($fileName)."
                } else {
                    note = "Failure log located at $lastFallbackPath"
                }
            }
        } else null,
        onTriggerPcmDump = {
            val msg = backend.triggerUsbPcmDump(true)
            note = msg
        },
        onSharePcmDump = {
            val path = backend.getUsbPcmDumpPath()
            if (path != null) {
                val fileName = path.substringAfterLast('/')
                val share = BackupFiles.shareFilePath
                if (share != null) {
                    share(fileName, path)
                    note = "Opened share sheet for PCM dump ($fileName)."
                } else {
                    note = "PCM dump located at $path"
                }
            } else {
                note = "No PCM dump file available yet. Tap Dump USB PCM to arm."
            }
        },
        pcmDumpStatus = backend.getUsbPcmDumpStatus(),
        onClearNote = { note = null }
    )
}

@Composable
internal fun UsbProbeContent(
    report: String?,
    isRunning: Boolean,
    note: String?,
    lastFallbackPath: String? = null,
    onRunProbe: () -> Unit,
    onExportDebugLog: () -> Unit,
    onShareLastFallback: (() -> Unit)? = null,
    onTriggerPcmDump: (() -> Unit)? = null,
    onSharePcmDump: (() -> Unit)? = null,
    pcmDumpStatus: String? = null,
    onClearNote: () -> Unit = {}
) {
    val clipboard = LocalClipboardManager.current
    var localNote by remember { mutableStateOf(note) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Primary Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onRunProbe,
                enabled = !isRunning,
                modifier = Modifier.weight(1f)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Text("Probing...")
                } else {
                    AppIcon(Icons.Rounded.PlayArrow, "Run Probe", modifier = Modifier.padding(end = 4.dp))
                    Text("Run Probe")
                }
            }

            OutlinedButton(
                onClick = onExportDebugLog,
                enabled = !isRunning,
                modifier = Modifier.weight(1f)
            ) {
                AppIcon(Icons.Rounded.IosShare, "Export debug log", modifier = Modifier.padding(end = 4.dp))
                Text("Export debug log")
            }
        }

        // Secondary Actions Row: Copy Report & Optional Last Fallback Share
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    if (!report.isNullOrBlank()) {
                        clipboard.setText(AnnotatedString(report))
                        localNote = "Report copied to clipboard"
                    }
                },
                enabled = !report.isNullOrBlank() && !isRunning,
                modifier = Modifier.weight(1f)
            ) {
                AppIcon(Icons.Rounded.ContentCopy, "Copy Report", modifier = Modifier.padding(end = 4.dp))
                Text("Copy Report")
            }

            if (onShareLastFallback != null) {
                OutlinedButton(
                    onClick = onShareLastFallback,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    AppIcon(Icons.Rounded.BugReport, "Share last failure", modifier = Modifier.padding(end = 4.dp))
                    Text("Share last failure")
                }
            }
        }

        // Tertiary Actions Row: Dump USB PCM & Share PCM Dump
        if (onTriggerPcmDump != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onTriggerPcmDump,
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    AppIcon(Icons.Rounded.PlayArrow, "Dump USB PCM", modifier = Modifier.padding(end = 4.dp))
                    Text("Dump USB PCM")
                }

                if (onSharePcmDump != null) {
                    OutlinedButton(
                        onClick = onSharePcmDump,
                        enabled = !isRunning,
                        modifier = Modifier.weight(1f)
                    ) {
                        AppIcon(Icons.Rounded.IosShare, "Share PCM dump", modifier = Modifier.padding(end = 4.dp))
                        Text("Share PCM dump")
                    }
                }
            }
        }

        // Note banner if present
        val displayNote = localNote ?: note
        if (!displayNote.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = displayNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Output Display Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp)
        ) {
            val textContent = when {
                isRunning -> "Executing USB probe...\n\n1. Inspecting USB device connection\n2. Reading raw device and configuration descriptors\n3. Parsing UAC topology (clocks, terminals, feature units, alts)\n4. Querying AudioManager.getSupportedMixerAttributes\n5. Performing safe claim, GET_RANGE, SET_CUR 96 kHz, and readback\n6. Restoring alt 0 and reattaching kernel driver..."
                report != null -> report
                else -> "Connect a USB DAC and tap 'Run Probe' to start hardware inspection.\n\nThe probe will:\n- Dump VID/PID, product name, and negotiated speed\n- Read raw descriptors and parse UAC 1.0 / 2.0 topology\n- Evaluate Android 14+ AudioMixerAttributes (bit-perfect mode)\n- Perform safe claim, 96 kHz clock configuration, and GET_CUR readback\n- Reattach kernel driver and confirm system audio availability\n- Export full formatted report to app storage for analysis\n\nTap 'Export debug log' to capture in-memory ring logs and system logcat to share."
            }

            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = textContent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Preview
@Composable
private fun UsbProbeContentPreview() {
    MaterialTheme {
        UsbProbeContent(
            report = "=== PAPERPLAY USB AUDIO PROBE REPORT ===\nVID:PID: 0x3302:0x3365 CX31993 384Khz HIFI AUDIO\nUAC Version: UAC 2.0\nClock Source 9: Verified 96000 Hz",
            isRunning = false,
            note = "Probe ready",
            onRunProbe = {},
            onExportDebugLog = {}
        )
    }
}
