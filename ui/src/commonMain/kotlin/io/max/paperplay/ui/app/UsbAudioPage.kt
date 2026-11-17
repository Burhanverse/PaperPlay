package io.max.paperplay.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.max.paperplay.player.usb.UsbExclusiveStatus
import io.max.paperplay.ui.components.AppIcon
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Settings > Audio > USB exclusive mode:
 * Direct bit-perfect USB Audio Class (UAC 1.0 & UAC 2.0) playback status and diagnostics.
 */
@Composable
internal fun UsbAudioPage(backend: CoreBackend, stack: MutableList<String>? = null) {
    val exclusiveOn by backend.usbExclusiveMode.collectAsState()
    val status by backend.usbExclusiveStatus.collectAsState()
    val devUnlocked by backend.devOptionsUnlocked.collectAsState()
    val sinkChain by backend.sinkChain.collectAsState()

    UsbAudioContent(
        exclusiveOn = exclusiveOn,
        status = status,
        onToggleExclusive = backend::setUsbExclusiveMode,
        onRequestPermission = backend::requestUsbPermission,
        onOpenProbe = { stack?.add("usb_probe") },
        onRunPlaybackTest = backend::runUsbPlaybackTest,
        onTriggerPcmDump = { backend.triggerUsbPcmDump(true) },
        pcmDumpStatus = backend.getUsbPcmDumpStatus(),
        devOptionsUnlocked = devUnlocked,
        sinkChain = sinkChain,
        onSelectSinkChain = backend::setSinkChain,
    )
}

