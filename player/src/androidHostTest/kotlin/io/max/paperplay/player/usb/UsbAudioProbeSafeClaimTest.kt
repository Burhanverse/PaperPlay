package io.max.paperplay.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbAudioProbeSafeClaimTest {

    @Test
    fun failedStreamClaimStillReleasesControlInterfaceAndRestoresKernelDriver() {
        val events = mutableListOf<String>()
        var onClaimedCalled = false
        val sb = StringBuilder()

        UsbAudioProbe.runSafeClaimLifecycle(
            controlIntfId = 0,
            streamIntfId = 1,
            claimControl = {
                events += "claim:0"
                true
            },
            claimStream = {
                events += "claim:1"
                false
            },
            releaseControl = {
                events += "release:0"
                true
            },
            releaseStream = {
                events += "release:1"
                true
            },
            reattachKernelDriver = { intfId ->
                events += "reattach:$intfId"
                0
            },
            onClaimed = { onClaimedCalled = true },
            onPostRelease = { events += "postRelease" },
            sb = sb,
        )

        assertFalse(onClaimedCalled, "probe steps must not run when stream claim fails")
        assertEquals(
            listOf("claim:0", "claim:1", "release:0", "reattach:0"),
            events,
            "control interface must be released and reattached before returning on failed stream claim",
        )
    }

    @Test
    fun successfulClaimReleasesAndReattachesBothInterfacesInOrder() {
        val events = mutableListOf<String>()
        var onClaimedCalled = false
        val sb = StringBuilder()

        UsbAudioProbe.runSafeClaimLifecycle(
            controlIntfId = 0,
            streamIntfId = 1,
            claimControl = {
                events += "claim:0"
                true
            },
            claimStream = {
                events += "claim:1"
                true
            },
            releaseControl = {
                events += "release:0"
                true
            },
            releaseStream = {
                events += "release:1"
                true
            },
            reattachKernelDriver = { intfId ->
                events += "reattach:$intfId"
                0
            },
            onClaimed = {
                onClaimedCalled = true
                events += "onClaimed"
            },
            onPostRelease = { events += "postRelease" },
            sb = sb,
        )

        assertTrue(onClaimedCalled)
        assertEquals(
            listOf("claim:0", "claim:1", "onClaimed", "release:1", "release:0", "reattach:0", "reattach:1", "postRelease"),
            events,
        )
    }

    @Test
    fun streamClaimExceptionStillReleasesControlInterfaceAndRestoresKernelDriver() {
        val events = mutableListOf<String>()
        val sb = StringBuilder()

        val thrown = runCatching {
            UsbAudioProbe.runSafeClaimLifecycle(
                controlIntfId = 0,
                streamIntfId = 1,
                claimControl = {
                    events += "claim:0"
                    true
                },
                claimStream = {
                    events += "claim:1"
                    error("USB host rejected claim")
                },
                releaseControl = {
                    events += "release:0"
                    true
                },
                releaseStream = {
                    events += "release:1"
                    true
                },
                reattachKernelDriver = { intfId ->
                    events += "reattach:$intfId"
                    0
                },
                onClaimed = { events += "onClaimed" },
                onPostRelease = { events += "postRelease" },
                sb = sb,
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(
            listOf("claim:0", "claim:1", "release:0", "reattach:0"),
            events,
        )
    }
}
