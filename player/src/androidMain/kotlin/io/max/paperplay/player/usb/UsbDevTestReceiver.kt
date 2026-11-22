package io.max.paperplay.player.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import io.max.paperplay.player.AndroidUsbDacBackend
import io.max.paperplay.player.BitPerfectStatus
import io.max.paperplay.player.DelegateSinkDiag
import io.max.paperplay.player.PlayLog
import io.max.paperplay.player.PlaybackCore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Debug-only wireless adb verification harness registered solely in `androidApp/src/debug/AndroidManifest.xml`.
 * Guarded by `FLAG_DEBUGGABLE` at runtime and never shipped/exported in release builds.
 */
class UsbDevTestReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DEV_USB_CMD = "io.max.paperplay.DEV_USB_CMD"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        if (intent.action != ACTION_DEV_USB_CMD) return

        val cmd = intent.getStringExtra("cmd")?.trim().orEmpty()
        PlayLog.log("usb", "DEV_USB_CMD received: cmd=$cmd")

        when (cmd) {
            "arm_pcm_dump" -> {
                val dur = intent.getIntExtra("duration_sec", 30)
                UsbPcmDumper.enable(context.cacheDir, dur)
            }
            "play_files" -> {
                val paths = intent.getStringExtra("paths")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                if (paths.isEmpty()) return
                PlaybackCore.devDirectTestActive = true
                val player = PlaybackCore.activePlayer ?: return
                Handler(player.applicationLooper).post {
                    val items = paths.map { MediaItem.fromUri(Uri.fromFile(File(it))) }
                    player.setMediaItems(items, 0, 0L)
                    player.prepare()
                    player.play()
                }
            }
            "clear_dev_test" -> {
                PlaybackCore.devDirectTestActive = false
            }
            "set_speed" -> {
                val speed = intent.getFloatExtra("speed", 1.0f)
                PlaybackCore.speed = speed
                val player = PlaybackCore.activePlayer ?: return
                Handler(player.applicationLooper).post {
                    player.setPlaybackSpeed(speed)
                }
            }
            "transport" -> {
                val action = intent.getStringExtra("action").orEmpty()
                val posMs = intent.getLongExtra("pos_ms", 0L)
                val player = PlaybackCore.activePlayer ?: return
                Handler(player.applicationLooper).post {
                    when (action) {
                        "next" -> player.seekToNextMediaItem()
                        "prev" -> player.seekToPreviousMediaItem()
                        "seek" -> player.seekTo(posMs)
                        "pause" -> player.pause()
                        "play" -> player.play()
                        "stop" -> player.stop()
                    }
                }
            }
            "set_bit_perfect" -> {
                val on = intent.getBooleanExtra("enabled", false)
                Handler(Looper.getMainLooper()).post {
                    AndroidUsbDacBackend.setUsbExclusive(on)
                }
            }
            "set_unified" -> {
                val on = intent.getBooleanExtra("enabled", true)
                Handler(Looper.getMainLooper()).post {
                    AndroidUsbDacBackend.setUseUnifiedBitPerfect(on)
                }
            }
            "status" -> writeStatusSnapshot(context)
            "read_dac_baseline" -> {
                val pr = goAsync()
                Thread {
                    try {
                        readDacBaselineOnly(context.applicationContext)
                    } finally {
                        pr.finish()
                    }
                }.start()
            }
            "volume_audit" -> {
                val pr = goAsync()
                Thread {
                    try {
                        runVolumeAudit(context.applicationContext)
                    } finally {
                        pr.finish()
                    }
                }.start()
            }
        }
    }

    private fun writeStatusSnapshot(context: Context) {
        val unified = UnifiedUsbAudioSink.currentInstance
        val legacy = UsbAudioSink.currentInstance
        val stats = unified?.getStatsSnapshot() ?: UsbAudioManager.instance?.getStreamerStats()
        val mgrStatus = UnifiedUsbDacManager.instance?.status?.value
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val dacPlugged = um?.deviceList?.values?.any { d ->
            val pairs = (0 until d.interfaceCount).map { i ->
                val intf = d.getInterface(i)
                intf.interfaceClass to intf.interfaceSubclass
            }
            UsbDiscoveryHintTracker.hasAudioStreamingInterface(pairs)
        } == true
        val phase0 = DelegateSinkDiag.formatPhase0Summary(
            buildId = "debug",
            dacPlugged = dacPlugged,
            bitPerfectOn = PlaybackCore.usbExclusiveMode,
        )
        val out = buildString {
            appendLine("activePlayerReady=${PlaybackCore.activePlayer != null}")
            appendLine("useUnifiedBitPerfect=${PlaybackCore.useUnifiedBitPerfect}")
            appendLine("usbExclusiveMode=${PlaybackCore.usbExclusiveMode}")
            appendLine("usbActive=${PlaybackCore.usbActive}")
            appendLine("usbStatus=${PlaybackCore.usbStatus}")
            appendLine("speed=${PlaybackCore.speed}")
            appendLine("playerPlaybackState=${PlaybackCore.playerPlaybackState}")
            appendLine("playerIsPlaying=${PlaybackCore.playerIsPlaying}")
            appendLine("unifiedModeActive=${unified?.isUsbModeActive == true}")
            appendLine("unifiedRate=${unified?.confirmedRateHz ?: 0}")
            appendLine("unifiedBitDepth=${unified?.confirmedBitDepth ?: 0}")
            appendLine("unifiedAlt=${unified?.confirmedAlt ?: -1}")
            appendLine("unifiedInPlace=${unified?.lastConfigureWasInPlace == true}")
            appendLine("unifiedVolReadbackRaw=${unified?.lastVolumeReadbackRaw ?: 0}")
            appendLine("unifiedSubslotBytes=${unified?.lastSubslotBytes ?: 0}")
            appendLine("legacyMode=${legacy?.activeMode}")
            appendLine("framesConsumed=${stats?.framesConsumed ?: 0L}")
            appendLine("underruns=${stats?.underruns ?: 0L}")
            appendLine("urbErrors=${stats?.urbErrors ?: 0L}")
            appendLine("ringFillBytes=${stats?.ringFillBytes ?: 0L}")
            appendLine("isRunning=${stats?.isRunning ?: false}")
            appendLine("statsRate=${stats?.sampleRate ?: 0}")
            appendLine("isPaused=${stats?.isPaused ?: false}")
            appendLine("bitPerfectStatus=${mgrStatus ?: BitPerfectStatus.Off}")
            appendLine("pcmDumpStatus=${UsbPcmDumper.status}")
            appendLine("pcmDumpPath=${UsbPcmDumper.dumpFilePath ?: ""}")
            appendLine("phase0=$phase0")
            val nlog = if (UsbEngineNative.isAlreadyLoaded) UsbEngineNative.getNativeLog().orEmpty() else ""
            appendLine("---NATIVE_RING_LOG---")
            appendLine(nlog.lines().takeLast(30).joinToString("\n"))
        }
        runCatching { File(context.cacheDir, "usb_dev_status.txt").writeText(out) }
    }

    private fun runVolumeAudit(context: Context) {
        val sb = StringBuilder()
        sb.appendLine("=== PAPERPLAY USB VOLUME AUDIT ===")
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamIdxBefore = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxIdx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val safeSystemIdx = streamIdxBefore.coerceIn(0, maxIdx)
        sb.appendLine("streamMusicIdxBefore=$streamIdxBefore/$maxIdx")

        // Ensure playback is paused cleanly and no active claim collides.
        PlaybackCore.activePlayer?.let { p ->
            val latch = CountDownLatch(1)
            Handler(p.applicationLooper).post {
                p.pause()
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
        }
        Thread.sleep(300L)
        UnifiedUsbDacManager.instance?.coordinator?.releaseClaim("Volume audit")
        UsbAudioSink.currentInstance?.onExclusiveModeChanged(false)
        UsbAudioManager.instance?.onUsbExclusiveModeChanged(false)
        Thread.sleep(350L)

        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i ->
                d.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
            }
        }
        if (dev == null) {
            sb.appendLine("ERROR: no_usb_dac")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }
        if (!um.hasPermission(dev)) {
            sb.appendLine("ERROR: no_usb_permission")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }

        UsbEngineNative.ensureLoaded()
        val conn1 = um.openDevice(dev)
        if (conn1 == null) {
            sb.appendLine("ERROR: openDevice_failed")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }
        val rawDesc = conn1.rawDescriptors ?: byteArrayOf()
        val desc = UsbDescriptorParser.parse(rawDesc)
        val alt = desc.playbackAltSettings.firstOrNull()
        val fu = alt?.let { desc.resolvePlaybackFeatureUnit(it) }
        if (alt == null || fu == null) {
            conn1.close()
            sb.appendLine("ERROR: no_playback_fu")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }

        val caps = UsbEngineNative.parseCapabilities(conn1.fileDescriptor, desc)
        conn1.close()
        Thread.sleep(200L)

        val hwVol = UsbHardwareVolume(
            uacVersion = desc.uacVersion,
            controlInterfaceId = desc.acInterfaceNumber,
            featureUnit = fu,
            caps = caps,
        )
        val volChannels = when {
            fu.hasMasterVolume -> listOf(0)
            fu.hasChannelVolume -> listOf(1, 2)
            else -> listOf(0)
        }
        val minDb = hwVol.minRaw.toFloat() / 256.0f
        val maxDb = hwVol.maxRaw.toFloat() / 256.0f
        val resDb = hwVol.resRaw.toFloat() / 256.0f
        sb.appendLine("dac=${dev.productName} minRaw=${hwVol.minRaw}($minDb dB) maxRaw=${hwVol.maxRaw}($maxDb dB) resRaw=${hwVol.resRaw}($resDb dB) chs=$volChannels")

        val conn2 = um.openDevice(dev) ?: run {
            sb.appendLine("ERROR: reopen_claim1_failed")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }
        val clockId = desc.resolvePlaybackClockSourceId(alt) ?: 9
        val isUac2 = desc.uacVersion == UacVersion.UAC_2_0
        val handle1 = UsbEngineNative.sessionOpen(
            conn2.fileDescriptor, desc.acInterfaceNumber, alt.interfaceNumber,
            isUac2, clockId, true, fu.id,
        )
        if (handle1 <= 0L) {
            conn2.close()
            sb.appendLine("ERROR: sessionOpen1_failed")
            File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
            return
        }

        fun decodeRawVol(rawOrErr: Int): Int = if (rawOrErr >= 0) rawOrErr.toShort().toInt() else rawOrErr

        // Pre-session GET_CUR before ANY SET_CUR
        val preVols = volChannels.associateWith { ch -> decodeRawVol(UsbEngineNative.sessionReadVolume(handle1, fu.id, desc.acInterfaceNumber, ch)) }
        val preMutes = (listOf(0) + volChannels).distinct().associateWith { ch -> UsbEngineNative.sessionReadMute(handle1, fu.id, desc.acInterfaceNumber, ch) }
        sb.appendLine("preSessionVol=$preVols preSessionMute=$preMutes")

        // Also record active safe index comparison target vs Android HAL dB table
        val androidUsbDb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (0..maxIdx).associateWith { i ->
                runCatching { am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, i, AudioDeviceInfo.TYPE_USB_HEADSET) }.getOrNull()
            }
        } else {
            emptyMap()
        }

        var monotonicOk = true
        var maxJumpDb = 0.0f
        var muteAt0Ok = false
        var unmutedAt1Ok = false
        var unmutedAtMaxOk = false
        var prevDb: Float? = null
        var maxDeltaRaw = 0
        sb.appendLine("---SWEEP---")
        for (idx in 0..maxIdx) {
            val sysDb = androidUsbDb[idx]
            if (idx == 0) {
                val targetRaw = hwVol.minRaw.toInt()
                for (ch in volChannels) {
                    UsbEngineNative.sessionApplyVolume(handle1, fu.id, desc.acInterfaceNumber, ch, targetRaw, true)
                }
                val readVol = decodeRawVol(UsbEngineNative.sessionReadVolume(handle1, fu.id, desc.acInterfaceNumber, volChannels.first()))
                val readMute = UsbEngineNative.sessionReadMute(handle1, fu.id, desc.acInterfaceNumber, 0).let {
                    if (it < 0) UsbEngineNative.sessionReadMute(handle1, fu.id, desc.acInterfaceNumber, volChannels.first()) else it
                }
                muteAt0Ok = (readMute == 1)
                sb.appendLine("idx=$idx androidDb=$sysDb target=MUTE($targetRaw) readRaw=$readVol mute=$readMute")
                continue
            }
            val db = UsbVolumeCurve.computeVolumeDb(idx, maxIdx, minDb, maxDb, resDb)
            val raw = UsbVolumeCurve.dbToRaw(db, hwVol.minRaw, hwVol.maxRaw, hwVol.resRaw).toInt()
            var stepReadRaw = 0
            for (ch in volChannels) {
                val rc = UsbEngineNative.sessionApplyVolume(handle1, fu.id, desc.acInterfaceNumber, ch, raw, false)
                val signed = decodeRawVol(rc)
                if (ch == volChannels.first()) stepReadRaw = signed
                maxDeltaRaw = maxOf(maxDeltaRaw, abs(signed - raw))
            }
            val readDb = stepReadRaw / 256.0f
            val prev = prevDb
            val stepJump = if (prev != null) readDb - prev else 0f
            if (prev != null) {
                if (readDb < prev - 0.01f) monotonicOk = false
                if (idx >= 2) maxJumpDb = maxOf(maxJumpDb, abs(stepJump))
            }
            prevDb = readDb
            var muteStr = ""
            if (idx == 1 || idx == maxIdx) {
                val m = UsbEngineNative.sessionReadMute(handle1, fu.id, desc.acInterfaceNumber, 0).let {
                    if (it < 0) UsbEngineNative.sessionReadMute(handle1, fu.id, desc.acInterfaceNumber, volChannels.first()) else it
                }
                if (idx == 1) unmutedAt1Ok = (m == 0)
                if (idx == maxIdx) unmutedAtMaxOk = (m == 0)
                muteStr = " mute=$m"
            }
            sb.appendLine("idx=$idx androidDb=$sysDb targetDb=$db targetRaw=$raw readRaw=$stepReadRaw readDb=$readDb jumpDb=$stepJump$muteStr")
        }

        // Release claim1 (triggers native baseline restore -> alt 0 -> release -> uaudio_connect)
        UsbEngineNative.sessionClose(handle1)
        conn2.close()
        Thread.sleep(350L)

        val nativeAfterRelease = UsbEngineNative.getNativeLog().orEmpty().lines().takeLast(40)

        // Open clean claim2 without calling SET_CUR to confirm DAC baseline was restored before release
        var restoredVols: Map<Int, Int> = emptyMap()
        var restoredMutes: Map<Int, Int> = emptyMap()
        val conn3 = um.openDevice(dev)
        if (conn3 != null) {
            val handle2 = UsbEngineNative.sessionOpen(
                conn3.fileDescriptor, desc.acInterfaceNumber, alt.interfaceNumber,
                isUac2, clockId, true, fu.id,
            )
            if (handle2 > 0L) {
                restoredVols = volChannels.associateWith { ch -> decodeRawVol(UsbEngineNative.sessionReadVolume(handle2, fu.id, desc.acInterfaceNumber, ch)) }
                restoredMutes = (listOf(0) + volChannels).distinct().associateWith { ch -> UsbEngineNative.sessionReadMute(handle2, fu.id, desc.acInterfaceNumber, ch) }
                UsbEngineNative.sessionClose(handle2)
            }
            conn3.close()
        }

        val streamIdxAfter = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val restoredVolOk = restoredVols == preVols
        val restoredMuteOk = restoredMutes == preMutes
        val cliffDetected = maxJumpDb > 6.0f
        val safeIdxBitPerfectDb = UsbVolumeCurve.computeVolumeDb(safeSystemIdx, maxIdx, minDb, maxDb, resDb)
        val safeIdxAndroidDb = androidUsbDb[safeSystemIdx]
        sb.appendLine("restoredVol=$restoredVols restoredMute=$restoredMutes")
        sb.appendLine("---NATIVE_LOG_AFTER_RELEASE---")
        nativeAfterRelease.forEach { sb.appendLine(it) }
        sb.appendLine(
            "SUMMARY monotonic=$monotonicOk cliffDetected=$cliffDetected maxJumpDb=$maxJumpDb maxDeltaRaw=$maxDeltaRaw " +
                "muteAt0Ok=$muteAt0Ok unmutedAt1Ok=$unmutedAt1Ok unmutedAtMaxOk=$unmutedAtMaxOk " +
                "restoredMatchesPreSession=${restoredVolOk && restoredMuteOk} restoredVolOk=$restoredVolOk restoredMuteOk=$restoredMuteOk " +
                "safeIdx=$safeSystemIdx/$maxIdx streamAfter=$streamIdxAfter androidDbAtSafeIdx=$safeIdxAndroidDb bitPerfectDbAtSafeIdx=$safeIdxBitPerfectDb preSessionRaw=$preVols"
        )
        File(context.cacheDir, "usb_volume_audit.txt").writeText(sb.toString())
        PlayLog.log("usb", "DEV_VOLUME_AUDIT_DONE monotonic=$monotonicOk restored=$restoredVolOk streamIdx=$streamIdxAfter")
    }

    private fun readDacBaselineOnly(context: Context) {
        val outFile = File(context.cacheDir, "usb_dac_baseline.txt")
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { i ->
                d.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
            }
        }
        if (dev == null || !um.hasPermission(dev)) {
            outFile.writeText("ERROR: unavailable")
            return
        }
        UsbEngineNative.ensureLoaded()
        val conn = um.openDevice(dev) ?: run {
            outFile.writeText("ERROR: open_failed")
            return
        }
        val desc = UsbDescriptorParser.parse(conn.rawDescriptors ?: byteArrayOf())
        val alt = desc.playbackAltSettings.firstOrNull()
        val fu = alt?.let { desc.resolvePlaybackFeatureUnit(it) }
        if (alt == null || fu == null) {
            conn.close()
            outFile.writeText("ERROR: no_fu")
            return
        }
        val clockId = desc.resolvePlaybackClockSourceId(alt) ?: 9
        val h = UsbEngineNative.sessionOpen(
            conn.fileDescriptor, desc.acInterfaceNumber, alt.interfaceNumber,
            desc.uacVersion == UacVersion.UAC_2_0, clockId, true, fu.id,
        )
        if (h <= 0L) {
            conn.close()
            outFile.writeText("ERROR: session_open_failed")
            return
        }
        val chs = when {
            fu.hasMasterVolume -> listOf(0)
            fu.hasChannelVolume -> listOf(1, 2)
            else -> listOf(0)
        }
        val vols = chs.associateWith { ch ->
            UsbEngineNative.sessionReadVolume(h, fu.id, desc.acInterfaceNumber, ch)
                .let { if (it >= 0) it.toShort().toInt() else it }
        }
        val mutes = (listOf(0) + chs).distinct().associateWith { ch -> UsbEngineNative.sessionReadMute(h, fu.id, desc.acInterfaceNumber, ch) }
        UsbEngineNative.sessionClose(h)
        conn.close()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val volDb = vols.mapValues { (_, v) -> v / 256.0f }
        outFile.writeText("DAC_BASELINE sysIdx=$idx/$max raw=$vols db=$volDb mute=$mutes")
    }
}
