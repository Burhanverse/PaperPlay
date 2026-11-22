package io.max.paperplay.player.usb

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.max.paperplay.player.DelegateSinkDiag
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import java.nio.ByteBuffer

/**
 * AudioSink wrapper for USB exclusive mode.
 *
 * When USB exclusive mode is enabled and a supported USB DAC is connected:
 * - Direct bit-perfect PCM streaming through [UsbSinkStateMachine] and [UsbAudioStreamer].
 * - Bypasses Android's audio mixer, DSP, volume, and sample rate conversion.
 * - Position tracking from the USB isochronous frames-consumed hardware clock.
 * - Gapless playback for consecutive tracks of matching format; silence-padded drain for rate switches.
 *
 * In DELEGATE mode (USB exclusive off, fallback, or non-USB output):
 * - Every AudioSink method forwards straight 1:1 to [normalSink] with zero USB state machine bookkeeping.
 * - Periodic diagnostics are logged once per second for system route and sink tracking.
 */
@OptIn(UnstableApi::class)
class UsbAudioSink(
    private val context: Context,
    private val normalSink: AudioSink,
    val testStateMachine: UsbSinkStateMachine? = null,
    val testAltSettings: List<UsbAudioAltSetting>? = null
) : ForwardingAudioSink(normalSink) {

    enum class ActiveSinkMode {
        DELEGATE,
        USB
    }

    @Volatile
    var activeMode: ActiveSinkMode = if (testStateMachine != null) ActiveSinkMode.USB else ActiveSinkMode.DELEGATE
        private set

    companion object {
        @Volatile
        var currentInstance: UsbAudioSink? = null
            private set
    }

    init {
        if (testStateMachine == null) {
            currentInstance = this
        }
    }

    private var lastConfig: AudioSink.AudioSinkConfig? = null
    private var lastConfiguredFormat: Format? = null
    private var lastHandleBufferLogMs = 0L
    private var lastGetPosLogMs = 0L
    private var lastPendingLogMs = 0L
    private var lastPendingState = false
    private var lastEndedState = false
    private var playerIsPlaying = false
    private var getPosLogCountAfterReset = 0
    private var logFirstBufferAfterReset = false
    @Volatile
    private var pendingReconfigure = false
    @Volatile
    private var pendingDisable = false
    @Volatile
    private var pendingDelegateConfigure = false
    private var lastDelegateDiagLogMs = 0L

    private fun playerStateDesc(): String {
        val state = PlaybackCore.playerPlaybackState
        val stateStr = when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            else -> "STATE_$state"
        }
        val pwr = PlaybackCore.playerPlayWhenReady
        return "state=$stateStr playWhenReady=$pwr"
    }

    private fun formatUsbStatus(action: String, underruns: Long = 0L): String {
        val confirmedRate = stateMachine.confirmedRate
        val confirmedAlt = stateMachine.confirmedAlt
        val rate = if (confirmedRate > 0) confirmedRate else stateMachine.currentRate
        val alt = if (confirmedAlt >= 0) confirmedAlt else (stateMachine.currentAltSetting?.altSetting ?: 0)
        val bitDepth = stateMachine.currentBitDepth
        val fmtTag = if (stateMachine.isDop) "DoP" else "PCM"
        val underrunStr = if (underruns > 0L) ", $underruns underruns" else ""
        return if (action == "streaming") {
            "bit-perfect active ($rate Hz, ${bitDepth}-bit $fmtTag, alt $alt$underrunStr)"
        } else {
            "USB: $action ($rate Hz, ${bitDepth}-bit $fmtTag, alt $alt$underrunStr)"
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        super.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {
                listener.onPositionDiscontinuity()
            }
            override fun onSilenceSkipped() {
                listener.onSilenceSkipped()
            }
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                listener.onPositionAdvancing(playoutStartSystemTimeMs)
            }
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                PlayLog.log("usb", "Delegate AudioSink onUnderrun: bufSize=$bufferSize bufMs=$bufferSizeMs elapsed=$elapsedSinceLastFeedMs")
                UsbRingLog.record(
                    "UsbAudioSink",
                    "Delegate onUnderrun: bufSize=$bufferSize bufMs=$bufferSizeMs elapsed=$elapsedSinceLastFeedMs",
                    UsbRingLog.CODE_SINK_UNDERRUN
                )
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            }
            override fun onAudioSinkError(audioSinkError: Exception) {
                PlayLog.log("usb", "Delegate AudioSink error: ${audioSinkError.message}")
                UsbRingLog.record(
                    "UsbAudioSink",
                    "Delegate error: ${audioSinkError.message}",
                    UsbRingLog.CODE_ERROR
                )
                listener.onAudioSinkError(audioSinkError)
            }
            override fun onAudioCapabilitiesChanged() {
                listener.onAudioCapabilitiesChanged()
            }
            override fun onOffloadBufferEmptying() {
                listener.onOffloadBufferEmptying()
            }
            override fun onOffloadBufferFull() {
                listener.onOffloadBufferFull()
            }
            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                listener.onAudioTrackInitialized(audioTrackConfig)
            }
            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                listener.onAudioTrackReleased(audioTrackConfig)
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                listener.onAudioSessionIdChanged(audioSessionId)
            }
        })
    }

    private fun logDelegateDiagnostics(sourceEnded: Boolean, pos: Long) {
        if (!DelegateSinkDiag.isEnabled) return
        DelegateSinkDiag.maybeSampleAndEmit(
            chainId = PlaybackCore.sinkChainMode.id,
            posUs = pos,
            pending = super.hasPendingData(),
            ended = super.isEnded(),
            sourceEnded = sourceEnded,
        )
    }

    private fun switchToUsbSink() {
        if (activeMode != ActiveSinkMode.USB) {
            PlayLog.log("usb", "UsbAudioSink: USB taking over from delegate; resetting DefaultAudioSink")
            UsbRingLog.record("UsbAudioSink", "USB taking over: resetting delegate AudioTrack", UsbRingLog.CODE_GENERIC)
            try {
                super.flush()
                super.reset()
            } catch (t: Throwable) {
                PlayLog.log("usb", "Error resetting delegate sink: ${t.message}")
            }
            pendingDelegateConfigure = false
            activeMode = ActiveSinkMode.USB
        }
    }

    /**
     * Completes a pending disable or delegate configure strictly on the ExoPlayer playback thread.
     * Never called from the main thread.
     */
    private fun applyPendingDelegateTransitionOnSinkThread() {
        if (pendingDisable || (!PlaybackCore.usbExclusiveMode && activeMode == ActiveSinkMode.USB)) {
            pendingDisable = false
            if (activeMode == ActiveSinkMode.USB) {
                PlayLog.log("usb", "UsbAudioSink: applying pending exclusive-off transition on playback thread")
                UsbRingLog.record("UsbAudioSink", "exclusive mode OFF applied on playback thread", UsbRingLog.CODE_GENERIC)
                try {
                    stateMachine.reset()
                    stateMachine.release()
                } catch (_: Throwable) {}
                activeMode = ActiveSinkMode.DELEGATE
                PlaybackCore.usbActive = false
                UsbAudioManager.instance?.onUsbReleased(closeConnection = true)
                pendingDelegateConfigure = true
            }
        }
        if (pendingDelegateConfigure && activeMode == ActiveSinkMode.DELEGATE) {
            pendingDelegateConfigure = false
            val config = lastConfig
            if (config != null) {
                try {
                    super.configure(config)
                } catch (t: Throwable) {
                    PlayLog.log("usb", "Error configuring delegate normalSink on mode toggle: ${t.message}")
                }
            }
        }
    }

    fun onExclusiveModeChanged(on: Boolean) {
        if (!on) {
            pendingReconfigure = false
            PlaybackCore.usbStatus = "USB exclusive: off"
            PlaybackCore.usbActive = false
            if (activeMode == ActiveSinkMode.USB) {
                if (PlaybackCore.playerIsPlaying) {
                    // Defer teardown + delegate configure to ExoPlayer's playback thread (never call normalSink.configure on main thread)
                    pendingDisable = true
                } else {
                    PlayLog.log("usb", "UsbAudioSink: exclusive mode toggled OFF while idle; releasing USB state machine")
                    UsbRingLog.record("UsbAudioSink", "exclusive mode toggled OFF: switching to DELEGATE", UsbRingLog.CODE_GENERIC)
                    try {
                        stateMachine.reset()
                        stateMachine.release()
                    } catch (_: Throwable) {}
                    activeMode = ActiveSinkMode.DELEGATE
                    UsbAudioManager.instance?.onUsbReleased(closeConnection = true)
                    // Defer normal sink reconfiguration to the playback thread on next sink call
                    pendingDelegateConfigure = (lastConfig != null)
                }
            }
        } else {
            pendingDisable = false
            PlaybackCore.usbStatus = "waiting for DAC"
            triggerReconfiguration()
        }
    }

    private fun switchToDelegateFallback(reason: String, cause: Throwable? = null) {
        PlayLog.log("usb", "switchToDelegateFallback: $reason")
        UsbRingLog.record("UsbAudioSink", "switchToDelegateFallback: $reason", UsbRingLog.CODE_SINK_FALLBACK)
        val wasUsb = activeMode == ActiveSinkMode.USB
        if (wasUsb) {
            try {
                stateMachine.reset()
                stateMachine.release()
            } catch (_: Throwable) {}
            activeMode = ActiveSinkMode.DELEGATE
            UsbRingLog.record("UsbAudioSink", "Delegate taking over: reset USB state machine", UsbRingLog.CODE_GENERIC)
        }
        PlaybackCore.usbActive = false
        PlaybackCore.usbStatus = "Fallback: $reason"
        UsbAudioProbe.exportFallbackLog(context, reason)
        if (wasUsb) {
            UsbAudioManager.instance?.onUsbReleased(closeConnection = !PlaybackCore.usbExclusiveMode)
        }
        lastConfig?.let {
            try {
                super.configure(it)
            } catch (e: Throwable) {
                PlayLog.log("usb", "Error configuring fallback sink: ${e.message}")
                UsbRingLog.record("UsbAudioSink", "Error configuring fallback sink: ${e.message}", UsbRingLog.CODE_ERROR)
            }
        }
    }

    private val stateMachine = (testStateMachine ?: UsbSinkStateMachine(
        bridgeFactory = { candidate, rate, isDop ->
            UsbAudioManager.instance?.testBridgeFactory?.invoke(candidate, rate, isDop)
                ?: UsbAudioManager.instance?.createStreamer(candidate, rate, isDop)
        },
        onFallbackTriggered = { reason ->
            switchToDelegateFallback(reason)
        }
    )).apply {
        onWriteHook = { buf, off, len ->
            if (UsbPcmDumper.isDumping) {
                UsbPcmDumper.onBytesWritten(buf, off, len)
            }
        }
    }

    override fun supportsFormat(format: Format): Boolean {
        if (!PlaybackCore.usbExclusiveMode && testStateMachine == null) {
            return super.supportsFormat(format)
        }
        val support = UsbFormatSupport.getFormatSupport(
            isUsbExclusiveMode = true,
            sampleMimeType = format.sampleMimeType,
            pcmEncoding = format.pcmEncoding,
            channelCount = format.channelCount,
            sampleRate = format.sampleRate
        )
        return if (support >= 0) {
            support == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            super.supportsFormat(format)
        }
    }

    override fun getFormatSupport(format: Format): Int {
        if (!PlaybackCore.usbExclusiveMode && testStateMachine == null) {
            return super.getFormatSupport(format)
        }
        val support = UsbFormatSupport.getFormatSupport(
            isUsbExclusiveMode = true,
            sampleMimeType = format.sampleMimeType,
            pcmEncoding = format.pcmEncoding,
            channelCount = format.channelCount,
            sampleRate = format.sampleRate
        )
        return if (support >= 0) support else super.getFormatSupport(format)
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        pendingReconfigure = false
        pendingDisable = false
        pendingDelegateConfigure = false
        lastConfig = config
        val f = config.format
        lastConfiguredFormat = f

        if (!PlaybackCore.usbExclusiveMode) {
            if (activeMode == ActiveSinkMode.USB) {
                try {
                    stateMachine.reset()
                    stateMachine.release()
                } catch (_: Throwable) {}
                activeMode = ActiveSinkMode.DELEGATE
                UsbAudioManager.instance?.onUsbReleased(closeConnection = true)
            }
            PlaybackCore.usbActive = false
            PlaybackCore.usbStatus = "USB exclusive: off"
            if (DelegateSinkDiag.isEnabled) DelegateSinkDiag.onConfigure(config.format)
            super.configure(config)
            return
        }

        val rate = f.sampleRate
        val channels = f.channelCount
        val enc = f.pcmEncoding
        val encName = when (enc) {
            C.ENCODING_PCM_16BIT -> "PCM_16BIT"
            C.ENCODING_PCM_24BIT -> "PCM_24BIT"
            C.ENCODING_PCM_32BIT -> "PCM_32BIT"
            C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            else -> "ENC_$enc"
        }
        UsbRingLog.record(
            "UsbAudioSink",
            "configure [${playerStateDesc()}]: mime=${f.sampleMimeType} rate=$rate ch=$channels enc=$encName ($enc)",
            UsbRingLog.CODE_SINK_CONFIGURE,
            rate.toLong(),
            ((channels shl 16) or (enc and 0xFFFF)).toLong()
        )
        PlayLog.log("usb", "UsbAudioSink.configure [${playerStateDesc()}]: rate=$rate ch=$channels enc=$encName ($enc) mime=${f.sampleMimeType}")

        // In USB exclusive mode, reject any non-raw format (FLAC, etc. must have been decoded by MediaCodec)
        if (f.sampleMimeType != null && f.sampleMimeType != MimeTypes.AUDIO_RAW) {
            PlayLog.log("usb", "Non-raw format passed to UsbAudioSink.configure (${f.sampleMimeType}); delegating to normal sink")
            UsbRingLog.record("UsbAudioSink", "Reject non-raw mime: ${f.sampleMimeType}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("Reject non-raw mime: ${f.sampleMimeType}")
            return
        }

        val usbManager = UsbAudioManager.instance
        val usbState = usbManager?.state?.value
        val testAlts = testAltSettings ?: usbManager?.testAltSettings

        if (testStateMachine == null && usbState !is UsbAudioState.Connected && testAlts == null) {
            val statusDesc = when (usbState) {
                is UsbAudioState.Attached -> if (usbState.hasPermission) "USB DAC attached, connecting & probing" else "USB DAC found - tap to allow"
                null, is UsbAudioState.NoDevice -> "waiting for DAC"
                is UsbAudioState.Error -> usbState.message
            }
            PlaybackCore.usbStatus = statusDesc
            PlayLog.log("usb", "configure: $statusDesc (state=$usbState); delegating to normal sink pending connection")
            UsbRingLog.record("UsbAudioSink", "configure: $statusDesc", UsbRingLog.CODE_SINK_CONFIGURE)
            switchToDelegateFallback(statusDesc)
            return
        }

        val isFloat = f.pcmEncoding == C.ENCODING_PCM_FLOAT
        val bitDepth = when (f.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        if (testStateMachine == null && testAlts == null) {
            usbManager?.ensureNativeCapabilitiesProbed()
        }
        val probedState = usbManager?.state?.value
        val availableAlts = testAlts ?: (probedState as? UsbAudioState.Connected)?.description?.altSettings ?: emptyList()

        try {
            val configured = stateMachine.configure(
                formatRate = rate,
                formatBitDepth = bitDepth,
                formatChannels = channels,
                availableAltSettings = availableAlts,
                isFloat = isFloat,
                sampleMimeType = f.sampleMimeType
            )

            if (configured) {
                switchToUsbSink()
                stateMachine.hasHardwareVolume = usbManager?.hasHardwareVolume == true
                PlaybackCore.usbActive = true
                val alt = stateMachine.currentAltSetting?.altSetting ?: 0
                val configuredDepth = stateMachine.currentBitDepth
                val fmtTag = if (stateMachine.isDop) "DoP" else "PCM"
                PlaybackCore.usbStatus = formatUsbStatus("configured")
                PlayLog.log("usb", "UsbAudioSink prepared ($rate Hz, $configuredDepth-bit $fmtTag, $channels ch, alt=$alt)")
                UsbRingLog.record(
                    "UsbAudioSink",
                    "stateMachine configured: $rate Hz, $configuredDepth-bit $fmtTag, alt $alt, $channels ch",
                    UsbRingLog.CODE_SINK_CONFIGURE
                )
                if (UsbPcmDumper.isEnabled) {
                    UsbPcmDumper.onStreamConfigured(
                        cacheDir = context.cacheDir,
                        sampleRate = rate,
                        bitDepth = configuredDepth,
                        channels = channels,
                        subslotSize = stateMachine.currentSubslotSize
                    )
                }
                if (playerIsPlaying) {
                    PlayLog.log("usb", "UsbAudioSink.configure: player is playing, auto-starting native streamer via stateMachine.play()")
                    stateMachine.play()
                }
                return
            } else {
                val reason = "Format unsupported by USB DAC ($rate Hz, ${bitDepth}-bit, $channels ch)"
                PlayLog.log("usb", "$reason; falling back to normal sink")
                UsbRingLog.record("UsbAudioSink", reason, UsbRingLog.CODE_ERROR)
                switchToDelegateFallback(reason)
                return
            }
        } catch (t: Throwable) {
            val reason = "USB configuration rejected: ${t.message ?: t::class.simpleName}\n${t.stackTraceToString()}"
            PlayLog.log("usb", "$reason; falling back to normal sink")
            UsbRingLog.record("UsbAudioSink", reason, UsbRingLog.CODE_ERROR)
            switchToDelegateFallback(reason, t)
            return
        }
    }

    fun triggerReconfiguration() {
        if (!PlaybackCore.usbExclusiveMode) return
        PlayLog.log("usb", "UsbAudioSink: scheduling sink reconfiguration following DAC probe/connect")
        UsbRingLog.record("UsbAudioSink", "triggerReconfiguration: scheduled", UsbRingLog.CODE_GENERIC)
        pendingReconfigure = true
        PlaybackCore.requestReconfiguration()
    }

    /**
     * Marks reconfiguration pending so the ExoPlayer playback thread picks it up safely.
     * Never mutates DefaultAudioSink or stateMachine from the main thread.
     */
    fun applyPendingReconfigure(): Boolean {
        if (PlaybackCore.usbExclusiveMode && activeMode != ActiveSinkMode.USB && (lastConfiguredFormat != null || lastConfig != null)) {
            pendingReconfigure = true
            return true
        }
        return false
    }

    private fun reconfigureUsbStateMachine(): Boolean {
        val format = lastConfiguredFormat ?: lastConfig?.format ?: return false
        val usbManager = UsbAudioManager.instance ?: return false
        val usbState = usbManager.state.value
        val testAlts = usbManager.testAltSettings
        if (usbState !is UsbAudioState.Connected && testAlts == null) return false
        if (!PlaybackCore.usbExclusiveMode) return false

        val rate = format.sampleRate
        val channels = format.channelCount
        val enc = format.pcmEncoding
        val isFloat = enc == C.ENCODING_PCM_FLOAT
        val bitDepth = when (enc) {
            C.ENCODING_PCM_16BIT -> 16
            C.ENCODING_PCM_24BIT -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
            else -> 16
        }

        if (testAlts == null) {
            usbManager.ensureNativeCapabilitiesProbed()
        }
        val probedState = usbManager.state.value
        val availableAlts = testAlts ?: (probedState as? UsbAudioState.Connected)?.description?.altSettings ?: return false

        PlayLog.log("usb", "UsbAudioSink: auto-reconfiguring stateMachine with last format ($rate Hz, $bitDepth-bit, $channels ch)")
        UsbRingLog.record("UsbAudioSink", "auto-reconfigure stateMachine: $rate Hz, $bitDepth-bit, $channels ch", UsbRingLog.CODE_SINK_CONFIGURE)

        return try {
            val configured = stateMachine.configure(
                formatRate = rate,
                formatBitDepth = bitDepth,
                formatChannels = channels,
                availableAltSettings = availableAlts,
                isFloat = isFloat,
                sampleMimeType = format.sampleMimeType
            )
            if (configured) {
                switchToUsbSink()
                stateMachine.hasHardwareVolume = usbManager.hasHardwareVolume
                PlaybackCore.usbActive = true
                val alt = stateMachine.currentAltSetting?.altSetting ?: 0
                val configuredDepth = stateMachine.currentBitDepth
                val fmtTag = if (stateMachine.isDop) "DoP" else "PCM"
                PlaybackCore.usbStatus = formatUsbStatus("configured")
                if (UsbPcmDumper.isEnabled) {
                    UsbPcmDumper.onStreamConfigured(
                        cacheDir = context.cacheDir,
                        sampleRate = rate,
                        bitDepth = configuredDepth,
                        channels = channels,
                        subslotSize = stateMachine.currentSubslotSize
                    )
                }
                if (playerIsPlaying) {
                    stateMachine.play()
                }
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            PlayLog.log("usb", "auto-reconfigure stateMachine failed: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "auto-reconfigure stateMachine failed: ${t.message}\n${t.stackTraceToString()}", UsbRingLog.CODE_ERROR)
            false
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        applyPendingDelegateTransitionOnSinkThread()
        if (activeMode == ActiveSinkMode.DELEGATE) {
            if (PlaybackCore.usbExclusiveMode && pendingReconfigure) {
                pendingReconfigure = false
                if (reconfigureUsbStateMachine()) {
                    // Switched to USB mode, fall through to USB handling
                } else if (DelegateSinkDiag.isEnabled) {
                    val before = buffer.remaining()
                    val ok = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    DelegateSinkDiag.recordHandleBuffer(before, (before - buffer.remaining()).coerceAtLeast(0), ok)
                    return ok
                } else {
                    return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                }
            } else if (DelegateSinkDiag.isEnabled) {
                val before = buffer.remaining()
                val ok = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                DelegateSinkDiag.recordHandleBuffer(before, (before - buffer.remaining()).coerceAtLeast(0), ok)
                return ok
            } else {
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
        }

        // ActiveSinkMode.USB handling (zero per-buffer String allocations or locks):
        val bridge = stateMachine.activeBridge
        if (playerIsPlaying && bridge != null) {
            if (!bridge.isRunning) {
                runCatching { stateMachine.play() }
            } else if (bridge.isPaused) {
                runCatching { bridge.resume() }
            }
        }
        if (logFirstBufferAfterReset) {
            logFirstBufferAfterReset = false
            UsbRingLog.record(
                "UsbAudioSink",
                "first handleBuffer post-reset [${playerStateDesc()}]: rem=${buffer.remaining()} pts=$presentationTimeUs us state=${stateMachine.state} isPaused=${stateMachine.activeBridge?.isPaused}",
                UsbRingLog.CODE_SINK_HANDLE_BUFFER
            )
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastHandleBufferLogMs >= 5000L) {
                lastHandleBufferLogMs = now
                UsbRingLog.recordAudio(
                    UsbRingLog.CODE_SINK_HANDLE_BUFFER,
                    buffer.remaining().toLong(),
                    presentationTimeUs
                )
            }
            val done = stateMachine.handleBuffer(buffer, presentationTimeUs)
            if (stateMachine.state == UsbSinkState.FALLBACK) {
                switchToDelegateFallback(stateMachine.fallbackReason ?: "USB buffer failure")
                return runCatching { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
            }
            return done
        } catch (t: Throwable) {
            val reason = "handleBuffer failed in USB mode: ${t.message}\n${t.stackTraceToString()}"
            PlayLog.log("usb", reason)
            UsbRingLog.record("UsbAudioSink", reason, UsbRingLog.CODE_ERROR)
            switchToDelegateFallback(reason, t)
            return runCatching { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }.getOrDefault(false)
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            val pos = super.getCurrentPositionUs(sourceEnded)
            if (DelegateSinkDiag.isEnabled) {
                logDelegateDiagnostics(sourceEnded, pos)
            }
            return pos
        }

        // ActiveSinkMode.USB:
        try {
            val pos = stateMachine.getCurrentPositionUs(sourceEnded)
            if (pos == AudioSink.CURRENT_POSITION_NOT_SET) {
                if (getPosLogCountAfterReset > 0) {
                    getPosLogCountAfterReset--
                    UsbRingLog.record(
                        "UsbAudioSink",
                        "getCurrentPositionUs post-reset [$getPosLogCountAfterReset left]: NOT_SET sourceEnded=$sourceEnded",
                        UsbRingLog.CODE_SINK_GET_POS
                    )
                }
                return AudioSink.CURRENT_POSITION_NOT_SET
            }
            if (getPosLogCountAfterReset > 0) {
                getPosLogCountAfterReset--
                UsbRingLog.record(
                    "UsbAudioSink",
                    "getCurrentPositionUs post-reset [$getPosLogCountAfterReset left]: pos=$pos us (${pos / 1000} ms) sourceEnded=$sourceEnded",
                    UsbRingLog.CODE_SINK_GET_POS
                )
            }
            if (stateMachine.state == UsbSinkState.FALLBACK) {
                switchToDelegateFallback(stateMachine.fallbackReason ?: "USB position error")
                return runCatching { super.getCurrentPositionUs(sourceEnded) }.getOrDefault(AudioSink.CURRENT_POSITION_NOT_SET)
            }
            val now = System.currentTimeMillis()
            if (now - lastGetPosLogMs >= 5000L) {
                lastGetPosLogMs = now
                val stats = stateMachine.activeBridge?.getStats()
                if (stats != null) {
                    UsbRingLog.recordAudio(UsbRingLog.CODE_PERIODIC_STATS, stats.framesConsumed, stats.underruns)
                    if (stateMachine.isActive) {
                        PlaybackCore.usbActive = true
                        PlaybackCore.usbStatus = formatUsbStatus("streaming", stats.underruns)
                    }
                }
                UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_GET_POS, pos, if (sourceEnded) 1L else 0L)
            }
            return pos
        } catch (t: Throwable) {
            val reason = "getCurrentPositionUs failed in USB mode: ${t.message}"
            PlayLog.log("usb", reason)
            UsbRingLog.record("UsbAudioSink", reason, UsbRingLog.CODE_ERROR)
            switchToDelegateFallback(reason, t)
            return runCatching { super.getCurrentPositionUs(sourceEnded) }.getOrDefault(AudioSink.CURRENT_POSITION_NOT_SET)
        }
    }

    override fun play() {
        playerIsPlaying = true
        applyPendingDelegateTransitionOnSinkThread()
        if (activeMode == ActiveSinkMode.DELEGATE) {
            if (PlaybackCore.usbExclusiveMode && pendingReconfigure) {
                pendingReconfigure = false
                if (reconfigureUsbStateMachine()) {
                    try {
                        stateMachine.play()
                        PlaybackCore.usbActive = true
                        PlaybackCore.usbStatus = formatUsbStatus("streaming")
                        return
                    } catch (t: Throwable) {
                        PlayLog.log("usb", "play failed after reconfigure: ${t.message}; falling back")
                        switchToDelegateFallback("play failed: ${t.message}", t)
                    }
                }
            }
            runCatching { super.play() }
            return
        }

        UsbRingLog.record("UsbAudioSink", "play [${playerStateDesc()}] activeMode=$activeMode", UsbRingLog.CODE_SINK_PLAY)
        try {
            stateMachine.play()
            PlaybackCore.usbActive = true
            PlaybackCore.usbStatus = formatUsbStatus("streaming")
        } catch (t: Throwable) {
            PlayLog.log("usb", "play failed in USB mode: ${t.message}; falling back")
            UsbRingLog.record("UsbAudioSink", "play error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("play failed: ${t.message}", t)
            runCatching { super.play() }
        }
    }

    override fun pause() {
        playerIsPlaying = false
        applyPendingDelegateTransitionOnSinkThread()
        if (activeMode == ActiveSinkMode.DELEGATE) {
            super.pause()
            return
        }

        UsbRingLog.record("UsbAudioSink", "pause [${playerStateDesc()}] activeMode=$activeMode", UsbRingLog.CODE_SINK_PAUSE)
        try {
            stateMachine.pause()
            PlaybackCore.usbStatus = formatUsbStatus("paused")
        } catch (t: Throwable) {
            PlayLog.log("usb", "pause failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "pause error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("pause failed: ${t.message}", t)
            runCatching { super.pause() }
        }
    }

    override fun flush() {
        applyPendingDelegateTransitionOnSinkThread()
        if (activeMode == ActiveSinkMode.DELEGATE) {
            if (DelegateSinkDiag.isEnabled) DelegateSinkDiag.onResetOrFlush()
            runCatching { super.flush() }
            return
        }

        getPosLogCountAfterReset = 5
        logFirstBufferAfterReset = true
        UsbRingLog.record("UsbAudioSink", "flush [${playerStateDesc()}] activeMode=$activeMode", UsbRingLog.CODE_SINK_FLUSH)
        try {
            stateMachine.flush(preservePlayRequested = playerIsPlaying)
        } catch (t: Throwable) {
            PlayLog.log("usb", "flush failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "flush error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("flush failed: ${t.message}", t)
            runCatching { super.flush() }
        }
    }

    override fun reset() {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            if (DelegateSinkDiag.isEnabled) DelegateSinkDiag.onResetOrFlush()
            super.reset()
            return
        }

        getPosLogCountAfterReset = 5
        logFirstBufferAfterReset = true
        UsbRingLog.record("UsbAudioSink", "reset [${playerStateDesc()}] activeMode=$activeMode", UsbRingLog.CODE_GENERIC)
        try {
            stateMachine.reset(preservePlayRequested = playerIsPlaying)
        } catch (t: Throwable) {
            PlayLog.log("usb", "reset failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "reset error: ${t.message}", UsbRingLog.CODE_ERROR)
        }
        if (stateMachine.isActive) {
            PlaybackCore.usbActive = true
        }
    }

    override fun release() {
        UsbRingLog.record("UsbAudioSink", "release", UsbRingLog.CODE_GENERIC)
        playerIsPlaying = false
        lastConfiguredFormat = null
        lastConfig = null
        if (activeMode == ActiveSinkMode.USB || stateMachine.isActive) {
            try {
                stateMachine.release()
            } catch (t: Throwable) {
                PlayLog.log("usb", "release failed in USB mode: ${t.message}")
            }
        }
        PlaybackCore.usbActive = false
        PlaybackCore.usbStatus = null
        activeMode = ActiveSinkMode.DELEGATE
        DelegateSinkDiag.onResetOrFlush()
        super.release()
    }

    fun onDeviceDetached() {
        PlayLog.log("usb", "UsbAudioSink: USB DAC detached, releasing stateMachine")
        playerIsPlaying = false
        lastConfiguredFormat = null
        lastConfig = null
        switchToDelegateFallback("No USB DAC connected")
    }

    override fun playToEndOfStream() {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            super.playToEndOfStream()
            return
        }

        try {
            stateMachine.playToEndOfStream()
        } catch (t: Throwable) {
            PlayLog.log("usb", "playToEndOfStream failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "playToEndOfStream error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("playToEndOfStream failed: ${t.message}", t)
            runCatching { super.playToEndOfStream() }
        }
    }

    override fun isEnded(): Boolean {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            return super.isEnded()
        }

        try {
            val ended = stateMachine.isEnded()
            if (ended != lastEndedState) {
                lastEndedState = ended
                UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_IS_ENDED, if (ended) 1L else 0L)
            }
            return ended
        } catch (t: Throwable) {
            PlayLog.log("usb", "isEnded failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "isEnded error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("isEnded failed: ${t.message}", t)
            return runCatching { super.isEnded() }.getOrDefault(false)
        }
    }

    override fun hasPendingData(): Boolean {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            return super.hasPendingData()
        }

        try {
            val pending = stateMachine.hasPendingData()
            val now = System.currentTimeMillis()
            if (pending != lastPendingState || now - lastPendingLogMs >= 5000L) {
                lastPendingState = pending
                lastPendingLogMs = now
                val fill = stateMachine.activeBridge?.getStats()?.ringFillBytes ?: 0L
                UsbRingLog.recordAudio(
                    UsbRingLog.CODE_SINK_PENDING_DATA,
                    if (pending) 1L else 0L,
                    fill
                )
            }
            return pending
        } catch (t: Throwable) {
            PlayLog.log("usb", "hasPendingData failed in USB mode: ${t.message}")
            UsbRingLog.record("UsbAudioSink", "hasPendingData error: ${t.message}", UsbRingLog.CODE_ERROR)
            switchToDelegateFallback("hasPendingData failed: ${t.message}", t)
            return runCatching { super.hasPendingData() }.getOrDefault(false)
        }
    }

    override fun setVolume(volume: Float) {
        if (activeMode == ActiveSinkMode.DELEGATE) {
            super.setVolume(volume)
            return
        }
        // Bit-perfect USB output: no software scaling
    }
}
