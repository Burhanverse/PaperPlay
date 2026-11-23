package io.max.paperplay.player.usb

/**
 * Graph topology resolvers for UAC 1.0 / 2.0 descriptors (single Kotlin source of truth).
 * Follows entity connections through InputTerminal, FeatureUnit, OutputTerminal,
 * ClockSelector, and ClockMultiplier while explicitly rejecting capture-only paths.
 */
fun UsbAudioDeviceDescription.resolveClockEntityId(
    entityId: Int,
    visited: MutableSet<Int> = mutableSetOf(),
): Int? {
    if (entityId <= 0 || !visited.add(entityId)) return null
    if (clockSources.any { it.id == entityId }) return entityId
    clockMultipliers.firstOrNull { it.id == entityId }?.let { cm ->
        return resolveClockEntityId(cm.sourceClockId, visited)
    }
    clockSelectors.firstOrNull { it.id == entityId }?.let { sel ->
        for (pin in sel.inputClockIds) {
            resolveClockEntityId(pin, visited)?.let { return it }
        }
    }
    return null
}

private fun UsbAudioDeviceDescription.isCaptureEntity(fu: UsbFeatureUnit): Boolean {
    // Walk upstream from FU.sourceId through FeatureUnits, SelectorUnits, and MixerUnits toward an InputTerminal;
    // if it originates from a non-USB input (mic/line), it's capture.
    val queue = ArrayDeque<Int>()
    val seen = mutableSetOf<Int>()
    queue.addLast(fu.sourceId)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (cur <= 0 || !seen.add(cur)) continue
        val it = inputTerminals.firstOrNull { it.id == cur }
        if (it != null) {
            if (!it.isUsbStreaming) return true
            continue
        }
        featureUnits.firstOrNull { it.id == cur }?.let { queue.addLast(it.sourceId) }
        selectorUnits.firstOrNull { it.id == cur }?.let { su -> su.sourceIds.forEach { queue.addLast(it) } }
        mixerUnits.firstOrNull { it.id == cur }?.let { mu -> mu.sourceIds.forEach { queue.addLast(it) } }
    }
    // Also check if an OutputTerminal fed (directly or via Selector/Mixer) by this FU is a USB Streaming output (0x0101 = capture to host).
    return outputTerminals.any { ot ->
        !ot.isPhysicalOutput && (ot.sourceId == fu.id ||
            selectorUnits.any { su -> su.id == ot.sourceId && fu.id in su.sourceIds } ||
            mixerUnits.any { mu -> mu.id == ot.sourceId && fu.id in mu.sourceIds })
    }
}

private fun UsbAudioDeviceDescription.findDownstreamOutputTerminal(startEntityId: Int): UsbOutputTerminal? {
    if (startEntityId <= 0) return null
    val queue = ArrayDeque<Int>()
    val seen = mutableSetOf<Int>()
    queue.addLast(startEntityId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        outputTerminals.firstOrNull { it.sourceId == id && it.isPhysicalOutput }?.let { return it }
        for (fu in featureUnits) {
            if (fu.sourceId == id) queue.addLast(fu.id)
        }
        for (su in selectorUnits) {
            if (id in su.sourceIds) queue.addLast(su.id)
        }
        for (mu in mixerUnits) {
            if (id in mu.sourceIds) queue.addLast(mu.id)
        }
    }
    return null
}

fun UsbAudioDeviceDescription.resolvePlaybackClockSourceId(alt: UsbAudioAltSetting): Int? {
    if (uacVersion != UacVersion.UAC_2_0) return null

    // 1. InputTerminal linked to this streaming interface
    inputTerminals.firstOrNull { it.id == alt.terminalLink }?.let { inTerm ->
        if (inTerm.clockSourceId > 0) {
            resolveClockEntityId(inTerm.clockSourceId)?.let { return it }
        }
    }

    // 2. Downstream physical OutputTerminal reachable from this streaming path, or any physical speaker/headphone output terminal
    val outTerm = findDownstreamOutputTerminal(alt.terminalLink)
        ?: outputTerminals.firstOrNull { (it.terminalType and 0xFF00) == 0x0300 }
        ?: outputTerminals.firstOrNull { it.isPhysicalOutput }
    if (outTerm != null && outTerm.clockSourceId > 0) {
        resolveClockEntityId(outTerm.clockSourceId)?.let { return it }
    }

    // 3. Fallback: first programmable / freq-settable ClockSource (skip obvious capture clock if playback outTerm didn't match)
    return clockSources.firstOrNull { it.supportsFrequencySet || it.isProgrammable }?.id
        ?: clockSources.firstOrNull()?.id
}

fun UsbAudioDeviceDescription.resolvePlaybackFeatureUnit(alt: UsbAudioAltSetting?): UsbFeatureUnit? {
    if (alt != null) {
        // 1. Direct downstream from playback InputTerminal (skip capture FUs)
        featureUnits.firstOrNull { it.sourceId == alt.terminalLink && !isCaptureEntity(it) }?.let { return it }

        // 2. Downstream reachable OutputTerminal -> walk upstream to its FeatureUnit
        findDownstreamOutputTerminal(alt.terminalLink)?.let { outTerm ->
            featureUnits.firstOrNull { it.id == outTerm.sourceId && !isCaptureEntity(it) }?.let { return it }
        }
    }

    // 3. Direct upstream from physical speaker/headphone OutputTerminal (0x03xx)
    val physOut = outputTerminals.firstOrNull { (it.terminalType and 0xFF00) == 0x0300 }
        ?: outputTerminals.firstOrNull { it.isPhysicalOutput }
    if (physOut != null) {
        featureUnits.firstOrNull { it.id == physOut.sourceId && !isCaptureEntity(it) }?.let { return it }
    }

    // 4. First non-capture FeatureUnit with volume (never return capture mic FU!)
    return featureUnits.firstOrNull { !isCaptureEntity(it) && (it.hasMasterVolume || it.hasChannelVolume) }
        ?: featureUnits.firstOrNull { !isCaptureEntity(it) }
}
