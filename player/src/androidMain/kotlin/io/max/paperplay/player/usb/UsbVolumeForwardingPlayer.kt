package io.max.paperplay.player.usb

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import io.max.paperplay.player.PlaybackCore
import java.util.concurrent.CopyOnWriteArraySet

/**
 * [ForwardingPlayer] that exposes [DeviceInfo.PLAYBACK_TYPE_LOCAL] (never remote/Cast) and routes
 * MediaSession volume controls to [AudioManager.STREAM_MUSIC] and the UAC hardware feature unit
 * while USB exclusive mode is active. When USB exclusive mode is off, delegates 1:1 to [player].
 */
class UsbVolumeForwardingPlayer(
    player: Player,
    private val context: Context,
) : ForwardingPlayer(player) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val customListeners = CopyOnWriteArraySet<Player.Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isUsbMode(): Boolean = PlaybackCore.usbExclusiveMode || PlaybackCore.usbActive

    override fun play() {
        PlaybackCore.playIntentTracker.onExplicitUserCommand(true)
        super.play()
    }

    override fun pause() {
        PlaybackCore.playIntentTracker.onExplicitUserCommand(false)
        super.pause()
    }

    override fun stop() {
        PlaybackCore.playIntentTracker.onExplicitUserCommand(false)
        super.stop()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        PlaybackCore.playIntentTracker.onExplicitUserCommand(playWhenReady)
        super.setPlayWhenReady(playWhenReady)
    }

    override fun addListener(listener: Player.Listener) {
        customListeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        customListeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getDeviceInfo(): DeviceInfo {
        if (!isUsbMode()) return super.getDeviceInfo()
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
        val minVol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager?.getStreamMinVolume(AudioManager.STREAM_MUSIC) ?: 0
        } else {
            0
        }
        return DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL)
            .setMinVolume(minVol)
            .setMaxVolume(maxVol)
            .build()
    }

    override fun getDeviceVolume(): Int {
        if (!isUsbMode()) return super.getDeviceVolume()
        return audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    }

    override fun isDeviceMuted(): Boolean {
        if (!isUsbMode()) return super.isDeviceMuted()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager?.isStreamMute(AudioManager.STREAM_MUSIC) == true ||
                UsbAudioManager.instance?.hardwareVolume?.isMuted == true
        } else {
            UsbAudioManager.instance?.hardwareVolume?.isMuted == true
        }
    }

    override fun setDeviceVolume(volume: Int) {
        if (!isUsbMode()) {
            super.setDeviceVolume(volume)
            return
        }
        setDeviceVolume(volume, AudioManager.FLAG_SHOW_UI)
    }

    override fun setDeviceVolume(volume: Int, flags: Int) {
        if (!isUsbMode()) {
            super.setDeviceVolume(volume, flags)
            return
        }
        val am = audioManager ?: return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (cur == volume) {
            return // Dedupe repeated writes of the same index
        }
        val caller = Throwable().stackTrace.take(6).joinToString(" -> ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        io.max.paperplay.player.PlayLog.log("volume", "UsbVolumeForwardingPlayer.setDeviceVolume: $cur -> $volume flags=$flags from $caller")
        UsbRingLog.record("Volume", "setDeviceVolume $cur->$volume from $caller", UsbRingLog.CODE_GENERIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, flags)
        UsbAudioManager.instance?.onSystemVolumeChanged(isUser = true)
        notifyVolumeChanged()
    }

    override fun increaseDeviceVolume() {
        if (!isUsbMode()) {
            super.increaseDeviceVolume()
            return
        }
        increaseDeviceVolume(AudioManager.FLAG_SHOW_UI)
    }

    override fun increaseDeviceVolume(flags: Int) {
        if (!isUsbMode()) {
            super.increaseDeviceVolume(flags)
            return
        }
        val caller = Throwable().stackTrace.take(6).joinToString(" -> ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val cur = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        io.max.paperplay.player.PlayLog.log("volume", "UsbVolumeForwardingPlayer.increaseDeviceVolume: cur=$cur flags=$flags from $caller")
        UsbRingLog.record("Volume", "increaseDeviceVolume cur=$cur from $caller", UsbRingLog.CODE_GENERIC)
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, flags)
        UsbAudioManager.instance?.onSystemVolumeChanged(isUser = true)
        notifyVolumeChanged()
    }

    override fun decreaseDeviceVolume() {
        if (!isUsbMode()) {
            super.decreaseDeviceVolume()
            return
        }
        decreaseDeviceVolume(AudioManager.FLAG_SHOW_UI)
    }

    override fun decreaseDeviceVolume(flags: Int) {
        if (!isUsbMode()) {
            super.decreaseDeviceVolume(flags)
            return
        }
        val caller = Throwable().stackTrace.take(6).joinToString(" -> ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val cur = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        io.max.paperplay.player.PlayLog.log("volume", "UsbVolumeForwardingPlayer.decreaseDeviceVolume: cur=$cur flags=$flags from $caller")
        UsbRingLog.record("Volume", "decreaseDeviceVolume cur=$cur from $caller", UsbRingLog.CODE_GENERIC)
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, flags)
        UsbAudioManager.instance?.onSystemVolumeChanged(isUser = true)
        notifyVolumeChanged()
    }

    fun adjustDeviceVolume(direction: Int, flags: Int = AudioManager.FLAG_SHOW_UI) {
        if (direction == 0 || direction == AudioManager.ADJUST_SAME) {
            return
        }
        if (direction > 0) {
            increaseDeviceVolume(flags)
        } else {
            decreaseDeviceVolume(flags)
        }
    }

    override fun setDeviceMuted(muted: Boolean) {
        if (!isUsbMode()) {
            super.setDeviceMuted(muted)
            return
        }
        setDeviceMuted(muted, AudioManager.FLAG_SHOW_UI)
    }

    override fun setDeviceMuted(muted: Boolean, flags: Int) {
        if (!isUsbMode()) {
            super.setDeviceMuted(muted, flags)
            return
        }
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags)
        UsbAudioManager.instance?.onSystemVolumeChanged(isUser = true)
        notifyVolumeChanged()
    }

    override fun getAvailableCommands(): Player.Commands {
        if (!isUsbMode()) return super.getAvailableCommands()
        val base = super.getAvailableCommands()
        return base.buildUpon()
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME)
            .add(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        if (!isUsbMode()) return super.isCommandAvailable(command)
        return when (command) {
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_ADJUST_DEVICE_VOLUME,
            Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS -> true
            else -> super.isCommandAvailable(command)
        }
    }

    fun notifyVolumeChanged() {
        val runnable = Runnable {
            val vol = getDeviceVolume()
            val muted = isDeviceMuted()
            for (l in customListeners) {
                l.onDeviceVolumeChanged(vol, muted)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    fun notifyDeviceInfoChanged() {
        val runnable = Runnable {
            val info = getDeviceInfo()
            for (l in customListeners) {
                l.onDeviceInfoChanged(info)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }
}