@Composable
internal fun UsbAudioContent(
    exclusiveOn: Boolean,
    status: UsbExclusiveStatus?,
    onToggleExclusive: (Boolean) -> Unit,
    onRequestPermission: (() -> Unit)? = null,
    onOpenProbe: (() -> Unit)? = null,
    onRunPlaybackTest: (suspend () -> String)? = null,
    onTriggerPcmDump: (() -> Unit)? = null,
    pcmDumpStatus: String? = null,
    devOptionsUnlocked: Boolean = false,
    sinkChain: String = io.max.paperplay.player.SinkChainMode.FULL.id,
    onSelectSinkChain: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var isTesting by remember { mutableStateOf(false) }
    var testResultSubtitle by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            title = "USB exclusive mode",
            checked = exclusiveOn,
            onChange = onToggleExclusive,
            icon = Icons.Rounded.Usb,
            subtitle = "Bypasses the Android audio mixer, DSP, sample-rate conversion, volume scaling, and equalizer for direct bit-perfect streaming. Requires microphone access so Android allows saving DAC permissions permanently (TRRS headset input)."
        )

        if (onOpenProbe != null) {
            SettingsRow(
                title = "USB probe",
                icon = Icons.Rounded.BugReport,
                subtitle = "Hardware inspection, raw descriptor dump, Android 14 bit-perfect mixer spike, and direct claim test",
                onClick = onOpenProbe
            )
        }

        if (devOptionsUnlocked && onSelectSinkChain != null) {
            val mode = io.max.paperplay.player.SinkChainMode.fromId(sinkChain)
            DropdownRow(
                title = "Sink chain",
                selected = mode,
                options = io.max.paperplay.player.SinkChainMode.entries,
                label = { it.label },
                onSelect = { onSelectSinkChain(it.id) },
                icon = Icons.Rounded.Build,
                subtitle = when (mode) {
                    io.max.paperplay.player.SinkChainMode.FULL -> "Full: UsbAudioSink + EngineAudioSink (float on) + DefaultAudioSink. Swipe close and reopen app to apply"
                    io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_ON -> "No DSP (float on): EngineAudioSink forced inactive, sinkFloat=true. Swipe close and reopen app to apply"
                    io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_OFF -> "No DSP (float off): EngineAudioSink forced inactive, sinkFloat=false. Swipe close and reopen app to apply"
                    io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_OFF -> "Plain (float off): DefaultAudioSink only, sinkFloat=false (UsbAudioSink & EngineAudioSink bypassed). Restart app to apply"
                    io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_ON -> "Plain (float on): DefaultAudioSink only, sinkFloat=true (UsbAudioSink & EngineAudioSink bypassed). Restart app to apply"
                },
            )
        }

        if (onRunPlaybackTest != null) {
            SettingsRow(
                title = if (isTesting) "Running playback test..." else "Device playback test",
                icon = Icons.Rounded.PlayCircle,
                subtitle = testResultSubtitle ?: "Plays two real audio tracks through ExoPlayer renderer chain with loopback streamer, tests next/prev/seek/pause-next, and validates sink lifecycle",
                onClick = if (!isTesting) {
                    {
                        isTesting = true
                        scope.launch {
                            val res = onRunPlaybackTest.invoke()
                            testResultSubtitle = if (res.contains("ALL CHECKS PASSED")) {
                                "Test passed! (All checks OK: next/prev/seek/pause-next)"
                            } else {
                                "Test completed with issues. Check USB debug log."
                            }
                            isTesting = false
                        }
                    }
                } else null
            )
        }

        val st = status
        if (st == null || !st.isConnected) {
            val isTapToAllow = st?.statusReason?.contains("tap to allow", ignoreCase = true) == true
            SettingsRow(
                title = st?.deviceName ?: "No USB DAC connected",
                icon = if (st != null) Icons.Rounded.Usb else Icons.Rounded.UsbOff,
                subtitle = st?.statusReason ?: "Connect a USB audio DAC or dongle to stream bit-perfect audio.",
                onClick = if (isTapToAllow) { { onRequestPermission?.invoke() } } else null
            )
            return@Column
        }

        // Bit-perfect status banner
        val isBitPerfect = st.isBitPerfect
        val bannerBg = if (isBitPerfect) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
        val bannerIcon = if (isBitPerfect) Icons.Rounded.CheckCircle else Icons.Rounded.Info
        val bannerTitle = if (isBitPerfect) "Bit-perfect active" else "Not bit-perfect"
        val bannerTint = if (isBitPerfect) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bannerBg)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppIcon(bannerIcon, null, tint = bannerTint)
            Column(Modifier.weight(1f)) {
                Text(
                    text = bannerTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = bannerTint
                )
                Text(
                    text = st.statusReason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = bannerTint.copy(alpha = 0.85f)
                )
            }
        }

        // Device information
        val devTitle = st.deviceName ?: "USB DAC"
        val devSubtitle = buildList {
            st.vidPid?.let { add(it) }
            st.uacVersion?.let { add(it) }
            add(if (st.isHighSpeed) "High-Speed 480 Mbps" else "Full-Speed 12 Mbps")
        }.joinToString(" • ")

        SettingsRow(
            title = devTitle,
            icon = Icons.Rounded.Headphones,
            subtitle = devSubtitle
        )

        // Stream format
        val rateText = st.activeRate?.let { "$it Hz" } ?: "Standby"
        val depthText = st.activeBitDepth?.let { "$it-bit PCM" } ?: ""
        val chText = st.activeChannels?.let { "$it ch" } ?: ""
        val formatText = listOf(rateText, depthText, chText).filter { it.isNotBlank() }.joinToString(", ")

        SettingsRow(
            title = "Active format",
            icon = Icons.Rounded.GraphicEq,
            subtitle = formatText
        )

        // Endpoint mode
        val modeText = "${st.endpointMode ?: "Asynchronous"} isochronous transfers"
        SettingsRow(
            title = "Endpoint synchronization",
            icon = Icons.Rounded.Sync,
            subtitle = modeText
        )

        // Hardware volume
        SettingsRow(
            title = "Hardware volume",
            icon = Icons.Rounded.VolumeUp,
            subtitle = st.hardwareVolume ?: "Fixed 0 dB (bit-perfect)"
        )

        // Diagnostics
        val diagText = "Underruns: ${st.underruns} • URB errors: ${st.urbErrors} • Frames: ${st.framesConsumed}"
        SettingsRow(
            title = "Stream diagnostics",
            icon = Icons.Rounded.Speed,
            subtitle = diagText
        )

        // Dump USB PCM
        SettingsRow(
            title = "Dump USB PCM",
            icon = Icons.Rounded.SaveAlt,
            subtitle = pcmDumpStatus ?: "Tap to capture exact integer PCM handed to the USB ring (first 30 s)",
            onClick = onTriggerPcmDump
        )
    }
}

@Preview
@Composable
private fun UsbAudioPagePreview() {
    val sampleStatus = UsbExclusiveStatus(
        enabled = true,
        isConnected = true,
        deviceName = "FiiO KA11",
        vidPid = "0x2972:0x0047",
        uacVersion = "UAC 2.0",
        activeRate = 96000,
        activeBitDepth = 24,
        activeChannels = 2,
        endpointMode = "Asynchronous",
        isHighSpeed = true,
        hardwareVolume = "UAC Feature Unit (verified)",
        underruns = 0L,
        urbErrors = 0L,
        framesConsumed = 288000L,
        isBitPerfect = true,
        statusReason = "Bit-perfect direct usbfs isochronous streaming active"
    )

    MaterialTheme {
        UsbAudioContent(
            exclusiveOn = true,
            status = sampleStatus,
            onToggleExclusive = {}
        )
    }
}
