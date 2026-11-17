package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbAudioStreamerTest {

    @Test
    fun testSwitchingSequenceABACycle() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 96000,
            targetAlt = 3,
            bitDepth = 24,
            channels = 2,
            subslotSize = 3,
            engineBridge = fake
        )

        // Initial setup
        val initOk = controller.reconfigure(
            newRate = 96000,
            newAlt = 3,
            newBitDepth = 24,
            newChannels = 2,
            newSubslot = 3
        )
        assertTrue(initOk)
        assertEquals(96000, controller.sampleRate)
        assertEquals(3, controller.targetAlt)
        assertEquals(96000, fake.currentRate)
        assertEquals(3, fake.currentAlt)

        // Step 1: 96k alt 3 -> 44.1k alt 3 (the exact scenario where stale val previously broke)
        val ok1 = controller.reconfigure(
            newRate = 44100,
            newAlt = 3,
            newBitDepth = 16,
            newChannels = 2,
            newSubslot = 2
        )
        assertTrue(ok1, "Reconfiguration to 44.1 kHz alt 3 must succeed")
        assertEquals(44100, controller.sampleRate)
        assertEquals(3, controller.targetAlt)
        assertEquals(44100, controller.confirmedRate)
        assertEquals(3, controller.confirmedAlt)
        assertEquals(44100, fake.currentRate)
        assertEquals(3, fake.currentAlt)

        // Step 2: 44.1k alt 3 -> 48k alt 2
        val ok2 = controller.reconfigure(
            newRate = 48000,
            newAlt = 2,
            newBitDepth = 24,
            newChannels = 2,
            newSubslot = 3
        )
        assertTrue(ok2)
        assertEquals(48000, controller.sampleRate)
        assertEquals(2, controller.targetAlt)
        assertEquals(48000, controller.confirmedRate)
        assertEquals(2, controller.confirmedAlt)
        assertEquals(48000, fake.currentRate)
        assertEquals(2, fake.currentAlt)

        // Step 3: 48k alt 2 -> 44.1k alt 1
        val ok3 = controller.reconfigure(
            newRate = 44100,
            newAlt = 1,
            newBitDepth = 16,
            newChannels = 2,
            newSubslot = 2
        )
        assertTrue(ok3)
        assertEquals(44100, controller.sampleRate)
        assertEquals(1, controller.targetAlt)
        assertEquals(44100, controller.confirmedRate)
        assertEquals(1, controller.confirmedAlt)
        assertEquals(44100, fake.currentRate)
        assertEquals(1, fake.currentAlt)

        // Step 4: 44.1k alt 1 -> 96k alt 3 (Return to initial format)
        val ok4 = controller.reconfigure(
            newRate = 96000,
            newAlt = 3,
            newBitDepth = 24,
            newChannels = 2,
            newSubslot = 3
        )
        assertTrue(ok4)
        assertEquals(96000, controller.sampleRate)
        assertEquals(3, controller.targetAlt)
        assertEquals(96000, controller.confirmedRate)
        assertEquals(3, controller.confirmedAlt)
        assertEquals(96000, fake.currentRate)
        assertEquals(3, fake.currentAlt)
    }

    @Test
    fun testRapidSwitchStress() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fake
        )

        val formats = listOf(
            Triple(44100, 1, 16),
            Triple(48000, 2, 24),
            Triple(88200, 3, 24),
            Triple(96000, 3, 24),
            Triple(192000, 3, 32),
            Triple(44100, 3, 24)
        )

        for (i in 0 until 100) {
            val (rate, alt, depth) = formats[i % formats.size]
            val ok = controller.reconfigure(
                newRate = rate,
                newAlt = alt,
                newBitDepth = depth,
                newChannels = 2,
                newSubslot = if (depth == 16) 2 else 3
            )
            assertTrue(ok, "Rapid switch iteration $i failed for rate $rate alt $alt")
            assertEquals(rate, controller.sampleRate)
            assertEquals(alt, controller.targetAlt)
            assertEquals(rate, controller.confirmedRate)
            assertEquals(alt, controller.confirmedAlt)
            assertEquals(rate, fake.currentRate)
            assertEquals(alt, fake.currentAlt)
        }
    }

    @Test
    fun testRateMismatchTriggersSessionRecreation() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 96000,
            targetAlt = 3,
            bitDepth = 24,
            channels = 2,
            subslotSize = 3,
            engineBridge = fake
        )
        controller.reconfigure(96000, 3, 24, 2, 3)

        // Simulate DAC hardware failing to switch clock: requested 44.1k, readback says 96k
        fake.simulatedReadbackRate = 96000

        val ok = controller.reconfigure(
            newRate = 44100,
            newAlt = 3,
            newBitDepth = 16,
            newChannels = 2,
            newSubslot = 2
        )

        assertFalse(ok, "Reconfigure must fail on rate mismatch to trigger session recreation")
        assertEquals(false, controller.isPrepared)
    }

    @Test
    fun testAltMismatchTriggersSessionRecreation() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fake
        )
        controller.reconfigure(44100, 1, 16, 2, 2)

        // Simulate DAC interface setting staying at alt 1 instead of switching to alt 2
        fake.simulatedReadbackAlt = 1

        val ok = controller.reconfigure(
            newRate = 48000,
            newAlt = 2,
            newBitDepth = 24,
            newChannels = 2,
            newSubslot = 3
        )

        assertFalse(ok, "Reconfigure must fail on alt mismatch to trigger session recreation")
        assertEquals(false, controller.isPrepared)
    }

    @Test
    fun testRestoreDefaultVolumeOnRelease() {
        val fake = FakeUsbEngineBridge()
        val handle = fake.create(
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
        )

        assertEquals(0, fake.restoreDefaultVolumeCount)
        fake.restoreDefaultVolume(handle)
        assertEquals(1, fake.restoreDefaultVolumeCount)
        fake.destroy(handle)
        assertEquals(1, fake.destroyedCount)
    }

    @Test
    fun testRateSwitchingABAPreservesHardwareVolume() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fake
        )
        controller.currentVolumeIndex = 15
        controller.maxVolumeIndex = 25
        controller.dacMinDb = -64.0f
        controller.dacMaxDb = 0.0f
        controller.dacResDb = 1.0f

        val minRaw = (-64 * 256).toShort()
        val maxRaw = 0.toShort()
        val resRaw = 256.toShort()
        val expectedDb = UsbVolumeCurve.computeVolumeDb(15, 25, -64.0f, 0.0f, 1.0f)
        val expectedRaw = UsbVolumeCurve.dbToRaw(expectedDb, minRaw, maxRaw, resRaw).toInt()

        // Initial setup format A (44.1k alt 1 16b)
        val initOk = controller.reconfigure(44100, 1, 16, 2, 2)
        assertTrue(initOk)
        assertEquals(expectedRaw, fake.lastHardwareVolumeRaw, "Volume after initial setup must equal curve for index 15")

        // Switch A -> B (96k alt 3 24b)
        val switchBOk = controller.reconfigure(96000, 3, 24, 2, 3)
        assertTrue(switchBOk)
        assertEquals(expectedRaw, fake.lastHardwareVolumeRaw, "Volume after switch A->B must equal curve for index 15 (never left at 0 dB default)")

        // Switch B -> A (44.1k alt 1 16b)
        val switchAOk = controller.reconfigure(44100, 1, 16, 2, 2)
        assertTrue(switchAOk)
        assertEquals(expectedRaw, fake.lastHardwareVolumeRaw, "Volume after switch B->A must equal curve for index 15")

        // User changes volume to index 8
        controller.currentVolumeIndex = 8
        val expectedDbNew = UsbVolumeCurve.computeVolumeDb(8, 25, -64.0f, 0.0f, 1.0f)
        val expectedRawNew = UsbVolumeCurve.dbToRaw(expectedDbNew, minRaw, maxRaw, resRaw).toInt()
        controller.applyHardwareVolume()
        assertEquals(expectedRawNew, fake.lastHardwareVolumeRaw)

        // Switch format again: 44.1k -> 96k
        val switchB2Ok = controller.reconfigure(96000, 3, 24, 2, 3)
        assertTrue(switchB2Ok)
        assertEquals(expectedRawNew, fake.lastHardwareVolumeRaw, "Volume after format switch must retain updated index 8")
    }

    @Test
    fun testUsbExclusiveToggleRestoresAndReappliesHardwareVolume() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 48000,
            targetAlt = 2,
            bitDepth = 24,
            channels = 2,
            subslotSize = 3,
            engineBridge = fake
        )
        controller.currentVolumeIndex = 18
        controller.maxVolumeIndex = 25
        controller.dacMinDb = -64.0f
        controller.dacMaxDb = 0.0f
        controller.dacResDb = 1.0f

        val minRaw = (-64 * 256).toShort()
        val maxRaw = 0.toShort()
        val resRaw = 256.toShort()
        val expectedDb = UsbVolumeCurve.computeVolumeDb(18, 25, -64.0f, 0.0f, 1.0f)
        val expectedRaw = UsbVolumeCurve.dbToRaw(expectedDb, minRaw, maxRaw, resRaw).toInt()

        // Start in USB mode
        val initOk = controller.reconfigure(48000, 2, 24, 2, 3)
        assertTrue(initOk)
        assertEquals(expectedRaw, fake.lastHardwareVolumeRaw)

        // Toggle USB exclusive OFF: hands DAC back to system
        controller.toggleUsbExclusive(false)
        assertEquals(1, fake.restoreDefaultVolumeCount, "Toggling USB exclusive off must call restoreDefaultVolume")
        assertEquals(0L, controller.nativeHandle)
        assertFalse(controller.isPrepared)
        assertEquals(0, fake.lastHardwareVolumeRaw, "Restoring default volume sets DAC hardware to 0 dB for system")

        // Toggle USB exclusive ON: re-creates session and re-applies current index
        controller.toggleUsbExclusive(true)
        assertTrue(controller.isPrepared)
        assertEquals(expectedRaw, fake.lastHardwareVolumeRaw, "Toggling USB exclusive back on must force re-apply curve volume for index 18")
    }

    @Test
    fun testStreamStartForcesHardwareVolumeBeforeStreaming() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fake
        )
        controller.currentVolumeIndex = 5
        controller.maxVolumeIndex = 25
        val minRaw = (-64 * 256).toShort()
        val maxRaw = 0.toShort()
        val resRaw = 256.toShort()
        val expectedDb = UsbVolumeCurve.computeVolumeDb(5, 25, -64.0f, 0.0f, 1.0f)
        val expectedRaw = UsbVolumeCurve.dbToRaw(expectedDb, minRaw, maxRaw, resRaw).toInt()

        controller.reconfigure(44100, 1, 16, 2, 2)
        // Reset fake record to verify start() specifically forces volume write
        fake.hardwareVolumeWrites.clear()

        val started = controller.start()
        assertTrue(started)
        assertEquals(1, fake.startCount)
        assertTrue(fake.hardwareVolumeWrites.contains(expectedRaw), "start() must force apply volume to ensure URBs are never submitted before volume is set")
    }

    @Test
    fun testRateAndAltChangesReconfigureInPlaceWithoutReopeningDevice() {
        val fake = FakeUsbEngineBridge()
        val controller = UsbStreamSwitchController(
            sampleRate = 44100,
            targetAlt = 1,
            bitDepth = 16,
            channels = 2,
            subslotSize = 2,
            engineBridge = fake
        )

        // Initial session open
        assertTrue(controller.reconfigure(44100, 1, 16, 2, 2))
        assertEquals(1, fake.createdCount)
        assertEquals(0, fake.destroyedCount)
        val initialHandle = controller.nativeHandle

        // Switch 44.1k alt 1 -> 96k alt 3 -> 48k alt 2 -> 44.1k alt 3 in place
        assertTrue(controller.reconfigure(96000, 3, 24, 2, 4))
        assertTrue(controller.reconfigure(48000, 2, 24, 2, 3))
        assertTrue(controller.reconfigure(44100, 3, 32, 2, 4))

        assertEquals(initialHandle, controller.nativeHandle, "Handle must stay open across in-place reconfigures")
        assertEquals(1, fake.createdCount, "Device must not be reopened when in-place reconfigure succeeds")
        assertEquals(0, fake.destroyedCount, "Session must not be destroyed during in-place reconfigure")
        assertEquals(3, fake.reconfigureCount, "All 3 format transitions must use in-place reconfigure")
        assertEquals(44100, controller.confirmedRate)
        assertEquals(3, controller.confirmedAlt)

        // When in-place reconfigure fails, falls back cleanly to recreate
        fake.failInPlaceReconfigure = true
        assertTrue(controller.reconfigure(192000, 3, 32, 2, 4))
        assertEquals(2, fake.createdCount, "Failed in-place reconfigure must fall back to full recreate")
        assertEquals(1, fake.destroyedCount)
        assertEquals(192000, controller.confirmedRate)
    }
}
