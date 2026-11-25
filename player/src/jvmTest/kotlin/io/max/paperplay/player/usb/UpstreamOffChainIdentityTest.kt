package io.max.paperplay.player.usb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstreamOffChainIdentityTest {
    private fun findRepoFile(relPath: String): File =
        listOf(File(relPath), File("../$relPath")).first { it.exists() }

    @Test
    fun `unified OFF path builds bare EngineAudioSink over DefaultAudioSink with float enabled and zero USB wrappers`() {
        val tapSrc = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/TapProcessor.kt").readText().replace("\r\n", "\n") // a Windows checkout has other line ends
        val helperBlock = tapSrc.substringAfter("internal fun buildUpstreamNormalAudioSink(").substringBefore("@OptIn(UnstableApi::class)\ninternal class TapRenderersFactory")
        assertTrue(helperBlock.contains(".setEnableFloatOutput(true)"), "Upstream normal chain must enable float output")
        assertTrue(helperBlock.contains(".setAudioProcessors(arrayOf(TapProcessor()))"), "Upstream normal chain must attach TapProcessor")
        assertTrue(helperBlock.contains("return EngineAudioSink(defaultSink)"), "Upstream normal chain must return bare EngineAudioSink")
        assertFalse(helperBlock.contains("UsbAudioSink"), "Upstream normal chain must not wrap in UsbAudioSink")
        assertFalse(helperBlock.contains("PlainDiagAudioSink"), "Upstream normal chain must not wrap in PlainDiagAudioSink")
        assertFalse(helperBlock.contains("DelegateSinkDiag"), "Upstream normal chain must not attach DelegateSinkDiag")

        val factoryBlock = tapSrc.substringAfter("internal class TapRenderersFactory").substringBefore("val sinkFloat = chainMode.sinkFloatOutput")
        assertTrue(factoryBlock.contains("setEnableAudioFloatOutput(!PlaybackCore.usbExclusiveMode)"))
        assertTrue(factoryBlock.contains("return buildUpstreamNormalAudioSink(context, enableAudioOutputPlaybackParams)"))
    }

    @Test
    fun `AndroidPlayerEngine never claims bitPerfect on built-in speaker or unverified mixer`() {
        val engineSrc = findRepoFile("player/src/androidMain/kotlin/io/max/paperplay/player/AndroidPlayerEngine.kt").readText()
        assertFalse(
            engineSrc.contains("effects.isEmpty() && matches && external == null"),
            "AndroidPlayerEngine must never claim speaker/mixer playback is bit-perfect"
        )
        assertTrue(
            engineSrc.contains("if (transport.verified && rate != null)"),
            "AndroidPlayerEngine must evaluate bitPerfect only from verified transport"
        )
    }
}
