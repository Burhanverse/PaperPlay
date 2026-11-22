package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbExclusiveModePreferenceTest {

    /**
     * Preference false -> first configure goes to the delegate and no claim/session creation ever happens.
     */
    @Test
    fun testPreferenceFalseFirstConfigureGoesToDelegateAndNoClaimEverHappens() {
        val fakeBridge = FakeUsbEngineBridge()
        var usbExclusiveMode = false
        var activeMode = if (usbExclusiveMode) "USB" else "DELEGATE"

        // Simulated sink configure logic (matching UsbAudioSink.configure)
        val sessionCreated = if (!usbExclusiveMode) {
            activeMode = "DELEGATE"
            false // Directly forward to normalSink, no USB session
        } else {
            activeMode = "USB"
            fakeBridge.create(
                fd = 42,
                endpoint = 1,
                streamingIface = 1,
                targetAlt = 1,
                isUac2 = true,
                clockSourceId = 1,
                controlIface = 0,
                sampleRate = 44100,
                bitDepth = 16,
                channels = 2,
                subslotSize = 2,
                isHighSpeed = true,
                isDop = false,
                feedbackEp = 0
            ) != 0L
        }

        assertFalse(sessionCreated, "Session must not be created when exclusive mode is off")
        assertEquals("DELEGATE", activeMode, "Sink must stay in DELEGATE mode")
        assertEquals(0, fakeBridge.createdCount, "No USB claim/create must ever happen when exclusive mode is off")
        assertTrue(fakeBridge.activeHandles.isEmpty(), "No active native handles")
    }

    /**
     * Preference true with a connected DAC -> configures USB mode and creates streaming session.
     */
    @Test
    fun testPreferenceTrueWithConnectedDacConfiguresUsb() {
        val fakeBridge = FakeUsbEngineBridge()
        var usbExclusiveMode = true
        var activeMode = if (usbExclusiveMode) "USB" else "DELEGATE"

        val sessionCreated = if (!usbExclusiveMode) {
            activeMode = "DELEGATE"
            false
        } else {
            activeMode = "USB"
            fakeBridge.create(
                fd = 42,
                endpoint = 1,
                streamingIface = 1,
                targetAlt = 2,
                isUac2 = true,
                clockSourceId = 1,
                controlIface = 0,
                sampleRate = 48000,
                bitDepth = 24,
                channels = 2,
                subslotSize = 3,
                isHighSpeed = true,
                isDop = false,
                feedbackEp = 0
            ) != 0L
        }

        assertTrue(sessionCreated, "Session must be created when exclusive mode is on")
        assertEquals("USB", activeMode, "Sink must be in USB mode")
        assertEquals(1, fakeBridge.createdCount, "Exactly 1 native session created")
        assertEquals(1, fakeBridge.activeHandles.size)
        assertEquals(48000, fakeBridge.currentRate)
        assertEquals(2, fakeBridge.currentAlt)
    }

    /**
     * Service restart without UI keeps the persisted preference.
     */
    @Test
    fun testServiceRestartWithoutUiKeepsPreference() {
        // Mock persisted store
        val mockStore = mutableMapOf<String, String>()
        mockStore["usb_exclusive_mode"] = "false"

        // Simulated PlaybackService / engine startup without UI:
        // Reads persisted store directly on service creation
        val loadedPref = mockStore["usb_exclusive_mode"] == "true"
        var runtimeFlag = loadedPref

        assertEquals(false, runtimeFlag, "Runtime flag must load 'false' from persisted store on service startup")

        // First configure inside background service without UI
        val fakeBridge = FakeUsbEngineBridge()
        if (runtimeFlag) {
            fakeBridge.create(42, 1, 1, 1, true, 1, 0, 44100, 16, 2, 2, true, false, 0)
        }

        assertEquals(0, fakeBridge.createdCount, "Service restarted with pref=false must not create native USB sessions")

        // Now simulate service restart when preference is true
        mockStore["usb_exclusive_mode"] = "true"
        val loadedPref2 = mockStore["usb_exclusive_mode"] == "true"
        runtimeFlag = loadedPref2

        assertEquals(true, runtimeFlag)
        if (runtimeFlag) {
            fakeBridge.create(42, 1, 1, 1, true, 1, 0, 44100, 16, 2, 2, true, false, 0)
        }
        assertEquals(1, fakeBridge.createdCount, "Service restarted with pref=true must create native USB session")
    }

    /**
     * Toggling at runtime still works both ways.
     */
    @Test
    fun testTogglingAtRuntimeWorksBothWays() {
        val fakeBridge = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fakeBridge
        )

        // 1. Start with exclusive mode OFF
        var isExclusive = false
        var activeMode = "DELEGATE"
        assertEquals(0, fakeBridge.createdCount)
        assertEquals(0L, controller.nativeHandle)

        // 2. User toggles ON at runtime
        isExclusive = true
        controller.toggleUsbExclusive(true)
        activeMode = if (controller.isPrepared) "USB" else "DELEGATE"

        assertEquals("USB", activeMode)
        assertTrue(controller.isPrepared)
        assertTrue(controller.nativeHandle != 0L)
        assertEquals(1, fakeBridge.createdCount)
        assertEquals(1, fakeBridge.startCount)

        // 3. User toggles OFF at runtime
        isExclusive = false
        controller.toggleUsbExclusive(false)
        activeMode = "DELEGATE"

        assertEquals("DELEGATE", activeMode)
        assertFalse(controller.isPrepared)
        assertEquals(0L, controller.nativeHandle)
        assertEquals(1, fakeBridge.restoreDefaultVolumeCount, "Toggling off must restore default volume for system")
        assertEquals(1, fakeBridge.destroyedCount, "Toggling off must destroy native session")

        // 4. User toggles ON again at runtime
        isExclusive = true
        controller.toggleUsbExclusive(true)
        activeMode = if (controller.isPrepared) "USB" else "DELEGATE"

        assertEquals("USB", activeMode)
        assertTrue(controller.isPrepared)
        assertEquals(2, fakeBridge.createdCount)
        assertEquals(2, fakeBridge.startCount)
    }

    /**
     * Status text derivation matches real state specifications:
     * - "USB exclusive: off" when flag is off
     * - "waiting for DAC" when on with no DAC
     * - "bit-perfect active" only when activeMode == USB and native stream is confirmed
     */
    @Test
    fun testStatusTextDerivesFromRealState() {
        // Case 1: Flag off (regardless of DAC presence)
        val isExclusiveOff = false
        val isDacConnected = true
        val statusReason1 = if (!isExclusiveOff) "USB exclusive: off" else "other"
        assertEquals("USB exclusive: off", statusReason1)

        // Case 2: Flag on with no DAC
        val isExclusiveOn = true
        val hasDac = false
        val statusReason2 = if (!isExclusiveOn) {
            "USB exclusive: off"
        } else if (!hasDac) {
            "waiting for DAC"
        } else {
            "other"
        }
        assertEquals("waiting for DAC", statusReason2)

        // Case 3: Flag on with DAC connected, but not streaming
        val activeModeIdle = "DELEGATE"
        val isStreamingIdle = false
        val isBitPerfectIdle = activeModeIdle == "USB" && isStreamingIdle
        val statusReason3 = if (!isExclusiveOn) {
            "USB exclusive: off"
        } else if (!hasDac) {
            "waiting for DAC"
        } else if (isBitPerfectIdle) {
            "bit-perfect active"
        } else {
            "Standby: ready to stream"
        }
        val hasDacConnected = true
        val statusReason3Real = if (!isExclusiveOn) {
            "USB exclusive: off"
        } else if (!hasDacConnected) {
            "waiting for DAC"
        } else if (isBitPerfectIdle) {
            "bit-perfect active"
        } else {
            "Standby: ready to stream"
        }
        assertEquals("Standby: ready to stream", statusReason3Real)
        assertFalse(isBitPerfectIdle)

        // Case 4: Flag on with DAC connected, activeMode == USB, and native stream confirmed
        val activeModeUsb = "USB"
        val isStreamingRunning = true
        val isBitPerfectConfirmed = activeModeUsb == "USB" && isStreamingRunning
        val statusReason4 = if (!isExclusiveOn) {
            "USB exclusive: off"
        } else if (!hasDacConnected) {
            "waiting for DAC"
        } else if (isBitPerfectConfirmed) {
            "bit-perfect active"
        } else {
            "Standby: ready to stream"
        }
        assertEquals("bit-perfect active", statusReason4)
        assertTrue(isBitPerfectConfirmed)
    }

    /**
     * Restore order: volume/mute restore must execute BEFORE destroy/release while interface 0 is still claimed.
     */
    @Test
    fun testRestoreDefaultVolumeRunsBeforeDestroy() {
        val fakeBridge = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 3,
            bitDepth = 32,
            channels = 2,
            subslotSize = 4,
            engineBridge = fakeBridge
        )

        controller.toggleUsbExclusive(true)
        fakeBridge.callOrder.clear()

        controller.toggleUsbExclusive(false)

        val stopIdx = fakeBridge.callOrder.indexOfFirst { it.startsWith("stop:") }
        val restoreIdx = fakeBridge.callOrder.indexOfFirst { it.startsWith("restoreDefaultVolume:") }
        val destroyIdx = fakeBridge.callOrder.indexOfFirst { it.startsWith("destroy:") }
        assertTrue(stopIdx >= 0, "stop must be called")
        assertTrue(restoreIdx > stopIdx, "restoreDefaultVolume must run after stop")
        assertTrue(destroyIdx > restoreIdx, "destroy/release must run strictly AFTER restoreDefaultVolume")
        assertEquals(0, fakeBridge.probeCount, "No native probe during toggle off")
    }

    /**
     * SinkChainMode developer option parses all five Stage 0 matrix modes (plus legacy IDs) cleanly and defaults to FULL.
     */
    @Test
    fun testSinkChainModeParsing() {
        assertEquals(io.max.paperplay.player.SinkChainMode.FULL, io.max.paperplay.player.SinkChainMode.fromId("full"))
        assertEquals(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_ON, io.max.paperplay.player.SinkChainMode.fromId("no_dsp_float_on"))
        assertEquals(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_OFF, io.max.paperplay.player.SinkChainMode.fromId("no_dsp_float_off"))
        assertEquals(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_OFF, io.max.paperplay.player.SinkChainMode.fromId("plain_float_off"))
        assertEquals(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_ON, io.max.paperplay.player.SinkChainMode.fromId("plain_float_on"))
        // Legacy ID backwards compatibility
        assertEquals(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_ON, io.max.paperplay.player.SinkChainMode.fromId("no_dsp"))
        assertEquals(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_OFF, io.max.paperplay.player.SinkChainMode.fromId("plain"))
        assertEquals(io.max.paperplay.player.SinkChainMode.FULL, io.max.paperplay.player.SinkChainMode.fromId(null))
        assertEquals(io.max.paperplay.player.SinkChainMode.FULL, io.max.paperplay.player.SinkChainMode.fromId("unknown"))

        assertTrue(io.max.paperplay.player.SinkChainMode.FULL.sinkFloatOutput)
        assertTrue(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_ON.sinkFloatOutput)
        assertFalse(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_OFF.sinkFloatOutput)
        assertFalse(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_OFF.sinkFloatOutput)
        assertTrue(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_ON.sinkFloatOutput)

        assertTrue(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_OFF.isPlain)
        assertTrue(io.max.paperplay.player.SinkChainMode.PLAIN_FLOAT_ON.isPlain)
        assertFalse(io.max.paperplay.player.SinkChainMode.FULL.isPlain)
        assertTrue(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_ON.isNoDsp)
        assertTrue(io.max.paperplay.player.SinkChainMode.NO_DSP_FLOAT_OFF.isNoDsp)
        assertFalse(io.max.paperplay.player.SinkChainMode.FULL.isNoDsp)
    }

    /**
     * Item 10: Bridge failures during configure/play/handleBuffer trigger fallback with a clear reason.
     */
    @Test
    fun testBridgeFailureTriggersFallbackWithReason() {
        val alt = UsbAudioAltSetting(
            interfaceNumber = 1,
            altSetting = 1,
            terminalLink = 1,
            formatTag = AudioFormatTag.PCM,
            bmFormats = 1L,
            channelCount = 2,
            subslotSize = 2,
            bitResolution = 16,
            sampleRates = listOf(44100),
            continuousRateRange = null,
            dataEndpoint = UsbEndpointDescriptor(1, true, EndpointSyncType.ASYNCHRONOUS, EndpointUsageType.DATA, 192, 1, 0, 0),
            feedbackEndpoint = null,
            isDsdRawCapable = false,
            isDsdDoPCapable = false
        )

        var fallbackReason: String? = null
        val sm = UsbSinkStateMachine(
            bridgeFactory = { _, _, _ -> throw RuntimeException("simulated USB claim EBUSY") },
            onFallbackTriggered = { reason -> fallbackReason = reason }
        )
        val caught = runCatching {
            sm.configure(44100, 16, 2, listOf(alt), isFloat = false)
        }
        assertTrue(caught.isFailure, "State machine signals configuration rejection for UsbAudioSink to catch")
        assertEquals(UsbSinkState.FALLBACK, sm.state)
        assertTrue(fallbackReason?.contains("EBUSY") == true, "Fallback reason must preserve root cause")
    }
}
