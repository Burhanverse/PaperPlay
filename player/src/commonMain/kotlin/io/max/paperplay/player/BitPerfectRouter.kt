package io.max.paperplay.player

import kotlin.math.abs

/** Output pipeline selected by [BitPerfectRouter]. */
sealed class OutputRoute {
    data object Normal : OutputRoute()
    data object UsbUacDirect : OutputRoute()
    data object Api34BitPerfectMixer : OutputRoute()
    data class HardStop(val reason: String) : OutputRoute()
}

/** Snapshot of playback and DAC state evaluated purely by [BitPerfectRouter]. */
data class RouteInput(
    val mode: BitPerfectMode,
    val speed: Float = 1.0f,
    val usbDacAttached: Boolean = false,
    val usbPermissionGranted: Boolean = false,
    val dacReady: Boolean = false,
    val dacFailureReason: String? = null,
    val nonUsbRouteActive: Boolean = false,
    val isDoP: Boolean = false,
    val sourceFormat: PcmFormat? = null,
    val verifiedOutputFormat: PcmFormat? = null,
    val effects: List<String> = emptyList(),
    val api34MixerEnabled: Boolean = false,
    val api34MixerAvailable: Boolean = false,
    val deviceName: String? = null,
    val deviceId: String? = null,
    val protocolVersion: String? = null,
    val confirmedAlt: Int? = null,
    val hardwareVolume: String? = null,
)

/** Deterministic routing + telemetry verdict produced by [BitPerfectRouter]. */
data class RouteDecision(
    val route: OutputRoute,
    val status: BitPerfectStatus,
    val outputBitPerfect: Boolean,
    val allowPlayback: Boolean,
    val note: String,
    val transport: TransportInfo,
)

/**
 * Pure policy router deciding between Normal output, Direct USB UAC output,
 * API 34+ Bit-perfect mixer (optional dev flag), or DoP hard-stop.
 */
object BitPerfectRouter {
    fun decide(input: RouteInput): RouteDecision {
        val speedActive = abs(input.speed - 1.0f) > 0.001f

        // 1. Speed != 1.0x always exits bit-perfect cleanly and returns seamlessly at 1.0x.
        if (input.mode == BitPerfectMode.ON && speedActive) {
            val speedText = "${input.speed}x"
            val reason = "Not bit-perfect: speed $speedText"
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP requires 1.0x playback speed (currently $speedText)"),
                    status = BitPerfectStatus.Bypassed("Speed $speedText"),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = "DoP requires 1.0x playback speed.",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Bypassed("Speed $speedText"),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = reason,
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            }
        }

