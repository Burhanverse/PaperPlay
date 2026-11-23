package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Api34BitPerfectMixerTest {
    private val option24 = Api34MixerOption(
        encoding = Api34BitPerfectMixerPolicy.ENCODING_PCM_24BIT_PACKED,
        sampleRateHz = 96_000,
        channels = 2,
        isBitPerfectBehavior = true,
    )

    private val option32 = Api34MixerOption(
        encoding = Api34BitPerfectMixerPolicy.ENCODING_PCM_32BIT,
        sampleRateHz = 96_000,
        channels = 2,
        isBitPerfectBehavior = true,
    )

    private fun baseInput(opt: Api34MixerOption = option24) = Api34MixerProbeInput(
        devFlagEnabled = true,
        sdkInt = 34,
        hasUsbAudioOutputDevice = true,
        supportedOptions = listOf(opt),
        requestedRateHz = 96_000,
        requestedChannels = 2,
        setPreferredSucceeded = true,
        readbackOption = opt,
        floatOutputDisabled = true,
        dspAndEffectsBypassed = true,
        audioTrackEncoding = opt.encoding,
        audioTrackRateHz = 96_000,
        audioTrackChannels = 2,
    )

    @Test
    fun allFiveGatesPassFor24BitPackedAnd32BitInteger() {
        val v24 = assertIs<Api34MixerVerification.Verified>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput(option24)),
        )
        assertEquals(24, v24.bitDepth)
        assertEquals(96_000, v24.sampleRateHz)

        val v32 = assertIs<Api34MixerVerification.Verified>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput(option32)),
        )
        assertEquals(32, v32.bitDepth)
        assertEquals(96_000, v32.sampleRateHz)
    }

    @Test
    fun gate1FailsWhenDevFlagOffSdkBelow34OrNoUsbDevice() {
        val off = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(devFlagEnabled = false)),
        )
        assertEquals(1, off.failedGate)

        val oldSdk = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(sdkInt = 33)),
        )
        assertEquals(1, oldSdk.failedGate)

        val noUsb = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(hasUsbAudioOutputDevice = false)),
        )
        assertEquals(1, noUsb.failedGate)
    }

    @Test
    fun gate2FailsWhenNonBitPerfectFloatOrRateMismatch() {
        val notBp = option24.copy(isBitPerfectBehavior = false)
        assertEquals(
            2,
            assertIs<Api34MixerVerification.Rejected>(
                Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                    baseInput(notBp).copy(supportedOptions = listOf(notBp)),
                ),
            ).failedGate,
        )

        val floatOpt = option24.copy(encoding = Api34BitPerfectMixerPolicy.ENCODING_PCM_FLOAT)
        assertEquals(
            2,
            assertIs<Api34MixerVerification.Rejected>(
                Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                    baseInput(floatOpt).copy(supportedOptions = listOf(floatOpt)),
                ),
            ).failedGate,
        )

        val wrongRate = option24.copy(sampleRateHz = 48_000)
        assertEquals(
            2,
            assertIs<Api34MixerVerification.Rejected>(
                Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                    baseInput().copy(supportedOptions = listOf(wrongRate)),
                ),
            ).failedGate,
        )
    }

    @Test
    fun gate3FailsOnSetFailureOrReadbackMismatch() {
        val setFailed = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(setPreferredSucceeded = false)),
        )
        assertEquals(3, setFailed.failedGate)

        val nullReadback = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(readbackOption = null)),
        )
        assertEquals(3, nullReadback.failedGate)

        val downgradedReadback = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput().copy(readbackOption = option24.copy(isBitPerfectBehavior = false)),
            ),
        )
        assertEquals(3, downgradedReadback.failedGate)
    }

    @Test
    fun gate4FailsWhenFloatOutputEnabledOrDspActive() {
        val floatOn = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(floatOutputDisabled = false)),
        )
        assertEquals(4, floatOn.failedGate)

        val dspOn = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(baseInput().copy(dspAndEffectsBypassed = false)),
        )
        assertEquals(4, dspOn.failedGate)

        val directEqAllowed = assertIs<Api34MixerVerification.Verified>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput().copy(dspAndEffectsBypassed = false, allowEqInDirectMixer = true),
            ),
        )
        assertEquals(true, directEqAllowed.eqActive)
        assertEquals(24, directEqAllowed.bitDepth)
        assertEquals(96_000, directEqAllowed.sampleRateHz)

        // Even when EQ is allowed, float output must remain rejected
        val floatStillRejected = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput().copy(floatOutputDisabled = false, dspAndEffectsBypassed = false, allowEqInDirectMixer = true),
            ),
        )
        assertEquals(4, floatStillRejected.failedGate)
    }

    @Test
    fun gate5FailsWhenAudioTrackFormatDiffersFromVerifiedMixer() {
        val wrongEncoding = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput(option24).copy(audioTrackEncoding = Api34BitPerfectMixerPolicy.ENCODING_PCM_16BIT),
            ),
        )
        assertEquals(5, wrongEncoding.failedGate)

        val wrongRate = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput(option24).copy(audioTrackRateHz = 48_000),
            ),
        )
        assertEquals(5, wrongRate.failedGate)

        val wrongChannels = assertIs<Api34MixerVerification.Rejected>(
            Api34BitPerfectMixerPolicy.verifyAllFiveGates(
                baseInput(option24).copy(audioTrackChannels = 1),
            ),
        )
        assertEquals(5, wrongChannels.failedGate)
    }
}
