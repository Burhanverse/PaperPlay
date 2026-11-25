package io.max.paperplay.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.max.paperplay.player.PlaybackCore
import io.max.paperplay.player.PlayLog
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class UsbAudioState {
    object NoDevice : UsbAudioState()
    data class Attached(val device: UsbDevice, val hasPermission: Boolean) : UsbAudioState()
    data class Connected(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val description: UsbAudioDeviceDescription,
    ) : UsbAudioState()
    data class Error(val message: String, val device: UsbDevice? = null) : UsbAudioState()
}

/**
 * Android USB Audio host manager.
 *
 * Discovers connected USB Audio Class devices, requests runtime permission,
 * maintains UsbDeviceConnection, and parses device descriptors via [UsbDescriptorParser].
 */
class UsbAudioManager(private val context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _state = MutableStateFlow<UsbAudioState>(UsbAudioState.NoDevice)
    val state: StateFlow<UsbAudioState> = _state.asStateFlow()

    private val connectionLock = Any()
    private var activeConnection: UsbDeviceConnection? = null
    private var activeStreamer: UsbAudioStreamer? = null
    var hardwareVolume: UsbHardwareVolume? = null
        private set
    private var isRegistered = false

    private var lastPlayerVolume = 1.0f
    private var volumeReceiverRegistered = false
    private var volumeContentObserver: ContentObserver? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingPermissionJob: Runnable? = null

    private val cachedDescriptions = mutableMapOf<Int, UsbAudioDeviceDescription>()
    private val cachedCaps = mutableMapOf<Int, DacCapabilities>()
    private val requestedPermissionDeviceIds = mutableSetOf<Int>()
    @Volatile
    private var isForeground = false
    var forwardingPlayer: UsbVolumeForwardingPlayer? = null
    var testBridgeFactory: ((UsbAudioAltSetting, Int, Boolean) -> UsbAudioSinkBridge)? = null
    var testAltSettings: List<UsbAudioAltSetting>? = null

    private val volumePollRunnable = object : Runnable {
        override fun run() {
            if (isForeground && (PlaybackCore.usbExclusiveMode || PlaybackCore.usbActive)) {
                onSystemVolumeChanged(isUser = true)
                mainHandler.postDelayed(this, 500L)
            }
        }
    }

    private val permissionAction = "${context.packageName}.USB_AUDIO_PERMISSION"

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC || streamType == -1) {
                    onSystemVolumeChanged(isUser = true)
                }
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getParcelableDevice(intent)
                    if (device != null && isAudioDevice(device)) {
                        handleDeviceAttached(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getParcelableDevice(intent)
                    if (device != null) {
                        handleDeviceDetached(device)
                    }
                }
                permissionAction -> {
                    val device = getParcelableDevice(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null) {
                        handlePermissionResult(device, granted)
                    }
                }
            }
        }
    }

    private fun registerVolumeObserver() {
        if (volumeReceiverRegistered) return
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(volumeReceiver, filter)
            }
            volumeReceiverRegistered = true
        } catch (e: Exception) {
            PlayLog.log("usb", "Failed to register VOLUME_CHANGED_ACTION receiver: ${e.message}")
        }

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    onSystemVolumeChanged(isUser = true)
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer
            )
            volumeContentObserver = observer
        } catch (e: Exception) {
            PlayLog.log("usb", "Failed to register Settings.System volume ContentObserver: ${e.message}")
        }
    }

    private fun unregisterVolumeObserver() {
        if (volumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (_: Exception) {}
            volumeReceiverRegistered = false
        }
        volumeContentObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
            volumeContentObserver = null
        }
    }

    fun start() {
        if (isRegistered) return
        if (PlaybackCore.usbExclusiveMode) {
            UsbEngineNative.ensureLoaded()
        }
        instance = this
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isRegistered = true
        if (PlaybackCore.usbExclusiveMode) {
            registerVolumeObserver()
        }
        scanDevices()
    }

    fun stop() {
        if (!isRegistered) return
        pendingPermissionJob?.let { mainHandler.removeCallbacks(it) }
        pendingPermissionJob = null
        mainHandler.removeCallbacks(volumePollRunnable)
        unregisterVolumeObserver()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
        isRegistered = false
        closeCurrentConnection()
        if (instance === this) instance = null
        _state.value = UsbAudioState.NoDevice
    }

    fun enumerateAudioDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.filter { isAudioDevice(it) }
    }

    fun requestPermission(device: UsbDevice) {
        requestedPermissionDeviceIds.add(device.deviceId)
        UsbRingLog.record(
            "UsbAudioManager",
            "requestPermission for ${device.productName ?: "USB Audio"} (vid=${device.vendorId}, pid=${device.productId})",
            UsbRingLog.CODE_PERMISSION_REQ,
            device.deviceId.toLong()
        )
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(permissionAction).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    fun onActivityResumed() {
        isForeground = true
        mainHandler.removeCallbacks(volumePollRunnable)
        if (PlaybackCore.usbExclusiveMode || PlaybackCore.usbActive) {
            mainHandler.post(volumePollRunnable)
        }
        val devices = enumerateAudioDevices()
        if (devices.isEmpty()) return
        val target = devices.first()
        val hasPerm = usbManager.hasPermission(target)
        if (!PlaybackCore.usbExclusiveMode) {
            // Never open, detach, claim, or prompt when USB exclusive mode is off
            if (activeConnection == null) {
                _state.value = UsbAudioState.Attached(target, hasPermission = hasPerm)
            }
            return
        }
        if (hasPerm) {
            if (activeConnection == null) {
                openDevice(target)
            }
        } else if (!PlaybackCore.useUnifiedBitPerfect && !requestedPermissionDeviceIds.contains(target.deviceId)) {
            // Otherwise UnifiedUsbDacManager asks; two questions for one device helped nobody.
            requestPermission(target)
        }
    }

    fun onActivityPaused() {
        isForeground = false
        mainHandler.removeCallbacks(volumePollRunnable)
    }

    fun onDeviceAttachedFromIntent(device: UsbDevice) {
        if (!isAudioDevice(device)) return
        pendingPermissionJob?.let { mainHandler.removeCallbacks(it) }
        pendingPermissionJob = null
        UsbRingLog.record(
            "UsbAudioManager",
            "onDeviceAttachedFromIntent: ${device.productName ?: "USB Audio"} (vid=${device.vendorId}, pid=${device.productId})",
            UsbRingLog.CODE_DEVICE_ATTACH,
            device.deviceId.toLong()
        )
        val hasPerm = usbManager.hasPermission(device)
        _state.value = UsbAudioState.Attached(device, hasPermission = hasPerm)
        if (!PlaybackCore.usbExclusiveMode) {
            PlayLog.log("usb", "USB DAC attached from intent while usbExclusiveMode=false; leaving untouched for OS")
            return
        }
        if (hasPerm) {
            openDevice(device)
        } else {
            requestPermission(device)
        }
    }

    fun onUsbExclusiveModeChanged(enabled: Boolean) {
        if (enabled) {
            UsbEngineNative.ensureLoaded()
            registerVolumeObserver()
            mainHandler.removeCallbacks(volumePollRunnable)
            if (isForeground) {
                mainHandler.post(volumePollRunnable)
            }
            if (activeConnection == null) {
                scanDevices()
            }
        } else {
            mainHandler.removeCallbacks(volumePollRunnable)
            unregisterVolumeObserver()
            closeCurrentConnection()
            scanDevices()
        }
    }

    /**
     * Opens the USB device and parses its topology strictly from Kotlin raw descriptors.
     * Never opens the device when [PlaybackCore.usbExclusiveMode] is false unless [forceForProbe] is true.
     * Never runs the native usbfs clock-range probe here; that runs lazily via [ensureNativeCapabilitiesProbed].
     */
    fun openDevice(device: UsbDevice, forceForProbe: Boolean = false, triggerReconfigure: Boolean = true): Boolean =
        synchronized(connectionLock) {
            if (!PlaybackCore.usbExclusiveMode && !forceForProbe) {
                val hasPerm = usbManager.hasPermission(device)
                _state.value = UsbAudioState.Attached(device, hasPermission = hasPerm)
                PlayLog.log("usb", "openDevice skipped: usbExclusiveMode=false (never open/detach/claim DAC)")
                return@synchronized false
            }
            if (!usbManager.hasPermission(device)) {
                _state.value = UsbAudioState.Attached(device, hasPermission = false)
                requestPermission(device)
                return@synchronized false
            }

            val existing = _state.value as? UsbAudioState.Connected
            if (!forceForProbe && activeConnection != null && existing?.device?.deviceId == device.deviceId) {
                if (PlaybackCore.usbExclusiveMode && triggerReconfigure) {
                    UnifiedUsbAudioSink.currentInstance?.markPendingUsbAttach()
                    if (PlaybackCore.userPlayIntent && PlaybackCore.playerIsPlaying) {
                        PlaybackCore.requestReconfiguration()
                    }
                }
                return@synchronized true
            }

            closeCurrentConnection()

            val connection = usbManager.openDevice(device)
            if (connection == null) {
                _state.value = UsbAudioState.Error("Failed to open USB device connection", device)
                return@synchronized false
            }

            val rawDescriptors = connection.rawDescriptors
            if (rawDescriptors == null || rawDescriptors.isEmpty()) {
                connection.close()
                _state.value = UsbAudioState.Error("Failed to read raw USB descriptors", device)
                return@synchronized false
            }

            val description = UsbDescriptorParser.parse(rawDescriptors)
            if (!description.isSupported) {
                connection.close()
                _state.value = UsbAudioState.Error(
                    description.unsupportedReason ?: "USB Audio device not supported",
                    device,
                )
                return@synchronized false
            }

            activeConnection = connection

            val cachedDesc = cachedDescriptions[device.deviceId]
            val cachedCap = cachedCaps[device.deviceId]

            val finalDescription = cachedDesc ?: description
            val probedCaps: DacCapabilities? = cachedCap

            val altDiag = finalDescription.altSettings.joinToString("; ") { alt ->
                val dir = if (alt.isOut) "OUT" else "IN"
                val rates = when {
                    alt.sampleRates.isNotEmpty() -> alt.sampleRates.joinToString(",")
                    alt.continuousRateRange != null -> "${alt.continuousRateRange.first}..${alt.continuousRateRange.second}"
                    else -> "clock"
                }
                val ep = alt.dataEndpoint
                val epStr = if (ep != null) {
                    "ep=0x%02x(%s,maxPkt=%d,csAttr=0x%02x,rateCtrl=%s)".format(
                        ep.address, ep.syncType.name, ep.maxPacketSize, ep.csBmAttributes, ep.supportsSampleRateControl
                    )
                } else "ep=none"
                "if=${alt.interfaceNumber}:alt=${alt.altSetting}[$dir,${alt.channelCount}ch,${alt.bitResolution}b/${alt.subslotSize}B,rates=[$rates],$epStr]"
            }
            val rawHex = rawDescriptors.joinToString("") { "%02x".format(it) }
            PlayLog.log("usb", "Opened USB DAC using Kotlin raw descriptors (uac=${finalDescription.uacVersion}, alts=${finalDescription.altSettings.size}, cachedNativeCaps=${cachedCap != null}): $altDiag")
            UsbRingLog.record(
                "UsbAudioManager",
                "DESCRIPTOR_SUMMARY: uac=${finalDescription.uacVersion} acIf=${finalDescription.acInterfaceNumber} alts=${finalDescription.altSettings.size} [$altDiag] rawHex(${rawDescriptors.size}B)=$rawHex",
                UsbRingLog.CODE_GENERIC
            )

            val fu = finalDescription.resolvePlaybackFeatureUnit(finalDescription.playbackAltSettings.firstOrNull())
                ?.takeIf { it.hasMasterVolume || it.hasChannelVolume }
            if (fu != null) {
                hardwareVolume = UsbHardwareVolume(
                    connection = connection,
                    uacVersion = finalDescription.uacVersion,
                    controlInterfaceId = finalDescription.acInterfaceNumber,
                    featureUnit = fu,
                    caps = probedCaps,
                    nativeHandleProvider = { activeStreamer?.handle ?: 0L }
                )
            } else {
                hardwareVolume = null
            }
            if (PlaybackCore.usbExclusiveMode) {
                PlaybackCore.usbStatus = "USB: Ready (${device.productName ?: "USB DAC"})"
            }
            _state.value = UsbAudioState.Connected(device, connection, finalDescription)
            forwardingPlayer?.notifyDeviceInfoChanged()
            if (PlaybackCore.usbExclusiveMode && !forceForProbe && triggerReconfigure) {
                logSystemAudioDevicesAndVolumeTable()
                val activelyPlaying = PlaybackCore.userPlayIntent && PlaybackCore.playerPlayWhenReady
                if (PlaybackCore.useUnifiedBitPerfect) {
                    if (UnifiedUsbAudioSink.currentInstance?.isUsbModeActive != true) {
                        UnifiedUsbAudioSink.currentInstance?.markPendingUsbAttach()
                        if (activelyPlaying) {
                            PlaybackCore.requestReconfiguration()
                        }
                    }
                } else {
                    if (activelyPlaying) {
                        UsbAudioSink.currentInstance?.triggerReconfiguration()
                    } else {
                        UsbAudioSink.currentInstance?.applyPendingReconfigure()
                    }
                }
            }
            true
        }

    /**
     * Lazily runs the native UAC clock-range + Feature Unit dB range probe only when USB exclusive mode
     * is actually active and capabilities are not yet cached for this device.
     * The native probe releases all interfaces and re-attaches kernel drivers cleanly before returning.
     */
    fun ensureNativeCapabilitiesProbed(): Boolean {
        if (!PlaybackCore.usbExclusiveMode) return false
        return probeConnectedDevice()
    }

    fun probeConnectedDevice(): Boolean {
        if (!PlaybackCore.usbExclusiveMode) return false
        val stateVal = _state.value as? UsbAudioState.Connected ?: return false
        val conn = activeConnection ?: return false
        val dev = stateVal.device
        val cachedDesc = cachedDescriptions[dev.deviceId]
        val cachedCap = cachedCaps[dev.deviceId]

        if (cachedDesc != null && cachedCap != null && cachedCap.sampleRates.isNotEmpty()) {
            PlayLog.log("usb", "probeConnectedDevice: reusing cached capabilities for device ${dev.deviceId}")
            return true
        }

        UsbEngineNative.ensureLoaded()
        val desc = stateVal.description
        return try {
            val caps = UsbEngineNative.parseCapabilities(conn.fileDescriptor, desc)
            if (caps != null && caps.sampleRates.isNotEmpty()) {
                val updatedAltSettings = desc.altSettings.map { alt ->
                    if (alt.dataEndpoint?.isOut == true && alt.sampleRates.isEmpty()) {
                        alt.copy(sampleRates = caps.sampleRates)
                    } else {
                        alt
                    }
                }
                val updatedDesc = desc.copy(altSettings = updatedAltSettings)
                cachedDescriptions[dev.deviceId] = updatedDesc
                cachedCaps[dev.deviceId] = caps
                val fu = updatedDesc.resolvePlaybackFeatureUnit(updatedDesc.playbackAltSettings.firstOrNull())
                    ?.takeIf { it.hasMasterVolume || it.hasChannelVolume }
                if (fu != null) {
                    hardwareVolume = UsbHardwareVolume(
                        connection = conn,
                        uacVersion = updatedDesc.uacVersion,
                        controlInterfaceId = updatedDesc.acInterfaceNumber,
                        featureUnit = fu,
                        caps = caps,
                        nativeHandleProvider = { activeStreamer?.handle ?: 0L }
                    )
                }
                PlaybackCore.usbStatus = "USB: Ready (${stateVal.device.productName ?: "USB DAC"})"
                _state.value = UsbAudioState.Connected(stateVal.device, conn, updatedDesc)
                forwardingPlayer?.notifyDeviceInfoChanged()
                PlayLog.log("usb", "Lazy native DAC probe: rates=${caps.sampleRates} depths=${caps.bitDepths} clockId=${caps.clockSourceId}")
                UsbRingLog.record(
                    "UsbAudioManager",
                    "Lazy native probe: rates=${caps.sampleRates} depths=${caps.bitDepths} clockId=${caps.clockSourceId}",
                    UsbRingLog.CODE_GENERIC
                )
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            PlayLog.log("usb", "Lazy native DAC probe failed: ${t.message}")
            UsbRingLog.record("UsbAudioManager", "Lazy native probe exception: ${t.message}\n${t.stackTraceToString()}", UsbRingLog.CODE_ERROR)
            false
        }
    }

    fun reopenConnection(): UsbDeviceConnection? {
        val stateVal = _state.value as? UsbAudioState.Connected ?: return null
        val dev = stateVal.device
        PlayLog.log("usb", "Closing and re-opening UsbDeviceConnection for ${dev.deviceName}")
        UsbRingLog.record("UsbAudioManager", "Re-opening UsbDeviceConnection", UsbRingLog.CODE_GENERIC)
        try {
            activeConnection?.close()
        } catch (_: Exception) {}
        activeConnection = null

        val newConn = usbManager.openDevice(dev)
        if (newConn == null) {
            PlayLog.log("usb", "Failed to re-open UsbDeviceConnection via UsbManager.openDevice")
            UsbRingLog.record("UsbAudioManager", "Failed to re-open connection", UsbRingLog.CODE_ERROR)
            return null
        }
        activeConnection = newConn
        _state.value = UsbAudioState.Connected(dev, newConn, stateVal.description)
        return newConn
    }

    fun createStreamer(altSetting: UsbAudioAltSetting, targetRate: Int, isDop: Boolean = false): UsbAudioStreamer? {
        val conn = activeConnection ?: return null
        val stateVal = _state.value as? UsbAudioState.Connected ?: return null
        val dev = stateVal.device
        val desc = stateVal.description

        var alt0: UsbInterface? = null
        var targetAlt: UsbInterface? = null

        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            if (intf.id == altSetting.interfaceNumber) {
                if (intf.alternateSetting == 0) alt0 = intf
                if (intf.alternateSetting == altSetting.altSetting) targetAlt = intf
            }
        }

        val target = targetAlt ?: return null

        var audioEp: UsbEndpoint? = null
        var fbEp: UsbEndpoint? = null

        for (e in 0 until target.endpointCount) {
            val ep = target.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                audioEp = ep
            } else if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                fbEp = ep
            }
        }

        val outEp = audioEp ?: return null

        val clockId = desc.resolvePlaybackClockSourceId(altSetting) ?: 1
        val isUac2 = desc.uacVersion == UacVersion.UAC_2_0

        val streamer = UsbAudioStreamer(
            device = dev,
            connection = conn,
            audioStreamingInterface = target,
            alt0Interface = alt0,
            targetAltInterface = target,
            audioEndpoint = outEp,
            feedbackEndpoint = fbEp,
            isUac2 = isUac2,
            clockSourceId = clockId,
            controlInterfaceId = desc.acInterfaceNumber,
            sampleRate = targetRate,
            bitDepth = altSetting.bitResolution,
            channels = altSetting.channelCount,
            subslotSize = altSetting.subslotSize,
            isHighSpeed = isUac2 || outEp.maxPacketSize > 1023,
            isDop = isDop,
            epAttributes = altSetting.computeNativeEpAttributes(targetRate),
        )

        activeStreamer = streamer
        return streamer
    }

    private fun scanDevices() {
        val devices = enumerateAudioDevices()
        if (devices.isEmpty()) {
            _state.value = UsbAudioState.NoDevice
            return
        }
        val target = devices.first()
        val hasPerm = usbManager.hasPermission(target)
        if (!PlaybackCore.usbExclusiveMode) {
            // Never open, detach, claim, or prompt when usb_exclusive_mode=false
            _state.value = UsbAudioState.Attached(target, hasPermission = hasPerm)
            return
        }
        if (hasPerm) {
            openDevice(target)
        } else {
            _state.value = UsbAudioState.Attached(target, hasPermission = false)
            if (isForeground && !requestedPermissionDeviceIds.contains(target.deviceId)) {
                requestPermission(target)
            }
        }
    }

    private fun handleDeviceAttached(device: UsbDevice) {
        pendingPermissionJob?.let { mainHandler.removeCallbacks(it) }
        pendingPermissionJob = null
        UsbRingLog.record(
            "UsbAudioManager",
            "deviceAttached: ${device.productName ?: "USB Audio"} (vid=${device.vendorId}, pid=${device.productId})",
            UsbRingLog.CODE_DEVICE_ATTACH,
            device.deviceId.toLong()
        )
        val hasPerm = usbManager.hasPermission(device)
        _state.value = UsbAudioState.Attached(device, hasPermission = hasPerm)
        if (!PlaybackCore.usbExclusiveMode) {
            PlayLog.log("usb", "USB DAC attached while usbExclusiveMode=false; leaving untouched for OS")
            return
        }
        if (hasPerm) {
            openDevice(device)
        } else if (isForeground && !requestedPermissionDeviceIds.contains(device.deviceId)) {
            // Give 400ms grace window for MainActivity's USB_DEVICE_ATTACHED intent-filter
            // to deliver the persistent permission grant before prompting with a transient dialog
            val job = Runnable {
                pendingPermissionJob = null
                if (PlaybackCore.usbExclusiveMode && !usbManager.hasPermission(device)) {
                    requestPermission(device)
                }
            }
            pendingPermissionJob = job
            mainHandler.postDelayed(job, 400L)
        }
    }

    private fun handleDeviceDetached(device: UsbDevice) {
        pendingPermissionJob?.let { mainHandler.removeCallbacks(it) }
        pendingPermissionJob = null
        requestedPermissionDeviceIds.remove(device.deviceId)
        cachedDescriptions.remove(device.deviceId)
        cachedCaps.remove(device.deviceId)
        UsbRingLog.record(
            "UsbAudioManager",
            "deviceDetached: ${device.productName ?: "USB Audio"}",
            UsbRingLog.CODE_DEVICE_DETACH,
            device.deviceId.toLong()
        )
        val current = _state.value
        if (current is UsbAudioState.Connected && current.device.deviceId == device.deviceId) {
            closeCurrentConnection()
            UsbAudioSink.currentInstance?.onDeviceDetached()
            scanDevices()
        } else if (current is UsbAudioState.Attached && current.device.deviceId == device.deviceId) {
            scanDevices()
        }
    }

    private fun handlePermissionResult(device: UsbDevice, granted: Boolean) {
        UsbRingLog.record(
            "UsbAudioManager",
            "permissionResult: granted=$granted dev=${device.productName ?: "USB Audio"}",
            UsbRingLog.CODE_PERMISSION_RES,
            if (granted) 1L else 0L,
            device.deviceId.toLong()
        )
        if (granted) {
            if (PlaybackCore.usbExclusiveMode) {
                openDevice(device)
            } else {
                _state.value = UsbAudioState.Attached(device, hasPermission = true)
            }
        } else {
            _state.value = UsbAudioState.Error("USB DAC found - tap to allow", device)
        }
    }

    fun getSystemVolumeRatio(): Float {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 1.0f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        if (max <= 0f) return 1.0f
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        return (cur / max).coerceIn(0f, 1f)
    }

    fun getEffectiveVolume(): Float {
        return getSystemVolumeRatio()
    }

    fun applyInitialVolume(force: Boolean = true) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            PlayLog.log("usb", "Applying USB hardware volume: cur=$cur max=$max force=$force")
            UsbRingLog.record(
                "UsbAudioManager",
                "applyInitialVolume: cur=$cur max=$max force=$force",
                UsbRingLog.CODE_GENERIC
            )
            hardwareVolume?.setVolumeFromSystem(am, cur, max, isUser = false, force = force)
        } else {
            val effective = getEffectiveVolume()
            hardwareVolume?.setVolume(effective, isUser = false, force = force)
        }
        forwardingPlayer?.notifyVolumeChanged()
    }

    fun forceApplyHardwareVolume() {
        applyInitialVolume(force = true)
    }

    fun setHardwareVolume(volume: Float): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ok = if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetIdx = (volume.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur != targetIdx) {
                val caller = Throwable().stackTrace.take(6).joinToString(" -> ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
                PlayLog.log("volume", "UsbAudioManager.setHardwareVolume: $cur -> $targetIdx from $caller")
                UsbRingLog.record("Volume", "setHardwareVolume $cur->$targetIdx from $caller", UsbRingLog.CODE_GENERIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIdx, 0)
            }
            hardwareVolume?.setVolumeFromSystem(am, targetIdx, max, isUser = true) ?: false
        } else {
            hardwareVolume?.setVolume(volume, isUser = true) ?: false
        }
        forwardingPlayer?.notifyVolumeChanged()
        return ok
    }

    fun onSystemVolumeChanged(isUser: Boolean = false) {
        val hw = hardwareVolume ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ratio = if (max > 0) cur.toFloat() / max.toFloat() else 1.0f
        if (kotlin.math.abs(hw.currentVolume - ratio) > 0.005f || (cur <= 0 && !hw.isMuted) || (cur > 0 && hw.isMuted)) {
            hw.setVolumeFromSystem(am, cur, max, isUser = isUser)
        }
        forwardingPlayer?.notifyVolumeChanged()
    }

    val hasHardwareVolume: Boolean
        get() = hardwareVolume?.isSupported == true

    fun getStreamerStats(): UsbStreamStats? = activeStreamer?.getStats()

    val isStreamingActive: Boolean
        get() = activeStreamer?.isRunning == true

    val streamer: UsbAudioStreamer?
        get() = activeStreamer

    /**
     * Called AFTER the native USB streamer has restored DAC mute/volume (while interface 0 was claimed)
     * and released/reattached the kernel driver. Never issues unclaimed USB control transfers and never
     * mutates Android's STREAM_MUSIC via setStreamVolume (only explicit user actions change STREAM_MUSIC).
     */
    fun onUsbReleased(closeConnection: Boolean = false, rescan: Boolean = true) {
        hardwareVolume?.invalidateVolumeCache()
        if (!PlaybackCore.usbExclusiveMode) {
            mainHandler.removeCallbacks(volumePollRunnable)
            unregisterVolumeObserver()
        }
        if (closeConnection) {
            closeCurrentConnection()
            if (rescan) {
                scanDevices()
            } else {
                _state.value = UsbAudioState.NoDevice
            }
        }
        forwardingPlayer?.notifyDeviceInfoChanged()
    }

    fun restoreHardwareVolumeOnRelease() {
        onUsbReleased(closeConnection = false)
    }

    fun logSystemAudioDevicesAndVolumeTable() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val devInfo = devices.joinToString("; ") { d ->
                val typeStr = when (d.type) {
                    AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
                    else -> "TYPE_${d.type}"
                }
                "name=${d.productName} id=${d.id} type=$typeStr(${d.type})"
            }
            PlayLog.log("usb", "System output audio devices: $devInfo")
            UsbRingLog.record("UsbAudioManager", "Output devices: $devInfo", UsbRingLog.CODE_GENERIC)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
                for (d in devices) {
                    if (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        val table = (min..max).joinToString(", ") { idx ->
                            val db = am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, idx, d.type)
                            "$idx:${"%.1f".format(db)}dB"
                        }
                        val typeStr = if (d.type == AudioDeviceInfo.TYPE_USB_DEVICE) "TYPE_USB_DEVICE" else "TYPE_USB_HEADSET"
                        PlayLog.log("usb", "getStreamVolumeDb table for $typeStr: [$table]")
                        UsbRingLog.record("UsbAudioManager", "Volume table for $typeStr: [$table]", UsbRingLog.CODE_GENERIC)
                    }
                }
            }
        }
    }

    fun closeCurrentConnection() {
        synchronized(connectionLock) {
            pendingPermissionJob?.let { mainHandler.removeCallbacks(it) }
            pendingPermissionJob = null
            try {
                // stopAndRelease restores DAC mute/volume while interface 0 is still claimed, then releases & reattaches.
                activeStreamer?.stopAndRelease()
            } catch (_: Exception) {}
            activeStreamer = null

            // Do NOT call restoreDefaultVolumeAndUnmute() here after stopAndRelease() (avoids unclaimed interface 0 transfers).
            hardwareVolume?.resetAppliedVolume()
            hardwareVolume = null
            activeConnection?.close()
            activeConnection = null
            forwardingPlayer?.notifyDeviceInfoChanged()
        }
    }

    private fun isAudioDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_AUDIO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    private fun getParcelableDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    companion object {
        @Volatile
        var instance: UsbAudioManager? = null

        @Volatile
        var onRequestRecordAudioPermission: (() -> Unit)? = null
    }
}
