package io.max.paperplay.player.usb

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsbRingLogTest {

    @BeforeTest
    fun setup() {
        UsbRingLog.clear()
    }

    @Test
    fun testRecordAndDump() {
        UsbRingLog.record("TestTag", "Initial setup", UsbRingLog.CODE_GENERIC)
        UsbRingLog.recordAudio(UsbRingLog.CODE_CLAIM_IFACE, 1, 1)
        UsbRingLog.recordAudio(UsbRingLog.CODE_CLAIM_RES, 1, 1)
        UsbRingLog.recordAudio(UsbRingLog.CODE_SET_CUR_RATE, 96000, 0)
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_CONFIGURE, 96000, 2)
        UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_HANDLE_BUFFER, 4096, 1000000)

        val dump = UsbRingLog.dump()
        assertEquals(6, dump.size)
        assertTrue(dump[0].contains("Initial setup"))
        assertTrue(dump[1].contains("CLAIM_IFACE iface=1 attempt=1"))
        assertTrue(dump[2].contains("CLAIM_RES iface=1 success=true"))
        assertTrue(dump[3].contains("SET_CUR_RATE rate=96000 result=0"))
        assertTrue(dump[4].contains("SINK_CONFIGURE rate=96000 ch/enc=2"))
        assertTrue(dump[5].contains("SINK_HANDLE_BUF bytes=4096 ptsUs=1000000"))
    }

    @Test
    fun testBufferWrappingMaintainsCapacity() {
        // Record more than CAPACITY items
        val total = UsbRingLog.CAPACITY + 100
        for (i in 0 until total) {
            UsbRingLog.recordAudio(UsbRingLog.CODE_SINK_HANDLE_BUFFER, i.toLong(), i.toLong() * 10)
        }

        val dump = UsbRingLog.dump()
        assertEquals(UsbRingLog.CAPACITY, dump.size)
        // First entry in dump should be entry index 100
        assertTrue(dump.first().contains("bytes=100 ptsUs=1000"))
        // Last entry should be entry index (total - 1)
        val lastIdx = total - 1
        assertTrue(dump.last().contains("bytes=$lastIdx ptsUs=${lastIdx * 10}"))
    }
}

