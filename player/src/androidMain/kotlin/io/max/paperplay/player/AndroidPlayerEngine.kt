package io.max.paperplay.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Call once (e.g. Activity/Application onCreate) before [createPlayerEngine].
 * [upstream] is the hook for non-stream URIs; Telegram files should use [QueueItem.source] instead.
 */
fun initPlayer(context: Context, upstream: DataSource.Factory? = null) {
    PlaybackCore.appContext = context.applicationContext
    PlaybackCore.upstream = upstream
    val savedPref = PlaybackCore.loadPersistedUsbExclusiveMode(context.applicationContext)
    val savedChain = PlaybackCore.loadPersistedSinkChain(context.applicationContext)
    val initialFlag = PlaybackCore.usbExclusiveMode
    PlaybackCore.usbExclusiveMode = savedPref
    PlayLog.log("usb", "initPlayer: usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}")
    if (initialFlag != savedPref) {
        PlayLog.log("usb", "WARNING: usbExclusiveMode initial mismatch in initPlayer: flag=$initialFlag pref=$savedPref")
    }
    io.max.paperplay.player.usb.UsbPowerTelemetry.start(context.applicationContext)
    io.max.paperplay.player.usb.UsbRingLog.record("InitPlayer", "usbExclusiveMode initial = $savedPref (pref=$savedPref), sinkChain = ${savedChain.id}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
    if (PlaybackCore.useUnifiedBitPerfect) {
        val um = io.max.paperplay.player.usb.UnifiedUsbDacManager.instance
            ?: io.max.paperplay.player.usb.UnifiedUsbDacManager(context.applicationContext).also {
                io.max.paperplay.player.usb.UnifiedUsbDacManager.instance = it
            }
        um.onStatusChanged = { AndroidUsbDacBackend.refreshStatus() }
        um.start()
    } else {
        val usb = io.max.paperplay.player.usb.UsbAudioManager.instance
            ?: io.max.paperplay.player.usb.UsbAudioManager(context.applicationContext).also {
                io.max.paperplay.player.usb.UsbAudioManager.instance = it
            }
        usb.start()
    }
    AndroidUsbDacBackend.refreshStatus()
}

internal interface AndroidEngineHooks {
    fun onExclusiveToggled(changed: Boolean)
    fun onSinkChainChanged(mode: SinkChainMode)
    fun onRebuildRequested()
}

internal object AndroidUsbDacBackend : io.max.paperplay.player.usb.UsbDacController {
    private val _usbExclusiveStatus = MutableStateFlow<io.max.paperplay.player.usb.UsbExclusiveStatus?>(null)
    override val usbExclusiveStatus: StateFlow<io.max.paperplay.player.usb.UsbExclusiveStatus?> = _usbExclusiveStatus

    private val _bitPerfectStatus = MutableStateFlow<BitPerfectStatus>(BitPerfectStatus.Off)
    val bitPerfectStatus: StateFlow<BitPerfectStatus>
        get() = _bitPerfectStatus

    private val noDiscoveryHint = MutableStateFlow<io.max.paperplay.player.usb.DiscoveredUsbDacHint?>(null)
    override val pendingDiscoveryHint: StateFlow<io.max.paperplay.player.usb.DiscoveredUsbDacHint?>
        get() = io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.pendingHint ?: noDiscoveryHint

    override fun dismissDiscoveryHint(vidPid: String) {
        io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.dismissHint(vidPid)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stackSwitchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "UsbStackSwitch").apply { isDaemon = true }
    }

    override fun setUseUnifiedBitPerfect(enabled: Boolean) {
        val ctx = PlaybackCore.appContext ?: return
        val changed = PlaybackCore.useUnifiedBitPerfect != enabled
        PlaybackCore.savePersistedUseUnifiedBitPerfect(ctx, enabled)
        PlayLog.log("usb", "Runtime switch dev_use_unified_bit_perfect=$enabled (changed=$changed, exclusive=${PlaybackCore.usbExclusiveMode})")
        if (!changed) return

        // Pause active player before tearing down USB stacks so ExoPlayer stops feeding buffers cleanly.
        PlaybackCore.activePlayer?.let { p ->
            Handler(p.applicationLooper).post {
                if (p.playWhenReady) p.pause()
            }
        }
        stackSwitchExecutor.execute {
            if (!enabled) {
                io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.stop()
                val usbReady = java.util.concurrent.CountDownLatch(1)
                var usbMgr: io.max.paperplay.player.usb.UsbAudioManager? = null
                mainHandler.post {
                    try {
                        val usb = io.max.paperplay.player.usb.UsbAudioManager.instance
                            ?: io.max.paperplay.player.usb.UsbAudioManager(ctx).also {
                                io.max.paperplay.player.usb.UsbAudioManager.instance = it
                            }
                        usb.start()
                        usbMgr = usb
                    } finally {
                        usbReady.countDown()
                    }
                }
                usbReady.await(2, java.util.concurrent.TimeUnit.SECONDS)
                if (PlaybackCore.usbExclusiveMode) {
                    usbMgr?.onUsbExclusiveModeChanged(true)
                }
            } else {
                io.max.paperplay.player.usb.UsbAudioSink.currentInstance?.onExclusiveModeChanged(false)
                io.max.paperplay.player.usb.UsbAudioManager.instance?.onUsbExclusiveModeChanged(false)
                val unifiedReady = java.util.concurrent.CountDownLatch(1)
                mainHandler.post {
                    try {
                        val um = io.max.paperplay.player.usb.UnifiedUsbDacManager.instance
                            ?: io.max.paperplay.player.usb.UnifiedUsbDacManager(ctx).also {
                                io.max.paperplay.player.usb.UnifiedUsbDacManager.instance = it
                            }
                        um.onStatusChanged = { refreshStatus() }
                        um.start()
                        um.onModeChanged(if (PlaybackCore.usbExclusiveMode) BitPerfectMode.ON else BitPerfectMode.OFF)
                    } finally {
                        unifiedReady.countDown()
                    }
                }
                unifiedReady.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            mainHandler.post {
                refreshStatus()
                val h = hooks
                if (h != null) h.onRebuildRequested() else PlaybackCore.onRebuildPlayerForBitPerfect?.invoke()
            }
        }
    }

    override fun getUseUnifiedBitPerfect(): Boolean = PlaybackCore.useUnifiedBitPerfect

    override fun setDevApi34Mixer(enabled: Boolean) {
        PlaybackCore.devApi34MixerEnabled = enabled
        val ctx = PlaybackCore.appContext ?: return
        ctx.getSharedPreferences("paperplay_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("dev_api34_bitperfect_mixer", enabled).apply()
        if (!enabled) {
            io.max.paperplay.player.usb.Api34BitPerfectMixer.clear(ctx)
        }
        if (PlaybackCore.useUnifiedBitPerfect) {
            val h = hooks
            if (h != null) h.onRebuildRequested() else PlaybackCore.onRebuildPlayerForBitPerfect?.invoke()
        }
    }

    @kotlin.concurrent.Volatile
    private var hooks: AndroidEngineHooks? = null

    fun attachHooks(engineHooks: AndroidEngineHooks) {
        hooks = engineHooks
    }

    fun detachHooks(engineHooks: AndroidEngineHooks) {
        if (hooks === engineHooks) hooks = null
    }

    override fun setUsbExclusive(on: Boolean) {
        val ctx = PlaybackCore.appContext ?: return
        val changed = PlaybackCore.usbExclusiveMode != on
        PlaybackCore.savePersistedUsbExclusiveMode(ctx, on)
        PlatformBitPerfectPrefs.save(if (on) BitPerfectMode.ON else BitPerfectMode.OFF)
        if (PlaybackCore.useUnifiedBitPerfect) {
            io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.onModeChanged(
                if (on) BitPerfectMode.ON else BitPerfectMode.OFF
            )
            if (on && !PlaybackCore.playerIsPlaying) {
                io.max.paperplay.player.usb.UsbAudioManager.onRequestRecordAudioPermission?.invoke()
            }
            if (!on) PlaybackCore.usbActive = false
            refreshStatus()
            hooks?.onExclusiveToggled(changed)
            return
        }
        if (!on) {
            PlaybackCore.usbActive = false
            // 1. Tell sink first so native session restores DAC volume/mute while interface 0 is still claimed and releases cleanly
            io.max.paperplay.player.usb.UsbAudioSink.currentInstance?.onExclusiveModeChanged(false)
            // 2. If playback is not actively running on USB, close connection immediately; otherwise the sink thread closes on release
            if (!PlaybackCore.playerIsPlaying) {
                io.max.paperplay.player.usb.UsbAudioManager.instance?.onUsbExclusiveModeChanged(false)
            }
        } else {
            io.max.paperplay.player.usb.UsbAudioManager.onRequestRecordAudioPermission?.invoke()
            // 1. Load native USB library and open device (Kotlin raw descriptors; native clock probe runs lazily on configure)
            io.max.paperplay.player.usb.UsbAudioManager.instance?.onUsbExclusiveModeChanged(true)
            // 2. Notify sink to reconfigure into USB mode
            io.max.paperplay.player.usb.UsbAudioSink.currentInstance?.onExclusiveModeChanged(true)
        }
        refreshStatus()
        hooks?.onExclusiveToggled(changed)
    }

    override fun setSinkChain(mode: String) {
        val ctx = PlaybackCore.appContext ?: return
        val parsed = SinkChainMode.fromId(mode)
        PlaybackCore.savePersistedSinkChain(ctx, parsed)
        PlayLog.log("usb", "Sink chain changed to ${parsed.id}")
        io.max.paperplay.player.usb.UsbRingLog.record("AndroidPlayerEngine", "sinkChain set to ${parsed.id}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
        hooks?.onSinkChainChanged(parsed)
    }

    override fun getSinkChain(): String =
        PlaybackCore.appContext?.let { PlaybackCore.loadPersistedSinkChain(it).id } ?: SinkChainMode.FULL.id

    override suspend fun runUsbProbe(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ctx = PlaybackCore.appContext ?: return@withContext "App context unavailable."
        val usb = io.max.paperplay.player.usb.UsbAudioManager.instance
            ?: io.max.paperplay.player.usb.UsbAudioManager(ctx).also { io.max.paperplay.player.usb.UsbAudioManager.instance = it }
        io.max.paperplay.player.usb.UsbAudioProbe.run(ctx, usb)
    }

    override suspend fun runUsbPlaybackTest(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val ctx = PlaybackCore.appContext ?: return@withContext "App context unavailable."
        io.max.paperplay.player.usb.UsbDevicePlaybackTest.run(ctx)
    }

    override fun requestUsbPermission() {
        if (PlaybackCore.useUnifiedBitPerfect) {
            io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.scanClassOnly()
            return
        }
        val usb = io.max.paperplay.player.usb.UsbAudioManager.instance ?: return
        val dev = usb.enumerateAudioDevices().firstOrNull() ?: return
        usb.requestPermission(dev)
    }

    override suspend fun exportUsbDebugLog(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ctx = PlaybackCore.appContext ?: return@withContext ""
        val file = io.max.paperplay.player.usb.UsbAudioProbe.exportDebugLogFile(ctx)
        file.absolutePath
    }

    override fun triggerUsbPcmDump(enable: Boolean): String {
        val ctx = PlaybackCore.appContext ?: return "App context unavailable."
        return if (enable) {
            io.max.paperplay.player.usb.UsbPcmDumper.enable(ctx.cacheDir)
            "Armed: dump will record next 30 s of USB audio to cache"
        } else {
            io.max.paperplay.player.usb.UsbPcmDumper.disable()
            "Dump cancelled"
        }
    }

    override fun getUsbPcmDumpStatus(): String = io.max.paperplay.player.usb.UsbPcmDumper.status

    override fun getUsbPcmDumpPath(): String? = io.max.paperplay.player.usb.UsbPcmDumper.dumpFilePath

    override fun getLastUsbFallbackLogPath(): String? {
        val ctx = PlaybackCore.appContext ?: return null
        val file = io.max.paperplay.player.usb.UsbAudioProbe.getLastFallbackLog(ctx)
        return file?.takeIf { it.exists() }?.absolutePath
    }

    fun refreshStatus() {
        val usbManager = io.max.paperplay.player.usb.UsbAudioManager.instance
        val state = usbManager?.state?.value
        val isExclusive = PlaybackCore.usbExclusiveMode
        val unifiedSink = if (PlaybackCore.useUnifiedBitPerfect) io.max.paperplay.player.usb.UnifiedUsbAudioSink.currentInstance else null
        val legacySink = if (!PlaybackCore.useUnifiedBitPerfect) io.max.paperplay.player.usb.UsbAudioSink.currentInstance else null
        val isActiveModeUsb = unifiedSink?.isUsbModeActive == true ||
            legacySink?.activeMode == io.max.paperplay.player.usb.UsbAudioSink.ActiveSinkMode.USB
        val stats = unifiedSink?.getStatsSnapshot() ?: usbManager?.getStreamerStats()
        val isStreamConfirmed = isActiveModeUsb && stats?.isRunning == true && (stats.sampleRate > 0 || stats.framesConsumed > 0L)
        val isUsbDirectEq = isExclusive && isStreamConfirmed &&
            PlaybackCore.allowEqInUsbExclusive &&
            PlaybackCore.parametricEnabled &&
            PlaybackCore.parametricEq?.hasActiveShaping() == true
        val isBitPerfect = isExclusive && isStreamConfirmed && !isUsbDirectEq

        val status = if (!isExclusive) {
            val dev = (state as? io.max.paperplay.player.usb.UsbAudioState.Connected)?.device
            val desc = (state as? io.max.paperplay.player.usb.UsbAudioState.Connected)?.description
            val currentAlt = desc?.altSettings?.firstOrNull()
            io.max.paperplay.player.usb.UsbExclusiveStatus(
                enabled = false,
                isConnected = state is io.max.paperplay.player.usb.UsbAudioState.Connected,
                deviceName = dev?.productName ?: "USB DAC",
                vidPid = dev?.let { "0x${it.vendorId.toString(16).padStart(4, '0')}:0x${it.productId.toString(16).padStart(4, '0')}" },
                uacVersion = when (desc?.uacVersion) {
                    io.max.paperplay.player.usb.UacVersion.UAC_1_0 -> "UAC 1.0"
                    io.max.paperplay.player.usb.UacVersion.UAC_2_0 -> "UAC 2.0"
                    else -> null
                },
                activeRate = currentAlt?.sampleRates?.firstOrNull(),
                activeBitDepth = currentAlt?.bitResolution,
                activeChannels = currentAlt?.channelCount,
                isBitPerfect = false,
                statusReason = "USB exclusive: off"
            )
        } else {
            when (state) {
                is io.max.paperplay.player.usb.UsbAudioState.Connected -> {
                    val dev = state.device
                    val desc = state.description
                    val streamer = usbManager?.streamer
                    val confirmedAlt = unifiedSink?.confirmedAlt?.takeIf { it >= 0 }
                        ?: streamer?.confirmedAlt?.takeIf { it >= 0 }
                    val activeAlt = confirmedAlt?.let { altNum ->
                        desc.altSettings.firstOrNull { it.altSetting == altNum }
                    } ?: desc.altSettings.firstOrNull()
                    io.max.paperplay.player.usb.UsbExclusiveStatus(
                        enabled = true,
                        isConnected = true,
                        deviceName = dev.productName ?: "USB DAC",
                        vidPid = "0x${dev.vendorId.toString(16).padStart(4, '0')}:0x${dev.productId.toString(16).padStart(4, '0')}",
                        uacVersion = when (desc.uacVersion) {
                            io.max.paperplay.player.usb.UacVersion.UAC_1_0 -> "UAC 1.0"
                            io.max.paperplay.player.usb.UacVersion.UAC_2_0 -> "UAC 2.0"
                            else -> "Unknown"
                        },
                        activeRate = unifiedSink?.confirmedRateHz?.takeIf { it > 0 }
                            ?: streamer?.confirmedRate?.takeIf { it > 0 }
                            ?: stats?.sampleRate?.takeIf { it > 0 }
                            ?: activeAlt?.sampleRates?.firstOrNull(),
                        activeBitDepth = unifiedSink?.confirmedBitDepth?.takeIf { it > 0 && isActiveModeUsb }
                            ?: streamer?.bitDepth?.takeIf { it > 0 && isActiveModeUsb }
                            ?: activeAlt?.bitResolution,
                        activeChannels = streamer?.channels?.takeIf { it > 0 && isActiveModeUsb } ?: activeAlt?.channelCount,
                        endpointMode = activeAlt?.dataEndpoint?.syncType?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Asynchronous",
                        isHighSpeed = desc.uacVersion == io.max.paperplay.player.usb.UacVersion.UAC_2_0,
                        hardwareVolume = if (desc.hasHardwareVolume) "UAC Feature Unit (verified)" else "Fixed 0 dB (bit-perfect)",
                        underruns = stats?.underruns ?: 0L,
                        urbErrors = stats?.urbErrors ?: 0L,
                        framesConsumed = stats?.framesConsumed ?: 0L,
                        isBitPerfect = isBitPerfect,
                        statusReason = if (isBitPerfect) {
                            "bit-perfect active"
                        } else if (isUsbDirectEq) {
                            "USB Direct + EQ (native rate, Android mixer bypassed)"
                        } else {
                            val coordReason = if (PlaybackCore.useUnifiedBitPerfect) {
                                when (val cs = io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.status?.value) {
                                    is BitPerfectStatus.Standby -> cs.reason
                                    is BitPerfectStatus.Bypassed -> cs.reason
                                    is BitPerfectStatus.Fallback -> cs.reason
                                    is BitPerfectStatus.Unsupported -> cs.reason
                                    is BitPerfectStatus.PermissionRequired -> "USB DAC found - tap to allow"
                                    is BitPerfectStatus.DirectWithEq -> "USB Direct + EQ (native rate, Android mixer bypassed)"
                                    is BitPerfectStatus.Active -> PlaybackCore.usbStatus ?: "bit-perfect active"
                                    BitPerfectStatus.Off, null -> null
                                }
                            } else {
                                null
                            }
                            coordReason
                                ?: PlaybackCore.usbStatus?.takeIf { !it.contains("streaming", ignoreCase = true) }
                                ?: "Standby: ready to stream"
                        }
                    )
                }
                is io.max.paperplay.player.usb.UsbAudioState.Attached -> {
                    io.max.paperplay.player.usb.UsbExclusiveStatus(
                        enabled = true,
                        isConnected = false,
                        deviceName = state.device.productName ?: "USB Device",
                        vidPid = "0x${state.device.vendorId.toString(16).padStart(4, '0')}:0x${state.device.productId.toString(16).padStart(4, '0')}",
                        isBitPerfect = false,
                        statusReason = if (state.hasPermission) "Initializing DAC..." else "USB DAC found - tap to allow"
                    )
                }
                is io.max.paperplay.player.usb.UsbAudioState.Error -> {
                    io.max.paperplay.player.usb.UsbExclusiveStatus(
                        enabled = true,
                        isConnected = false,
                        deviceName = state.device?.productName ?: "USB Device",
                        vidPid = state.device?.let { "0x${it.vendorId.toString(16).padStart(4, '0')}:0x${it.productId.toString(16).padStart(4, '0')}" } ?: "",
                        isBitPerfect = false,
                        statusReason = state.message
                    )
                }
                else -> {
                    val standbyReason = if (PlaybackCore.useUnifiedBitPerfect) {
                        when (val cs = io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.status?.value) {
                            is BitPerfectStatus.Standby -> cs.reason
                            is BitPerfectStatus.Bypassed -> cs.reason
                            is BitPerfectStatus.Fallback -> cs.reason
                            is BitPerfectStatus.PermissionRequired -> "USB DAC found - tap to allow"
                            else -> "waiting for DAC"
                        }
                    } else {
                        "waiting for DAC"
                    }
                    io.max.paperplay.player.usb.UsbExclusiveStatus(
                        enabled = true,
                        isConnected = false,
                        isBitPerfect = false,
                        statusReason = standbyReason
                    )
                }
            }
        }
        _usbExclusiveStatus.value = status
        val coordStatus = if (PlaybackCore.useUnifiedBitPerfect) {
            io.max.paperplay.player.usb.UnifiedUsbDacManager.instance?.status?.value
        } else {
            null
        }
        _bitPerfectStatus.value = when {
            !isExclusive -> BitPerfectStatus.Off
            isUsbDirectEq -> BitPerfectStatus.DirectWithEq(
                transportLabel = TransportKind.USB_UAC_DIRECT.displayName,
                sampleRateHz = status.activeRate ?: 0,
                bitDepth = status.activeBitDepth ?: 0,
                deviceName = status.deviceName?.takeIf { it.isNotBlank() },
            )
            isBitPerfect -> BitPerfectStatus.Active(
                transportLabel = TransportKind.USB_UAC_DIRECT.displayName,
                sampleRateHz = status.activeRate ?: 0,
                bitDepth = status.activeBitDepth ?: 0,
                deviceName = status.deviceName?.takeIf { it.isNotBlank() },
            )
            coordStatus is BitPerfectStatus.Bypassed -> coordStatus
            coordStatus is BitPerfectStatus.PermissionRequired -> coordStatus
            coordStatus is BitPerfectStatus.Fallback -> coordStatus
            coordStatus is BitPerfectStatus.Unsupported -> coordStatus
            state is io.max.paperplay.player.usb.UsbAudioState.Attached && !state.hasPermission ->
                BitPerfectStatus.PermissionRequired(
                    deviceName = state.device.productName ?: "USB Device",
                    deviceId = status.vidPid?.takeIf { it.isNotBlank() },
                )
            state is io.max.paperplay.player.usb.UsbAudioState.Error ->
                BitPerfectStatus.Fallback(state.message)
            else -> BitPerfectStatus.Standby(
                deviceName = status.deviceName?.takeIf { it.isNotBlank() && state is io.max.paperplay.player.usb.UsbAudioState.Connected },
                reason = status.statusReason,
            )
        }
    }
}

/** Main-thread Media3 [MediaController] client driving [PlaybackService]'s shared session. */
class AndroidPlayerEngine : PlayerEngine {
    private val context = requireNotNull(PlaybackCore.appContext) { "Call initPlayer(context) before createPlayerEngine()" }
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { runnable ->
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run() else handler.post(runnable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var fullQueue: List<QueueItem> = emptyList()
    private var windowStart = 0
    private var windowEnd = 0
    private var pendingSlideIndex = -1
    private var released = false
    private var failed = false
    private var tickerRunning = false
    private var infoTicks = 0
    private var volumeLevel = 1f

    private val _state = MutableStateFlow(PlayerState.Idle)
    private val _positionMs = MutableStateFlow(0L)
    private val _durationMs = MutableStateFlow(0L)
    private val _bufferedMs = MutableStateFlow(0L)
    private val _currentIndex = MutableStateFlow(-1)
    override val state: StateFlow<PlayerState> = _state
    override val positionMs: StateFlow<Long> = _positionMs
    override val durationMs: StateFlow<Long> = _durationMs
    override val bufferedMs: StateFlow<Long> = _bufferedMs
    override val currentIndex: StateFlow<Int> = _currentIndex
    private val _info = MutableStateFlow<OutputInfo?>(null)
    override val outputInfo: StateFlow<OutputInfo?> = _info
    override val supportsBitPerfect: Boolean = true
    override val bitPerfectStatus: StateFlow<BitPerfectStatus> get() = AndroidUsbDacBackend.bitPerfectStatus
    override val dacController: io.max.paperplay.player.usb.UsbDacController get() = AndroidUsbDacBackend

    private val engineHooks = object : AndroidEngineHooks {
        override fun onExclusiveToggled(changed: Boolean) {
            onMain {
                val mc = controller?.takeIf { it.isConnected }
                if (PlaybackCore.useUnifiedBitPerfect && changed) {
                    if (mc != null) {
                        mc.sendCustomCommand(PlaybackCore.CMD_REBUILD_FOR_BIT_PERFECT, Bundle.EMPTY)
                    } else {
                        PlaybackCore.onRebuildPlayerForBitPerfect?.invoke()
                    }
                } else if (changed && mc != null && (mc.isPlaying || mc.playWhenReady)) {
                    val pos = mc.currentPosition
                    mc.seekTo(pos)
                }
                mc?.let { publishInfo(it) }
            }
        }

        override fun onSinkChainChanged(mode: SinkChainMode) {
            onMain {
                val mc = controller?.takeIf { it.isConnected } ?: return@onMain
                mc.sendCustomCommand(PlaybackCore.CMD_SET_SINK_CHAIN, Bundle().apply { putString(PlaybackCore.EXTRA_SINK_CHAIN, mode.id) })
                publishInfo(mc)
            }
        }

        override fun onRebuildRequested() {
            onMain {
                val mc = controller?.takeIf { it.isConnected }
                if (mc != null) {
                    mc.sendCustomCommand(PlaybackCore.CMD_REBUILD_FOR_BIT_PERFECT, Bundle.EMPTY)
                } else {
                    PlaybackCore.onRebuildPlayerForBitPerfect?.invoke()
                }
                mc?.let { publishInfo(it) }
            }
        }
    }

    override val bypassSoftwareFade: Boolean
        get() = PlaybackCore.usbActive || PlaybackCore.usbExclusiveMode

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId ?: "none"
            val reasonStr = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                else -> "REASON_$reason"
            }
            io.max.paperplay.player.usb.UsbRingLog.record("Player", "TRACK_SWITCH mediaId=$id reason=$reasonStr", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (player.playerError == null && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)) {
                failed = false
            }
            if (events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_DEVICE_VOLUME_CHANGED)) publishInfo(player)
            sync()
        }

        override fun onPlayerError(error: PlaybackException) {
            failed = true
            sync()
        }

        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (max > 0) {
                    volumeLevel = (volume.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                }
            }
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(mc: MediaController) {
            if (controller === mc) {
                mc.removeListener(playerListener)
                controller = null
                controllerFuture = null
                stopTicker()
                _state.value = PlayerState.Idle
                _positionMs.value = 0L
                _durationMs.value = 0L
                _bufferedMs.value = 0L
                _currentIndex.value = -1
            }
        }
    }

    /** Set by the UI while a full player view needs smooth progress; otherwise the position ticks at 500 ms. */
    @Volatile var fastTicks: Boolean = false

    private fun pollMs() = if (fastTicks) FAST_POLL_MS else POSITION_POLL_MS

    private val ticker = object : Runnable {
        override fun run() {
            val mc = controller
            if (mc == null || !mc.isConnected || released || !shouldTick(mc)) {
                tickerRunning = false
                return
            }
            val tick = ++infoTicks
            if (tick % 3 == 0 && (PlaybackCore.engineActive || PlaybackCore.usbActive)) publishInfo(mc)
            if (PlaybackCore.usbActive || (PlaybackCore.usbExclusiveMode && tick % 3 == 0)) AndroidUsbDacBackend.refreshStatus()
            _positionMs.value = mc.currentPosition.coerceAtLeast(0L)
            _bufferedMs.value = mc.bufferedPosition.coerceAtLeast(0L)
            handler.postDelayed(this, pollMs())
        }
    }

    private val slideRunnable = Runnable {
        if (PlaybackCore.devDirectTestActive) return@Runnable
        val idx = pendingSlideIndex
        pendingSlideIndex = -1
        val mc = controller ?: return@Runnable
        if (!released && mc.isConnected && idx in fullQueue.indices) {
            slideWindow(mc, idx)
        }
    }

    init {
        val savedPref = PlaybackCore.loadPersistedUsbExclusiveMode(context)
        val initialFlag = PlaybackCore.usbExclusiveMode
        if (initialFlag != savedPref) {
            PlayLog.log("usb", "WARNING: usbExclusiveMode initial mismatch in AndroidPlayerEngine: flag=$initialFlag pref=$savedPref")
            PlaybackCore.usbExclusiveMode = savedPref
        }
        PlayLog.log("usb", "AndroidPlayerEngine init: usbExclusiveMode = ${PlaybackCore.usbExclusiveMode} (pref=$savedPref)")
        io.max.paperplay.player.usb.UsbRingLog.record("AndroidPlayerEngine", "usbExclusiveMode initial = ${PlaybackCore.usbExclusiveMode} (pref=$savedPref)", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
        AndroidUsbDacBackend.attachHooks(engineHooks)
        AndroidUsbDacBackend.refreshStatus()
        onMain { connect() }
        PlaybackCore.onUsbReconfigurationRequested = {
            onMain {
                val mc = controller?.takeIf { it.isConnected }
                io.max.paperplay.player.usb.UsbAudioSink.currentInstance?.applyPendingReconfigure()
                if (mc != null) {
                    if (mc.playbackState == Player.STATE_IDLE && mc.playerError != null) {
                        mc.prepare()
                    }
                    if (PlaybackCore.usbExclusiveMode && !PlaybackCore.usbActive) {
                        if (mc.playbackState != Player.STATE_IDLE && mc.mediaItemCount > 0) {
                            val pos = mc.currentPosition
                            mc.seekTo(pos)
                        }
                    }
                }
                AndroidUsbDacBackend.refreshStatus()
                mc?.let { publishInfo(it) }
            }
        }
        scope.launch {
            io.max.paperplay.player.usb.UsbAudioManager.instance?.state?.collect { state ->
                AndroidUsbDacBackend.refreshStatus()
                val mc = controller?.takeIf { it.isConnected }
                if (mc != null) {
                    if (state is io.max.paperplay.player.usb.UsbAudioState.Connected && PlaybackCore.usbExclusiveMode && !PlaybackCore.usbActive) {
                        io.max.paperplay.player.usb.UsbAudioSink.currentInstance?.applyPendingReconfigure()
                        if (mc.playbackState == Player.STATE_IDLE && mc.playerError != null) {
                            mc.prepare()
                        }
                        if (mc.playbackState != Player.STATE_IDLE && mc.mediaItemCount > 0) {
                            val pos = mc.currentPosition
                            mc.seekTo(pos)
                        }
                    }
                    publishInfo(mc)
                }
            }
        }
    }

    private fun shouldTick(p: Player): Boolean =
        p.isPlaying || p.playbackState == Player.STATE_BUFFERING

    private fun updateTicker(p: Player?) {
        val want = p != null && !released && shouldTick(p)
        if (want && !tickerRunning) {
            tickerRunning = true
            handler.postDelayed(ticker, pollMs())
        } else if (!want && tickerRunning) {
            stopTicker()
        }
    }

    private fun stopTicker() {
        tickerRunning = false
        handler.removeCallbacks(ticker)
    }

    private fun connect() {
        if (released || controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setApplicationLooper(Looper.getMainLooper())
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) {
                pending.clear()
                return@addListener
            }
            val mc = runCatching { future.get() }.getOrNull()
            if (mc == null) {
                if (controllerFuture === future) controllerFuture = null
                failed = true
                sync()
                pending.clear()
                return@addListener
            }
            controller = mc
            mc.addListener(playerListener)
            if (volumeLevel < 1f) mc.volume = volumeLevel
            PlaybackCore.eqGains?.let { sendEqualizer(mc, it) }
            if (PlaybackCore.normalize) sendNormalize(mc, true)
            if (PlaybackCore.speed != 1f) mc.setPlaybackSpeed(PlaybackCore.speed)
            while (pending.isNotEmpty()) {
                pending.removeFirst().invoke(mc)
            }
            sync()
        }, mainExecutor)
    }

    private fun currentFullIndex(mc: MediaController): Int {
        if (mc.mediaItemCount == 0 || fullQueue.isEmpty()) return -1
        val id = mc.currentMediaItem?.mediaId
        val parsed = id?.substringBefore(':')?.toIntOrNull()
        if (parsed != null && parsed in fullQueue.indices) return parsed
        return (windowStart + mc.currentMediaItemIndex).coerceIn(fullQueue.indices)
    }

    private fun scheduleSlide(fullIndex: Int) {
        pendingSlideIndex = fullIndex
        handler.removeCallbacks(slideRunnable)
        handler.post(slideRunnable)
    }

    private fun sync() {
        val mc = controller
        if (mc == null || !mc.isConnected) {
            _state.value = if (failed) PlayerState.Error else PlayerState.Idle
            _durationMs.value = 0L
            _bufferedMs.value = 0L
            _currentIndex.value = -1
            _positionMs.value = 0L
            updateTicker(null)
            return
        }
        val hasError = failed || mc.playerError != null
        _state.value = when {
            hasError -> PlayerState.Error
            mc.mediaItemCount == 0 -> PlayerState.Idle
            else -> when (mc.playbackState) {
                Player.STATE_BUFFERING -> PlayerState.Loading
                Player.STATE_READY -> if (mc.playWhenReady) PlayerState.Playing else PlayerState.Paused
                Player.STATE_ENDED -> PlayerState.Ended
                else -> PlayerState.Idle
            }
        }
        _durationMs.value = mc.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        _bufferedMs.value = mc.bufferedPosition.coerceAtLeast(0L)
        val fullIdx = currentFullIndex(mc)
        _currentIndex.value = fullIdx
        _positionMs.value = mc.currentPosition.coerceAtLeast(0L)
        if (!PlaybackCore.devDirectTestActive && fullIdx in fullQueue.indices) scheduleSlide(fullIdx)
        updateTicker(mc)
    }

    /** MediaController may only be used from its application (main) thread; callers may be on any thread. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun withController(block: (MediaController) -> Unit) = onMain {
        if (released) return@onMain
        val mc = controller
        if (mc != null && mc.isConnected) {
            block(mc)
        } else {
            pending.addLast(block)
            connect()
        }
    }

    override fun setQueue(items: List<QueueItem>, startIndex: Int) = withController { mc ->
        setQueueNow(mc, items, startIndex)
    }

    private fun streamKey(item: QueueItem): String = "stream://${item.uri}"

    private fun toMediaItem(index: Int, item: QueueItem): MediaItem {
        val src = item.source
        val uri = if (src != null) streamKey(item) else item.uri
        return MediaItem.Builder()
            .setMediaId("$index:${item.uri}")
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setArtworkUri(parseArtworkUri(item.artworkUri))
                    .build(),
            )
            .build()
    }

    private fun registerRange(start: Int, endExclusive: Int) {
        for (i in start until endExclusive) {
            val item = fullQueue[i]
            val src = item.source ?: continue
            PlaybackCore.sources[streamKey(item)] = src
        }
    }

    private fun retainActiveWindowSources() {
        val keep = HashSet<String>()
        for (i in windowStart until windowEnd) {
            val item = fullQueue[i]
            if (item.source != null) keep.add(streamKey(item))
        }
        PlaybackCore.sources.keys.retainAll(keep)
    }

    /**
     * Keeps only `[centerIndex - 1, centerIndex + 1]` loaded in [MediaController], sliding smoothly
     * as playback moves so queues of any size stay lightweight and never exceed Binder IPC limits.
     */
    private fun slideWindow(mc: MediaController, centerIndex: Int) {
        if (fullQueue.isEmpty() || centerIndex !in fullQueue.indices) return
        val wantStart = maxOf(0, centerIndex - 1)
        val wantEnd = minOf(fullQueue.size, centerIndex + 2)
        if (wantStart == windowStart && wantEnd == windowEnd) return

        if (wantStart < windowStart) registerRange(wantStart, windowStart)
        if (wantEnd > windowEnd) registerRange(windowEnd, wantEnd)

        if (wantEnd > windowEnd) {
            val append = (windowEnd until wantEnd).map { toMediaItem(it, fullQueue[it]) }
            windowEnd = wantEnd
            mc.addMediaItems(append)
        }
        if (wantStart < windowStart) {
            val prepend = (wantStart until windowStart).map { toMediaItem(it, fullQueue[it]) }
            windowStart = wantStart
            mc.addMediaItems(0, prepend)
        }
        if (wantEnd < windowEnd) {
            val from = wantEnd - windowStart
            val to = windowEnd - windowStart
            windowEnd = wantEnd
            if (from in 0 until to && to <= mc.mediaItemCount) mc.removeMediaItems(from, to)
        }
        if (wantStart > windowStart) {
            val count = minOf(wantStart - windowStart, mc.mediaItemCount)
            windowStart += count
            if (count > 0) mc.removeMediaItems(0, count)
        }
        retainActiveWindowSources()
    }

    private fun setQueueNow(mc: MediaController, items: List<QueueItem>, startIndex: Int) {
        failed = false
        handler.removeCallbacks(slideRunnable)
        pendingSlideIndex = -1
        if (items.isEmpty()) {
            fullQueue = emptyList()
            windowStart = 0
            windowEnd = 0
            PlaybackCore.sources.clear()
            mc.stop()
            mc.clearMediaItems()
            sync()
            return
        }
        fullQueue = items
        val target = startIndex.coerceIn(items.indices)
        windowStart = maxOf(0, target - 1)
        windowEnd = minOf(items.size, target + 2)
        registerRange(windowStart, windowEnd)
        retainActiveWindowSources()
        val mediaItems = (windowStart until windowEnd).map { toMediaItem(it, items[it]) }
        mc.setMediaItems(mediaItems, target - windowStart, 0L)
        mc.prepare()
        mc.play()
    }

    override fun play() = withController { mc ->
        failed = false
        if (mc.playbackState == Player.STATE_ENDED && mc.mediaItemCount > 0) {
            mc.seekToDefaultPosition(mc.currentMediaItemIndex.coerceAtLeast(0))
        } else if (mc.playbackState == Player.STATE_IDLE && mc.mediaItemCount > 0) {
            mc.prepare()
        }
        mc.play()
    }

    override fun pause() = withController { it.pause() }

    override fun next() = withController { mc ->
        failed = false
        val cur = currentFullIndex(mc)
        if (cur in fullQueue.indices) {
            val nextItem = if (cur + 1 in fullQueue.indices) fullQueue[cur + 1] else null
            io.max.paperplay.player.usb.UsbRingLog.record("Player", "TRACK_SWITCH user_next target=${nextItem?.uri ?: "none"}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
            if (mc.hasNextMediaItem()) {
                mc.seekToNextMediaItem()
                slideWindow(mc, cur + 1)
            } else if (cur + 1 < fullQueue.size) {
                setQueueNow(mc, fullQueue, cur + 1)
            }
        }
    }

    override fun previous() = withController { mc ->
        failed = false
        val cur = currentFullIndex(mc)
        if (cur in fullQueue.indices) {
            val threshold = mc.maxSeekToPreviousPosition
            val prevItem = if (cur - 1 in fullQueue.indices) fullQueue[cur - 1] else null
            io.max.paperplay.player.usb.UsbRingLog.record("Player", "TRACK_SWITCH user_prev target=${prevItem?.uri ?: "none"}", io.max.paperplay.player.usb.UsbRingLog.CODE_GENERIC)
            if (mc.currentPosition <= threshold && !mc.hasPreviousMediaItem() && cur > 0) {
                setQueueNow(mc, fullQueue, cur - 1)
            } else {
                val target = if (mc.currentPosition <= threshold && mc.hasPreviousMediaItem()) cur - 1 else cur
                mc.seekToPrevious()
                slideWindow(mc, target)
            }
        } else {
            mc.seekToPrevious()
        }
    }

    override fun seek(positionMs: Long) = withController { failed = false; it.seekTo(positionMs.coerceAtLeast(0L)) }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        onMain {
            volumeLevel = clamped
            val isUsb = PlaybackCore.usbExclusiveMode || PlaybackCore.usbActive
            if (isUsb) {
                // In USB exclusive mode, do NOT touch system STREAM_MUSIC from engine.setVolume!
                // PlayerEngine.setVolume is called by PlaybackController for fades, play/pause restores, etc.
                // System volume must ONLY be changed by explicit user actions (slider, hardware keys).
                // Keep ExoPlayer volume at 1.0f in USB mode for bit-perfection.
                controller?.takeIf { it.isConnected }?.let { it.volume = 1f; publishInfo(it) }
            } else {
                controller?.takeIf { it.isConnected }?.let { it.volume = clamped; publishInfo(it) }
            }
        }
    }

    override fun setBass(db: Float) {
        PlaybackCore.bassDb = db
    }

    override fun setExactLevel(on: Boolean) { PlaybackCore.exactLevel = on }

    override fun setDryCompare(on: Boolean) { PlaybackCore.dryCompare = on }

    override fun setStereoWidth(width: Float) { PlaybackCore.width = width }

    override fun setEngineOptions(bits: Int) { PlaybackCore.options = bits }

    override fun setEarphoneCorrection(text: String?) { PlaybackCore.correction = text?.let { CorrectionProfile.parseParametric(it) } }

    override fun setEqualizer(gainsDb: List<Float>?) {
        PlaybackCore.eqGains = gainsDb
        onMain {
            val mc = controller ?: return@onMain
            if (mc.isConnected) sendEqualizer(mc, gainsDb)
            publishInfo(mc)
        }
    }

    override fun setParametricEq(profile: ParametricEqProfile?, enabled: Boolean) {
        PlaybackCore.parametricEq = profile
        PlaybackCore.parametricEnabled = enabled
        onMain {
            AndroidUsbDacBackend.refreshStatus()
            val mc = controller ?: return@onMain
            publishInfo(mc)
        }
    }

    override fun setAllowEqInUsbExclusive(allow: Boolean) {
        PlaybackCore.allowEqInUsbExclusive = allow
        onMain {
            AndroidUsbDacBackend.refreshStatus()
            val mc = controller ?: return@onMain
            publishInfo(mc)
        }
    }

    override fun setSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2f)
        PlaybackCore.speed = s
        onMain { controller?.takeIf { it.isConnected }?.setPlaybackSpeed(s) }
    }

    override fun setNormalize(on: Boolean) {
        PlaybackCore.normalize = on
        onMain {
            val mc = controller ?: return@onMain
            if (mc.isConnected) sendNormalize(mc, on)
            publishInfo(mc)
        }
    }

    override fun setExclusiveOutput(on: Boolean) {
        // Desktop mpv output switch; no-op on Android
    }

    override fun setBitPerfect(on: Boolean) {
        AndroidUsbDacBackend.setUsbExclusive(on)
    }

    /** The decoded track's format from Media3, plus what Android says about the output device and the effects in the path. */
    private fun publishInfo(player: Player) {
        val format = player.currentTracks.groups
            .firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val outputs = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val external = outputs.firstOrNull {
            it.type in setOf(
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET, android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
            )
        }
        val usb = external != null && (external.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || external.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET)
        val outRate = (if (usb && external != null) usbMixerRate(external).takeIf { it > 0 } else null)
            ?: am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val isUsbBitPerfect = PlaybackCore.usbActive
        val usbMgr = io.max.paperplay.player.usb.UsbAudioManager.instance
        val usbConn = usbMgr?.state?.value as? io.max.paperplay.player.usb.UsbAudioState.Connected
        val usbDev = usbConn?.device
        val usbDevName = usbDev?.productName?.takeIf { it.isNotBlank() } ?: "USB DAC"
        val hwVolSupported = usbMgr?.hasHardwareVolume == true
        val unifiedSink = if (PlaybackCore.useUnifiedBitPerfect) io.max.paperplay.player.usb.UnifiedUsbAudioSink.currentInstance else null
        val usbStats = if (isUsbBitPerfect) unifiedSink?.getStatsSnapshot() ?: usbMgr?.getStreamerStats() else null
        val streamer = if (isUsbBitPerfect) usbMgr?.streamer else null
        val activeAltNum = unifiedSink?.confirmedAlt?.takeIf { it >= 0 } ?: streamer?.confirmedAlt?.takeIf { it >= 0 }
        val activeAltDesc = activeAltNum?.let { n -> usbConn?.description?.altSettings?.firstOrNull { it.altSetting == n } }

        val peqActiveRaw = PlaybackCore.parametricEnabled && PlaybackCore.parametricEq?.hasActiveShaping() == true
        val usbDirectEq = isUsbBitPerfect && PlaybackCore.allowEqInUsbExclusive && peqActiveRaw
        val peqOn = usbDirectEq || (!isUsbBitPerfect && peqActiveRaw)
        val eqOn = peqOn || (!isUsbBitPerfect && PlaybackCore.eqGains?.any { kotlin.math.abs(it) >= 0.05f } == true)
        val effects = if (isUsbBitPerfect) {
            if (usbDirectEq) listOf("Parametric EQ (${PlaybackCore.parametricEq?.bands?.count { it.enabled } ?: 0} bands)")
            else emptyList()
        } else buildList {
            if (peqOn) add("Parametric EQ (${PlaybackCore.parametricEq?.bands?.count { it.enabled } ?: 0} bands)")
            else if (eqOn) add(if (PlaybackCore.engineActive) "Equalizer (engine)" else "Equalizer (system)")
            if (eqOn && PlaybackCore.engineActive && PlaybackCore.bassDb >= 0.05f) add("Bass +${"%.1f".format(PlaybackCore.bassDb)} dB")
            if (PlaybackCore.engineActive && PlaybackCore.correction != null) add("Earphone correction")
            if (PlaybackCore.engineActive && PlaybackCore.options and 1 != 0 && PlaybackCore.bassDb >= 0.05f) add("Dynamic bass")
            if (PlaybackCore.engineActive && PlaybackCore.options and 2 != 0) add("De-esser")
            if (PlaybackCore.engineActive && PlaybackCore.options and 4 != 0) add("Crossfeed")
            if (PlaybackCore.engineActive && kotlin.math.abs(PlaybackCore.width - 1f) > 0.005f) add("Width ${(PlaybackCore.width * 100).toInt()}%")
            if (PlaybackCore.normalize) add("Normalise")
            if (PlaybackCore.speed != 1f) add("Speed ${PlaybackCore.speed}x")
            if (volumeLevel < 0.995f) add("Volume ${(volumeLevel * 100).toInt()}%")
        }
        val rate = format?.sampleRate?.takeIf { it > 0 }
        val depth = when (format?.pcmEncoding) {
            androidx.media3.common.C.ENCODING_PCM_16BIT -> 16
            androidx.media3.common.C.ENCODING_PCM_24BIT -> 24
            androidx.media3.common.C.ENCODING_PCM_32BIT, androidx.media3.common.C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }
        val matches = rate != null && outRate != null && rate == outRate
        val api34Verified = if (!isUsbBitPerfect && PlaybackCore.useUnifiedBitPerfect && PlaybackCore.devApi34MixerEnabled) {
            io.max.paperplay.player.usb.Api34BitPerfectMixer.lastVerification as? io.max.paperplay.player.usb.Api34MixerVerification.Verified
        } else {
            null
        }
        val transport = when {
            isUsbBitPerfect -> TransportInfo(
                kind = TransportKind.USB_UAC_DIRECT,
                verified = true,
                deviceName = usbDevName,
                deviceId = usbDev?.let { "${it.vendorId.toString(16).padStart(4, '0')}:${it.productId.toString(16).padStart(4, '0')}" },
                protocolVersion = when (usbConn?.description?.uacVersion) {
                    io.max.paperplay.player.usb.UacVersion.UAC_1_0 -> "UAC 1.0"
                    io.max.paperplay.player.usb.UacVersion.UAC_2_0 -> "UAC 2.0"
                    else -> null
                },
                confirmedRateHz = unifiedSink?.confirmedRateHz?.takeIf { it > 0 }
                    ?: streamer?.confirmedRate?.takeIf { it > 0 }
                    ?: usbStats?.sampleRate?.takeIf { it > 0 }
                    ?: rate,
                confirmedBitDepth = unifiedSink?.confirmedBitDepth?.takeIf { it > 0 }
                    ?: streamer?.bitDepth?.takeIf { it > 0 }
                    ?: activeAltDesc?.bitResolution
                    ?: depth,
                confirmedContainerBits = activeAltDesc?.subslotSize?.times(8),
                confirmedAlt = activeAltNum,
                syncMode = activeAltDesc?.dataEndpoint?.syncType?.name?.lowercase()?.replaceFirstChar { it.uppercase() },
                hardwareVolume = if (hwVolSupported) "UAC Feature Unit (verified)" else "Fixed 0 dB (bit-perfect)",
                underruns = usbStats?.underruns ?: 0L,
                errors = usbStats?.urbErrors ?: 0L,
                framesConsumed = usbStats?.framesConsumed ?: 0L,
            )
            api34Verified != null && usb && (effects.isEmpty() || (api34Verified.eqActive && effects.all(BitPerfectEvaluator::isEqEffect))) -> TransportInfo(
                kind = TransportKind.ANDROID_BIT_PERFECT_MIXER,
                verified = true,
                deviceName = io.max.paperplay.player.usb.Api34BitPerfectMixer.lastDeviceName
                    ?: external?.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: "USB DAC",
                confirmedRateHz = api34Verified.sampleRateHz,
                confirmedBitDepth = api34Verified.bitDepth,
                confirmedContainerBits = api34Verified.bitDepth,
                hardwareVolume = "System USB volume (API 34+ mixer)",
            )
            else -> TransportInfo(
                kind = TransportKind.ANDROID_MIXER,
                verified = false,
                deviceName = external?.productName?.toString()?.takeIf { it.isNotBlank() } ?: (if (external != null) "Headphones" else android.os.Build.MODEL),
            )
        }
        val verdict = if (transport.verified && rate != null) {
            val ch = format?.channelCount?.takeIf { it > 0 } ?: 2
            val isFloat = format?.pcmEncoding == androidx.media3.common.C.ENCODING_PCM_FLOAT
            val srcDepth = if (isFloat && (transport.confirmedBitDepth ?: 32) <= 24) {
                transport.confirmedBitDepth ?: 24
            } else {
                depth ?: 16
            }
            val srcPcm = PcmFormat(
                sampleRateHz = rate,
                sourceBitDepth = srcDepth,
                wireBitDepth = depth ?: srcDepth,
                containerBits = depth ?: srcDepth,
                encoding = when {
                    format?.sampleMimeType == MIME_AUDIO_DOP -> SampleEncoding.DOP_24
                    isFloat -> SampleEncoding.PCM_FLOAT
                    else -> SampleEncoding.PCM_INT
                },
                channels = ch,
                exactPowerOfTwoFloat = isFloat && srcDepth <= 24,
            )
            val outPcm = PcmFormat(
                sampleRateHz = transport.confirmedRateHz ?: rate,
                sourceBitDepth = transport.confirmedBitDepth ?: srcDepth,
                wireBitDepth = transport.confirmedBitDepth ?: srcDepth,
                containerBits = transport.confirmedContainerBits ?: transport.confirmedBitDepth ?: srcDepth,
                encoding = if (format?.sampleMimeType == MIME_AUDIO_DOP) SampleEncoding.DOP_24 else SampleEncoding.PCM_INT,
                channels = ch,
            )
            BitPerfectEvaluator.evaluate(
                transport = transport,
                source = srcPcm,
                output = outPcm,
                effects = effects,
                requestedOn = PlaybackCore.usbExclusiveMode,
                allowEqInUsbExclusive = PlaybackCore.allowEqInUsbExclusive,
            )
        } else {
            BitPerfectVerdict(
                bitPerfect = false,
                note = when {
                    eqOn && PlaybackCore.engineActive -> "The equalizer runs in 32-bit float and a limiter keeps peaks from clipping. This is not bit-perfect."
                    effects.isNotEmpty() -> "Effects change the sound before it reaches the output."
                    !matches && usb -> "Android mixes this DAC at ${outRate ?: 0} Hz and converts the track to it."
                    !matches -> "The device mixes at ${outRate ?: "an unknown"} Hz, so this track is resampled."
                    else -> "Android's mixer can still convert the format; only a verified bit-perfect USB DAC route skips it."
                },
            )
        }
        val resolvedDeviceKey: String = when {
            usbDev != null -> "usb:${usbDev.vendorId.toString(16).padStart(4, '0').uppercase()}:${usbDev.productId.toString(16).padStart(4, '0').uppercase()}"
            external != null && (external.type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE || external.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) -> {
                resolveConnectedUsbVidPidKey(context) ?: run {
                    val p = external.productName?.toString()?.trim().orEmpty().ifEmpty { "dac" }
                    "usb:$p"
                }
            }
            external != null && (external.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || external.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) -> {
                val addr = if (android.os.Build.VERSION.SDK_INT >= 28) external.address?.trim().orEmpty() else ""
                val usableAddr = addr.takeIf { it.isNotEmpty() && it != "02:00:00:00:00:00" }
                val label = usableAddr ?: external.productName?.toString()?.trim().orEmpty().ifEmpty { "bluetooth" }
                "bt:$label"
            }
            external != null && (external.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || external.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET) -> {
                "wired:${external.productName?.toString()?.trim().orEmpty().ifEmpty { "3.5mm" }}"
            }
            else -> "speaker:internal"
        }
        _info.value = OutputInfo(
            codec = format?.sampleMimeType?.substringAfter('/')?.uppercase(),
            sampleRateHz = rate,
            channels = format?.channelCount?.takeIf { it > 0 },
            bitrateKbps = format?.averageBitrate?.takeIf { it > 0 }?.let { it / 1000 },
            bitDepth = depth,
            device = if (isUsbBitPerfect) usbDevName else (external?.productName?.toString()?.takeIf { it.isNotBlank() } ?: (if (external != null) "Headphones" else android.os.Build.MODEL)),
            outputRateHz = if (isUsbBitPerfect) rate else outRate,
            effects = effects,
            details = if (isUsbBitPerfect) {
                listOf(
                    "Route" to if (usbDirectEq) "USB Direct + EQ (native rate, usbfs)" else "Bit-perfect USB exclusive (usbfs)",
                    "Hardware Volume" to if (hwVolSupported) "UAC Feature Unit (verified)" else "Fixed 0 dB (bit-perfect)"
                )
            } else {
                engineDetails(eqOn)
            },
            bitPerfect = verdict.bitPerfect,
            note = verdict.note,
            transport = transport,
            deviceKey = resolvedDeviceKey,
        )
    }

    /** Metadata-only VID:PID lookup so USB deviceKey stays identical in Normal and USB Exclusive modes. */
    private fun resolveConnectedUsbVidPidKey(ctx: Context): String? = runCatching {
        val um = ctx.getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager ?: return@runCatching null
        val dev = um.deviceList.values.firstOrNull { d ->
            val pairs = (0 until d.interfaceCount).map { idx ->
                val intf = d.getInterface(idx)
                intf.interfaceClass to intf.interfaceSubclass
            }
            io.max.paperplay.player.usb.UsbDiscoveryHintTracker.hasAudioStreamingInterface(pairs)
        } ?: return@runCatching null
        val vid = dev.vendorId.toString(16).padStart(4, '0').uppercase()
        val pid = dev.productId.toString(16).padStart(4, '0').uppercase()
        "usb:$vid:$pid"
    }.getOrNull()

    /** The rate Android runs a connected USB DAC at: the highest rate it lists (its own list, or the mixer list when that is empty); 0 if unknown. */
    private fun usbMixerRate(dev: android.media.AudioDeviceInfo): Int {
        var rates = dev.sampleRates.filter { it > 0 }
        if (rates.isEmpty() && android.os.Build.VERSION.SDK_INT >= 34) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            rates = am.getSupportedMixerAttributes(dev).map { it.format.sampleRate }.filter { it > 0 }
        }
        return rates.maxOrNull() ?: 0
    }

    /** The engine's real format and meters, only while it is in the audio path. */
    private fun engineDetails(eqOn: Boolean): List<Pair<String, String>> {
        if (!PlaybackCore.engineActive) return emptyList()
        val st = PlaybackCore.engineStats
        fun db(v: Float) = (if (v > 0) "+" else "") + "%.1f dB".format(v)
        return buildList {
            PlaybackCore.engineIn?.let { add("Engine input" to it) }
            PlaybackCore.engineOut?.let { add("Engine output" to it) }
            if (eqOn && st != null && st.size == 7) {
                add("Curve peak" to db(st[5]))
                add("Level trim" to "bass ${db(st[0])}, rest ${db(st[1])}")
                fun lim(v: Float) = if (v < -0.05f) "up to ${"%.1f".format(-v)} dB" else "idle"
                add("Limiter" to "bass ${lim(st[3])}, rest ${lim(st[4])}")
                add("Level mode" to if (PlaybackCore.exactLevel) "exact curve" else "loud")
                if (PlaybackCore.dryCompare) add("A/B" to "original, matched ${db(st[6])}")
            }
        }
    }

    private fun sendNormalize(mc: MediaController, on: Boolean) {
        mc.sendCustomCommand(PlaybackCore.CMD_SET_NORMALIZE, Bundle().apply { putBoolean(PlaybackCore.EXTRA_NORMALIZE, on) })
    }

    private fun sendEqualizer(mc: MediaController, gainsDb: List<Float>?) {
        val args = Bundle().apply {
            if (gainsDb != null) putFloatArray(PlaybackCore.EXTRA_EQ_GAINS, gainsDb.toFloatArray())
        }
        mc.sendCustomCommand(PlaybackCore.CMD_SET_EQUALIZER, args)
    }

    override fun release() = onMain {
        released = true
        AndroidUsbDacBackend.detachHooks(engineHooks)
        scope.cancel()
        PlaybackCore.onUsbReconfigurationRequested = null
        stopTicker()
        handler.removeCallbacks(slideRunnable)
        pending.clear()
        fullQueue = emptyList()
        windowStart = 0
        windowEnd = 0
        controller?.let { mc ->
            mc.removeListener(playerListener)
            if (mc.isConnected) {
                mc.stop()
                mc.clearMediaItems()
            }
        }
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        PlaybackCore.sources.clear()
        _state.value = PlayerState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
        _bufferedMs.value = 0L
        _currentIndex.value = -1
    }

    private fun parseArtworkUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        val parsed = Uri.parse(raw)
        return if (parsed.scheme == null) Uri.fromFile(File(raw)) else parsed
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
        const val FAST_POLL_MS = 250L
    }
}

actual fun createPlayerEngine(): PlayerEngine = LazyPlayerEngine(
    factory = { AndroidPlayerEngine() },
    supportsBitPerfect = true,
    dacController = AndroidUsbDacBackend,
    onPendingBitPerfect = { on -> AndroidUsbDacBackend.setUsbExclusive(on) },
)
