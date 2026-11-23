package io.max.paperplay.player.usb

sealed class FormatSelectionResult {
    data class Success(val candidate: UsbAudioAltSetting) : FormatSelectionResult()
    data class Failure(val reason: String) : FormatSelectionResult()
}

/**
 * Format negotiator for USB Audio Class endpoints.
 *
 * Implements strict format matching:
 * - Exact sample rate match (or within advertised continuous range)
 * - Container depth >= source bit depth (subslotSize * 8 >= sourceBitDepth)
 * - Bit resolution >= source bit depth
 * - Channel count match
 * - Prefers Asynchronous isochronous endpoints over Adaptive or Synchronous
 * - Prefers exact resolution match over padded representations
 * - Returns diagnostic reason string when no format fits
 */
object UsbFormatNegotiator {

    fun pickBestFormat(
        candidates: List<UsbAudioAltSetting>,
        sourceRate: Int,
        sourceBitDepth: Int,
        sourceChannels: Int,
    ): FormatSelectionResult {
        if (candidates.isEmpty()) {
            return FormatSelectionResult.Failure("No audio streaming alternate settings available on USB device")
        }

        // 1. Only consider OUT data streaming settings
        val outCandidates = candidates.filter { it.dataEndpoint != null && it.dataEndpoint.isOut }
        if (outCandidates.isEmpty()) {
            return FormatSelectionResult.Failure("USB device does not expose any audio playback (OUT) streaming endpoints")
        }

        // 2. Channel match
        val channelCandidates = outCandidates.filter { it.channelCount == sourceChannels }
        if (channelCandidates.isEmpty()) {
            val available = outCandidates.map { it.channelCount }.distinct().sorted()
            return FormatSelectionResult.Failure(
                "No alternate setting supports $sourceChannels channels (available channels: ${available.joinToString()})"
            )
        }

        // 3. Sample rate match
        // In UAC 1.0 (and some UAC 2.0 with pre-parsed rates), sampleRates lists supported frequencies.
        // If sampleRates is empty (pure UAC 2.0 where rates are queried at runtime from clock source),
        // we treat the descriptor candidate as rate-eligible.
        val rateCandidates = channelCandidates.filter { candidate ->
            when {
                candidate.sampleRates.isNotEmpty() -> sourceRate in candidate.sampleRates
                candidate.continuousRateRange != null -> {
                    val (min, max) = candidate.continuousRateRange
                    sourceRate in min..max
                }
                else -> true // UAC2 candidate, rate capability verified via clock control
            }
        }
        if (rateCandidates.isEmpty()) {
            val discreteRates = channelCandidates.flatMap { it.sampleRates }.distinct().sorted()
            val continuousRanges = channelCandidates.mapNotNull { it.continuousRateRange }
                .distinct()
                .map { "${it.first}..${it.second}" }
            val parts = discreteRates.map { it.toString() } + continuousRanges
            val rateText = if (parts.isNotEmpty()) parts.joinToString() else "none listed"
            return FormatSelectionResult.Failure(
                "No alternate setting supports sample rate $sourceRate Hz (available rates: $rateText)"
            )
        }

        // 4. Bit depth match
        val depthCandidates = rateCandidates.filter {
            (it.subslotSize * 8) >= sourceBitDepth && it.bitResolution >= sourceBitDepth
        }
        if (depthCandidates.isEmpty()) {
            val maxResolution = rateCandidates.maxOfOrNull { it.bitResolution } ?: 0
            val maxContainer = rateCandidates.maxOfOrNull { it.subslotSize * 8 } ?: 0
            return FormatSelectionResult.Failure(
                "Source bit depth $sourceBitDepth exceeds device bit resolution $maxResolution (container max: $maxContainer-bit)"
            )
        }

        // 5. Rank remaining eligible candidates
        val best = depthCandidates.minWithOrNull(
            compareBy<UsbAudioAltSetting> { candidate ->
                // Priority 1: Prefer Asynchronous > Adaptive > Synchronous
                when (candidate.dataEndpoint?.syncType) {
                    EndpointSyncType.ASYNCHRONOUS -> 0
                    EndpointSyncType.ADAPTIVE -> 1
                    EndpointSyncType.SYNCHRONOUS -> 2
                    else -> 3
                }
            }.thenBy { candidate ->
                // Priority 2: Exact resolution match first
                if (candidate.bitResolution == sourceBitDepth) 0 else 1
            }.thenBy { candidate ->
                // Priority 3: Tighter container size (e.g. 24-bit in 3-byte subslot preferred over 4-byte subslot)
                (candidate.subslotSize * 8) - sourceBitDepth
            }.thenBy { candidate ->
                // Priority 4: Prefer single discrete rate match (dedicated alt setting for this exact rate)
                // over multi-rate discrete alt setting over continuous range alt setting
                when {
                    candidate.continuousRateRange == null && candidate.sampleRates.size == 1 && candidate.sampleRates[0] == sourceRate -> 0
                    candidate.sampleRates.isNotEmpty() -> 1
                    candidate.continuousRateRange != null -> 2
                    else -> 1
                }
            }.thenBy { candidate ->
                // Priority 5: For async endpoints, prefer presence of explicit feedback endpoint
                if (candidate.dataEndpoint?.syncType == EndpointSyncType.ASYNCHRONOUS && candidate.feedbackEndpoint != null) 0 else 1
            }
        )

        return if (best != null) {
            FormatSelectionResult.Success(best)
        } else {
            FormatSelectionResult.Failure("No matching audio format candidate could be selected")
        }
    }
}

