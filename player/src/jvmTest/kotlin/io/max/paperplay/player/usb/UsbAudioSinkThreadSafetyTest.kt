package io.max.paperplay.player.usb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbAudioSinkThreadSafetyTest {

    @Test
    fun testUsbAudioSinkNeverCallsPlayerMethodsDirectly() {
        val candidates = listOf(
            File("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioSink.kt"),
            File("../player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioSink.kt")
        )
        val sinkFile = candidates.firstOrNull { it.exists() }
        assertTrue(sinkFile != null, "UsbAudioSink.kt must exist")

        val sinkContent = sinkFile.readText()

        // 1. Must never access PlaybackCore.activePlayer
        assertFalse(
            sinkContent.contains("activePlayer"),
            "UsbAudioSink must never access PlaybackCore.activePlayer directly"
        )

        // 2. Must never call ExoPlayer/Player methods
        val forbiddenMethods = listOf(
            ".playbackState",
            ".playWhenReady",
            ".currentPosition",
            ".currentMediaItem",
            ".seekTo(",
            ".prepare()"
        )
        for (method in forbiddenMethods) {
            assertFalse(
                sinkContent.contains(method),
                "UsbAudioSink must never call Player method $method (thread safety violation)"
            )
        }

        // 3. Must use Volatile playerPlaybackState / playerPlayWhenReady
        assertTrue(
            sinkContent.contains("PlaybackCore.playerPlaybackState"),
            "UsbAudioSink must read player state from PlaybackCore.playerPlaybackState"
        )
        assertTrue(
            sinkContent.contains("PlaybackCore.playerPlayWhenReady"),
            "UsbAudioSink must read playWhenReady from PlaybackCore.playerPlayWhenReady"
        )
    }

    @Test
    fun testUsbAudioManagerNeverCallsPlayerDirectly() {
        val candidates = listOf(
            File("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt"),
            File("../player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt")
        )
        val managerFile = candidates.firstOrNull { it.exists() }
        assertTrue(managerFile != null, "UsbAudioManager.kt must exist")

        val content = managerFile.readText()
        assertFalse(
            content.contains("PlaybackCore.activePlayer"),
            "UsbAudioManager must not access PlaybackCore.activePlayer"
        )
    }

    @Test
    fun testUsbAudioSinkInDelegateModeHasNoBlockingLatchesAndDirectlyForwards() {
        val candidates = listOf(
            File("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioSink.kt"),
            File("../player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioSink.kt")
        )
        val sinkFile = candidates.firstOrNull { it.exists() }
        assertTrue(sinkFile != null, "UsbAudioSink.kt must exist")

        val content = sinkFile.readText()

        // 1. Must never contain blocking latches or route wait synchronization
        assertFalse(
            content.contains("CountDownLatch"),
            "UsbAudioSink must not contain CountDownLatch (causes playback stutter/starvation)"
        )
        assertFalse(
            content.contains("routeWaitLatch"),
            "UsbAudioSink must not contain routeWaitLatch"
        )
        assertFalse(
            content.contains("ensureDelegateReady"),
            "UsbAudioSink must not block in ensureDelegateReady"
        )
        assertFalse(
            content.contains("Thread.sleep"),
            "UsbAudioSink must never call Thread.sleep"
        )

        // 2. All methods in DELEGATE mode must forward directly to super
        val requiredDelegatePassThroughs = listOf(
            "activeMode == ActiveSinkMode.DELEGATE",
            "super.supportsFormat(format)",
            "super.getFormatSupport(format)",
            "super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)",
            "super.getCurrentPositionUs(sourceEnded)",
            "super.play()",
            "super.pause()",
            "super.flush()",
            "super.reset()",
            "super.playToEndOfStream()",
            "super.isEnded()",
            "super.hasPendingData()",
            "super.setVolume(volume)"
        )

        for (passThrough in requiredDelegatePassThroughs) {
            assertTrue(
                content.contains(passThrough),
                "UsbAudioSink must contain delegate pass-through '$passThrough'"
            )
        }

        // 3. Must contain periodic diagnostic logging for DELEGATE mode (without Binder IPC on audio thread)
        assertTrue(
            content.contains("logDelegateDiagnostics"),
            "UsbAudioSink must implement logDelegateDiagnostics"
        )
        assertTrue(
            content.contains("DelegateSinkDiag.maybeSampleAndEmit"),
            "logDelegateDiagnostics must emit zero-IPC per-second counters via DelegateSinkDiag"
        )
        assertFalse(
            content.contains("activePlaybackConfigurations"),
            "UsbAudioSink must not call AudioManager.activePlaybackConfigurations Binder IPC on the audio thread"
        )

        // 4. AudioSink.Listener must forward track lifecycle & session id callbacks 1:1
        assertTrue(content.contains("onAudioTrackInitialized"), "UsbAudioSink must forward onAudioTrackInitialized")
        assertTrue(content.contains("onAudioTrackReleased"), "UsbAudioSink must forward onAudioTrackReleased")
        assertTrue(content.contains("onAudioSessionIdChanged"), "UsbAudioSink must forward onAudioSessionIdChanged")
    }

    @Test
    fun testUsbHardwareVolumeNeverIssuesUnclaimedControlTransfers() {
        val candidates = listOf(
            File("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbHardwareVolume.kt"),
            File("../player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbHardwareVolume.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "UsbHardwareVolume.kt must exist")
        val content = file.readText()
        assertFalse(
            content.contains("conn.controlTransfer("),
            "UsbHardwareVolume must never execute unclaimed conn.controlTransfer on interface 0"
        )
    }

    @Test
    fun testUsbAudioManagerNeverOpensOrProbesWhenExclusiveModeOff() {
        val candidates = listOf(
            File("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt"),
            File("../player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue(file != null, "UsbAudioManager.kt must exist")
        val content = file.readText()
        assertTrue(
            content.contains("if (!PlaybackCore.usbExclusiveMode && !forceForProbe)"),
            "UsbAudioManager.openDevice must guard against opening DAC when usbExclusiveMode is false"
        )
        assertTrue(
            content.contains("fun ensureNativeCapabilitiesProbed()"),
            "UsbAudioManager must expose lazy native capability probe"
        )
    }

    @Test
    fun testRegressionChecklistInvariantsHoldAcrossUsbStack() {
        fun readSrc(relPath: String): String {
            val f = listOf(File(relPath), File("../$relPath")).firstOrNull { it.exists() }
            assertTrue(f != null, "$relPath must exist")
            return f.readText()
        }

        // Item 1: UsbAudioSink supportsFormat/getFormatSupport must not depend on transient activeMode
        val sinkSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioSink.kt")
        val supportsBlock = sinkSrc.substringAfter("override fun supportsFormat(").substringBefore("override fun getFormatSupport(")
        val getSupportBlock = sinkSrc.substringAfter("override fun getFormatSupport(").substringBefore("override fun configure(")
        assertFalse(supportsBlock.contains("activeMode"), "supportsFormat must not depend on transient activeMode")
        assertFalse(getSupportBlock.contains("activeMode"), "getFormatSupport must not depend on transient activeMode")

        // Item 2 & thread-safety: onExclusiveModeChanged and applyPendingReconfigure must not call super.configure/reconfigureUsbStateMachine on main thread
        fun stripLineComments(s: String) = s.lines().joinToString("\n") { it.substringBefore("//") }
        val onToggleBlock = stripLineComments(sinkSrc.substringAfter("fun onExclusiveModeChanged(").substringBefore("private fun switchToDelegateFallback("))
        assertFalse(onToggleBlock.contains("super.configure("), "onExclusiveModeChanged must not call super.configure from main thread")
        val applyPendingBlock = stripLineComments(sinkSrc.substringAfter("fun applyPendingReconfigure(").substringBefore("private fun reconfigureUsbStateMachine("))
        assertFalse(applyPendingBlock.contains("reconfigureUsbStateMachine("), "applyPendingReconfigure must not mutate sink from main thread")

        // Item 4 & lazy loading: UsbEngineNative loads standalone libusb_audio.so, never pulling libusb into libengine.so
        val nativeSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbEngineNative.kt")
        assertTrue(nativeSrc.contains("System.loadLibrary(\"usb_audio\")"), "UsbEngineNative must load standalone usb_audio library")
        assertFalse(nativeSrc.contains("System.loadLibrary(\"engine\")"), "UsbEngineNative must not load libengine")

        // Item 6: UsbVolumeForwardingPlayer exposes PLAYBACK_TYPE_LOCAL (never REMOTE) and tracks listeners
        val fwdSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbVolumeForwardingPlayer.kt")
        assertTrue(fwdSrc.contains("DeviceInfo.PLAYBACK_TYPE_LOCAL"), "UsbVolumeForwardingPlayer must use PLAYBACK_TYPE_LOCAL")
        assertFalse(fwdSrc.contains("PLAYBACK_TYPE_REMOTE"), "UsbVolumeForwardingPlayer must never reference PLAYBACK_TYPE_REMOTE")
        assertTrue(fwdSrc.contains("override fun addListener("), "UsbVolumeForwardingPlayer must override addListener")
        assertTrue(fwdSrc.contains("override fun removeListener("), "UsbVolumeForwardingPlayer must override removeListener")

        // Item 7 & 12: Normal FULL DELEGATE mode must gate DelegateSinkDiag on isEnabled (zero extra hasPendingData/getTimestamp calls)
        val diagSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/DelegateSinkDiag.kt")
        assertTrue(diagSrc.contains("val isEnabled: Boolean"), "DelegateSinkDiag must expose opt-in isEnabled")
        assertTrue(sinkSrc.contains("if (DelegateSinkDiag.isEnabled)"), "UsbAudioSink must gate DelegateSinkDiag on isEnabled")
        val handleBufferBlock = sinkSrc.substringAfter("override fun handleBuffer(").substringBefore("override fun getCurrentPositionUs(")
        assertFalse(handleBufferBlock.contains("formatUsbStatus("), "handleBuffer must not allocate status strings per buffer")

        // Item 8: No setStreamVolume side effects on release/fallback/prepare/play/pause/fade
        val mgrSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioManager.kt")
        val onReleasedBlock = mgrSrc.substringAfter("fun onUsbReleased(").substringBefore("fun restoreHardwareVolumeOnRelease(")
        assertFalse(onReleasedBlock.contains("setStreamVolume"), "onUsbReleased must never call setStreamVolume")
        assertFalse(sinkSrc.contains("setStreamVolume"), "UsbAudioSink must never call setStreamVolume")

        // Audio-path lock-freedom: UsbAudioStreamer.getStats must not be @Synchronized
        val streamerSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UsbAudioStreamer.kt")
        val getStatsHeader = streamerSrc.substringBefore("override fun getStats()").takeLast(40)
        assertFalse(getStatsHeader.contains("@Synchronized"), "UsbAudioStreamer.getStats must be lock-free")

        // UnifiedUsbAudioSink & UnifiedUsbDacManager must enforce thread safety, static format contracts, and zero-touch OFF
        val unifiedSinkSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UnifiedUsbAudioSink.kt")
        for (forbidden in listOf("activePlayer", ".playbackState", ".playWhenReady", ".currentPosition", ".seekTo(", ".prepare()", "CountDownLatch", "Thread.sleep")) {
            assertFalse(unifiedSinkSrc.contains(forbidden), "UnifiedUsbAudioSink.kt must not contain $forbidden")
        }
        val uSupports = unifiedSinkSrc.substringAfter("override fun supportsFormat(").substringBefore("override fun getFormatSupport(")
        val uGetSupport = unifiedSinkSrc.substringAfter("override fun getFormatSupport(").substringBefore("override fun configure(")
        for (transientField in listOf("isUsbModeActive", "releasedWhileIdle", "sinkReleased", "bypassedForSpeed")) {
            assertFalse(uSupports.contains(transientField), "UnifiedUsbAudioSink.supportsFormat must not depend on $transientField")
            assertFalse(uGetSupport.contains(transientField), "UnifiedUsbAudioSink.getFormatSupport must not depend on $transientField")
        }
        val unifiedMgrSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/usb/UnifiedUsbDacManager.kt")
        assertFalse(unifiedMgrSrc.contains("PlaybackCore.activePlayer"), "UnifiedUsbDacManager must never access activePlayer")
        assertFalse(stripLineComments(unifiedMgrSrc.substringAfter("fun scanClassOnly(").substringBefore("fun onModeChanged(")).contains("openDevice("), "UnifiedUsbDacManager.scanClassOnly must never openDevice")

        val coreSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/PlaybackCore.kt")
        assertTrue(coreSrc.contains("dev_use_unified_bit_perfect"), "PlaybackCore must persist dev_use_unified_bit_perfect for runtime path switching")

        // Rebuild & MediaSession continuity invariants:
        val serviceSrc = readSrc("player/src/androidMain/kotlin/io/max/paperplay/player/PlaybackService.kt")
        assertTrue(serviceSrc.contains("BitPerfectRebuildPolicy.shouldRebuildPlayer"), "PlaybackService must gate rebuilds via BitPerfectRebuildPolicy")
        assertTrue(serviceSrc.contains("PlaybackRebuildTransaction.execute"), "PlaybackService must use PlaybackRebuildTransaction for ordered swap-before-release")
        val onCreateBlock = serviceSrc.substringAfter("override fun onCreate()").substringBefore("private val rebuildHandler")
        assertFalse(onCreateBlock.contains("addAnalyticsListener(PlaybackLogging.analytics)"), "PlaybackService must not double-register PlaybackLogging.analytics already added by PlaybackCore.createPlayer")
        assertTrue(unifiedMgrSrc.contains("BitPerfectRebuildPolicy.shouldRebuildPlayer"), "UnifiedUsbDacManager must check BitPerfectRebuildPolicy before requesting player rebuild")
    }
}
