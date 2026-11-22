package io.max.paperplay.player.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.max.paperplay.player.BitPerfectMode
import io.max.paperplay.player.BitPerfectPrefs
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import io.max.paperplay.player.PrefKeyValueStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Android host binding for [UnifiedUsbCoordinator].
 * - Zero `openDevice()` / zero native load / zero permission prompt while Bit-perfect is OFF.
 * - Enforces 25 s pause release timer & immediate release on screen-off while paused.
 * - Releases immediately on Bluetooth/speaker/wired route activation or hot-unplug.
 */
class UnifiedUsbDacManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val permissionAction = "${appContext.packageName}.UNIFIED_USB_PERMISSION"
    private var volumeReceiverRegistered = false

    private val spStore = object : PrefKeyValueStore {
        private val sp = appContext.getSharedPreferences("paperplay_prefs", Context.MODE_PRIVATE)
        override fun get(key: String): String? = if (sp.contains(key)) sp.getString(key, null) else null
        override fun set(key: String, value: String) { sp.edit().putString(key, value).apply() }
    }

    private val scheduler = object : PauseTimerScheduler {
        override fun schedule(delayMs: Long, action: () -> Unit): PauseTimerScheduler.Handle {
            val r = Runnable { action() }
            mainHandler.postDelayed(r, delayMs)
            return object : PauseTimerScheduler.Handle {
                override fun cancel() { mainHandler.removeCallbacks(r) }
            }
        }
    }

    private val systemOps = object : UsbSystemOps {
        override fun loadNativeLibrary() {
            if (coordinator.mode.value != BitPerfectMode.ON) return
            UsbEngineNative.ensureLoaded()
        }

        override fun openUsbDevice(deviceId: Int): Boolean {
            if (coordinator.mode.value != BitPerfectMode.ON) return false
            val dev = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return false
            val usb = UsbAudioManager.instance ?: UsbAudioManager(appContext).also { UsbAudioManager.instance = it }
            return usb.openDevice(dev)
        }

        override fun requestUsbPermission(deviceId: Int) {
            if (coordinator.mode.value != BitPerfectMode.ON) return
            val dev = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
            val pi = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(permissionAction).apply { setPackage(appContext.packageName) },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            usbManager.requestPermission(dev, pi)
        }

        override fun pauseGraceNative(handle: Long): Int =
            UnifiedUsbAudioSink.currentInstance?.pauseGraceFromCoordinator()
                ?: UsbEngineNative.sessionPauseGrace(handle)

        override fun resumeGraceNative(handle: Long): Int =
            UnifiedUsbAudioSink.currentInstance?.resumeGraceFromCoordinator()
                ?: UsbEngineNative.sessionStart(handle)

        override fun releaseClaimAndReattach(handle: Long) {
            val sink = UnifiedUsbAudioSink.currentInstance
            if (sink != null && sink.matchesSessionHandle(handle)) {
                sink.releaseFromCoordinator(handle)
            } else {
                UsbEngineNative.sessionClose(handle)
            }
            UsbAudioManager.instance?.onUsbReleased(closeConnection = true, rescan = false)
        }
    }

    val coordinator: UnifiedUsbCoordinator = UnifiedUsbCoordinator(
        prefs = spStore,
        ops = systemOps,
        scheduler = scheduler,
        initialMode = io.max.paperplay.player.PlatformBitPerfectPrefs.load(),
    )

    val status: StateFlow<BitPerfectStatus> get() = coordinator.status
    val pendingHint: StateFlow<DiscoveredUsbDacHint?> get() = coordinator.pendingHint

    var onStatusChanged: (() -> Unit)?
        get() = coordinator.onStatusChanged
        set(value) { coordinator.onStatusChanged = value }

    fun reopenActiveDeviceIfPermitted(): Boolean {
        if (coordinator.mode.value != BitPerfectMode.ON) return false
        val audioDev = usbManager.deviceList.values.firstOrNull { d ->
            val pairs = (0 until d.interfaceCount).map { idx ->
                val intf = d.getInterface(idx)
                intf.interfaceClass to intf.interfaceSubclass
            }
            UsbDiscoveryHintTracker.hasAudioStreamingInterface(pairs) && usbManager.hasPermission(d)
        } ?: return false
        UsbEngineNative.ensureLoaded()
        val usb = UsbAudioManager.instance ?: UsbAudioManager(appContext).also { UsbAudioManager.instance = it }
        val opened = usb.openDevice(audioDev, triggerReconfigure = false)
        if (opened) {
            usb.ensureNativeCapabilitiesProbed()
        }
        return opened
    }

    private var receiversRegistered = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION" &&
                coordinator.mode.value == BitPerfectMode.ON
            ) {
                val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (streamType == AudioManager.STREAM_MUSIC || streamType == -1) {
                    UnifiedUsbAudioSink.currentInstance?.syncSystemVolume(isUser = true)
                }
            }
        }
    }

    private fun updateVolumeReceiverRegistration() {
        val shouldRegister = receiversRegistered && coordinator.mode.value == BitPerfectMode.ON
        if (shouldRegister && !volumeReceiverRegistered) {
            volumeReceiverRegistered = true
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(volumeReceiver, filter)
            }
        } else if (!shouldRegister && volumeReceiverRegistered) {
            volumeReceiverRegistered = false
            runCatching { appContext.unregisterReceiver(volumeReceiver) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> coordinator.onScreenOff()
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> coordinator.onRouteChanged(nonUsbRouteActive = true)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanClassOnly()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev = getParcelableDevice(intent)
                    dev?.let { coordinator.onHotUnplug(it.deviceId) }
                    scanClassOnly()
                }
                permissionAction -> {
                    scanClassOnly()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && coordinator.mode.value == BitPerfectMode.ON &&
                        UnifiedUsbAudioSink.currentInstance?.isUsbModeActive != true
                    ) {
                        PlaybackCore.onRebuildPlayerForBitPerfect?.invoke()
                    }
                }
            }
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val nonUsbAdded = addedDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            if (nonUsbAdded && coordinator.mode.value == BitPerfectMode.ON) {
                PlayLog.log("usb", "UnifiedUsbDacManager: non-USB audio route added; releasing claim")
                coordinator.onRouteChanged(nonUsbRouteActive = true)
            }
        }
    }

    fun start() {
        if (!receiversRegistered) {
            receiversRegistered = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(permissionAction)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            audioManager.registerAudioDeviceCallback(audioCallback, mainHandler)
        }
        updateVolumeReceiverRegistration()
        scanClassOnly()
    }

    fun stop() {
        if (receiversRegistered) {
            receiversRegistered = false
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { audioManager.unregisterAudioDeviceCallback(audioCallback) }
        }
        updateVolumeReceiverRegistration()
        coordinator.releaseClaim("Manager stopped")
    }

    /** Metadata-only scan from [UsbManager.getDeviceList]; zero openDevice() calls when OFF. */
    fun scanClassOnly() {
        val list = usbManager.deviceList.values.map { d ->
            val pairs = (0 until d.interfaceCount).map { idx ->
                val intf = d.getInterface(idx)
                intf.interfaceClass to intf.interfaceSubclass
            }
            UsbDeviceMeta(
                deviceId = d.deviceId,
                vendorId = d.vendorId,
                productId = d.productId,
                productName = d.productName,
                hasPermission = if (coordinator.mode.value == BitPerfectMode.OFF) false else usbManager.hasPermission(d),
                interfaceClassSubclassPairs = pairs,
            )
        }
        coordinator.scanDevices(list)
    }

    fun onModeChanged(mode: BitPerfectMode) {
        coordinator.onModeChanged(mode)
        updateVolumeReceiverRegistration()
        scanClassOnly()
    }

    fun dismissHint(vidPid: String) = coordinator.dismissHint(vidPid)
    fun onSessionOpened(handle: Long, deviceId: Int, deviceName: String?) =
        coordinator.onNativeSessionOpened(handle, deviceId, deviceName)
    fun onSessionClosed(handle: Long) = coordinator.onNativeSessionClosed(handle)
    fun onSpeedBypass(speed: Float) = coordinator.onSpeedBypass(speed)
    fun onStreamingActive(sampleRateHz: Int, bitDepth: Int, deviceName: String?) =
        coordinator.onStreamingActive(sampleRateHz, bitDepth, deviceName)
    fun onPlaybackStarted() = coordinator.onPlaybackStarted()
    fun onPlaybackPaused() = coordinator.onPlaybackPaused()
    fun onPlaybackResumed(): Boolean = coordinator.onPlaybackResumed()
    fun onSinkFallback(reason: String) = coordinator.releaseClaim(reason)

    private fun getParcelableDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        @Volatile
        var instance: UnifiedUsbDacManager? = null
    }
}