        // 2. User has Bit-perfect turned OFF.
        if (input.mode == BitPerfectMode.OFF) {
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP requires Bit-perfect mode with a connected USB DAC"),
                    status = BitPerfectStatus.Off,
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = "Turn on Bit-perfect with a USB DAC connected to play DoP.",
                    transport = TransportInfo(kind = TransportKind.NONE, verified = false),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Off,
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = "Turn on Bit-perfect with a USB DAC for untouched output.",
                    transport = TransportInfo(
                        kind = TransportKind.ANDROID_MIXER,
                        verified = false,
                        deviceName = input.deviceName,
                    ),
                )
            }
        }

        // 3. Bluetooth / wired jack / built-in speaker took over the route.
        if (input.nonUsbRouteActive) {
            val reason = "Not bit-perfect: routed to non-USB output"
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP halted: audio output routed away from USB DAC"),
                    status = BitPerfectStatus.Bypassed("Non-USB output active"),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = "DoP stopped because output switched away from USB DAC.",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Bypassed("Non-USB output active"),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = reason,
                    transport = TransportInfo(
                        kind = TransportKind.ANDROID_MIXER,
                        verified = false,
                        deviceName = input.deviceName,
                        unverifiedReason = reason,
                    ),
                )
            }
        }

        // 4. Optional Stage 5 API 34+ MIXER_BEHAVIOR_BIT_PERFECT (only when dev flag on and available without direct USB claim)
        if (!input.usbDacAttached && input.api34MixerEnabled && input.api34MixerAvailable && !input.isDoP) {
            val transport = TransportInfo(
                kind = TransportKind.ANDROID_BIT_PERFECT_MIXER,
                verified = input.sourceFormat != null && input.verifiedOutputFormat != null && input.effects.isEmpty(),
                deviceName = input.deviceName,
                deviceId = input.deviceId,
                confirmedRateHz = input.verifiedOutputFormat?.sampleRateHz,
                confirmedBitDepth = input.verifiedOutputFormat?.wireBitDepth,
                confirmedContainerBits = input.verifiedOutputFormat?.containerBits,
            )
            val verdict = BitPerfectEvaluator.evaluate(
                transport = transport,
                source = input.sourceFormat,
                output = input.verifiedOutputFormat,
                effects = input.effects,
                requestedOn = true,
            )
            return RouteDecision(
                route = OutputRoute.Api34BitPerfectMixer,
                status = if (verdict.bitPerfect && input.verifiedOutputFormat != null) {
                    BitPerfectStatus.Active(
                        transportLabel = TransportKind.ANDROID_BIT_PERFECT_MIXER.displayName,
                        sampleRateHz = input.verifiedOutputFormat.sampleRateHz,
                        bitDepth = input.verifiedOutputFormat.wireBitDepth,
                        deviceName = input.deviceName,
                    )
                } else {
                    BitPerfectStatus.Fallback(verdict.note)
                },
                outputBitPerfect = verdict.bitPerfect,
                allowPlayback = true,
                note = verdict.note,
                transport = transport,
            )
        }

        // 5. Waiting for USB DAC / permission / failure states.
        if (!input.usbDacAttached) {
            val reason = "Waiting for USB DAC"
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP requires a connected USB DAC"),
                    status = BitPerfectStatus.Standby(reason = reason),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = "Connect a DoP-capable USB DAC.",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Standby(reason = reason),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = "Android mixer active; connect a USB DAC for bit-perfect output.",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            }
        }

        if (!input.usbPermissionGranted) {
            val devName = input.deviceName ?: "USB DAC"
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP requires USB permission for $devName"),
                    status = BitPerfectStatus.PermissionRequired(devName, input.deviceId),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = "Grant USB permission for $devName to play DoP.",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.PermissionRequired(devName, input.deviceId),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = "USB DAC permission required ($devName).",
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false),
                )
            }
        }

        if (input.dacFailureReason != null) {
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP halted: ${input.dacFailureReason}"),
                    status = BitPerfectStatus.Fallback(input.dacFailureReason),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = input.dacFailureReason,
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = input.dacFailureReason),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Fallback(input.dacFailureReason),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = input.dacFailureReason,
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = input.dacFailureReason),
                )
            }
        }

        if (!input.dacReady) {
            val reason = "Initializing USB DAC"
            return if (input.isDoP) {
                RouteDecision(
                    route = OutputRoute.HardStop("DoP waiting for USB DAC initialization"),
                    status = BitPerfectStatus.Standby(input.deviceName, reason),
                    outputBitPerfect = false,
                    allowPlayback = false,
                    note = reason,
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            } else {
                RouteDecision(
                    route = OutputRoute.Normal,
                    status = BitPerfectStatus.Standby(input.deviceName, reason),
                    outputBitPerfect = false,
                    allowPlayback = true,
                    note = reason,
                    transport = TransportInfo(kind = TransportKind.ANDROID_MIXER, verified = false, unverifiedReason = reason),
                )
            }
        }

        // 6. Verified Direct USB UAC path.
        val outFmt = input.verifiedOutputFormat
        val transport = TransportInfo(
            kind = TransportKind.USB_UAC_DIRECT,
            verified = true,
            deviceName = input.deviceName,
            deviceId = input.deviceId,
            protocolVersion = input.protocolVersion,
            confirmedRateHz = outFmt?.sampleRateHz,
            confirmedBitDepth = outFmt?.wireBitDepth,
            confirmedContainerBits = outFmt?.containerBits,
            confirmedAlt = input.confirmedAlt,
            hardwareVolume = input.hardwareVolume,
        )
        val verdict = BitPerfectEvaluator.evaluate(
            transport = transport,
            source = input.sourceFormat,
            output = outFmt,
            effects = input.effects,
            requestedOn = true,
        )
        val status = if (verdict.bitPerfect && outFmt != null) {
            BitPerfectStatus.Active(
                transportLabel = TransportKind.USB_UAC_DIRECT.displayName,
                sampleRateHz = outFmt.sampleRateHz,
                bitDepth = outFmt.wireBitDepth,
                deviceName = input.deviceName,
            )
        } else {
            BitPerfectStatus.Fallback(verdict.note)
        }
        if (!verdict.bitPerfect && input.isDoP) {
            return RouteDecision(
                route = OutputRoute.HardStop(verdict.note),
                status = status,
                outputBitPerfect = false,
                allowPlayback = false,
                note = verdict.note,
                transport = transport.copy(verified = false, unverifiedReason = verdict.note),
            )
        }
        return RouteDecision(
            route = OutputRoute.UsbUacDirect,
            status = status,
            outputBitPerfect = verdict.bitPerfect,
            allowPlayback = true,
            note = verdict.note,
            transport = transport,
        )
    }
}
