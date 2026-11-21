package io.max.paperplay.player.usb

import io.max.paperplay.player.MIME_AUDIO_DOP
import kotlin.test.Test
import kotlin.test.assertEquals

class UsbAudioSinkContractTest {
    @Test
    fun `static format support accepts 16, 24, 32 int PCM, float PCM, and DoP for any positive rate`() {
        for (rate in listOf(8000, 44100, 48000, 96000, 176400, 384000, 768000, -1)) {
            assertEquals(2, UsbCoarseSinkController.getFormatSupport("audio/raw", 16, true, 2, rate))
            assertEquals(2, UsbCoarseSinkController.getFormatSupport("audio/raw", 24, true, 2, rate))
            assertEquals(2, UsbCoarseSinkController.getFormatSupport("audio/raw", 32, true, 2, rate))
            assertEquals(2, UsbCoarseSinkController.getFormatSupport(null, 32, true, 2, rate))
            assertEquals(2, UsbCoarseSinkController.getFormatSupport(MIME_AUDIO_DOP, 24, true, 2, rate))
        }
    }

    @Test
    fun `static format support rejects encoded mime types and invalid rates or channels`() {
        assertEquals(0, UsbCoarseSinkController.getFormatSupport("audio/flac", 16, false, 2, 44100))
        assertEquals(0, UsbCoarseSinkController.getFormatSupport("audio/mpeg", 16, false, 2, 44100))
        assertEquals(0, UsbCoarseSinkController.getFormatSupport("audio/raw", 16, true, 2, 0))
        assertEquals(0, UsbCoarseSinkController.getFormatSupport("audio/raw", 16, true, 0, 44100))
    }
}
