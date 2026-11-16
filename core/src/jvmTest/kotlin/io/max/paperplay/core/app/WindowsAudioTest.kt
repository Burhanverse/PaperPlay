package io.max.paperplay.core.app

import kotlin.test.Test
import kotlin.test.assertTrue

class WindowsAudioTest {
    @Test fun readsTheDefaultOutputOnWindows() {
        val out = windowsOutput()
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            println("default output: ${out?.name} headphones=${out?.headphones}")
            assertTrue(out == null || out.name.isNotBlank())
        }
    }
}
