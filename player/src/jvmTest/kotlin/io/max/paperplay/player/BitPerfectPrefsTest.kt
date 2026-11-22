package io.max.paperplay.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitPerfectPrefsTest {
    private class MapStore(initial: Map<String, String> = emptyMap()) : PrefKeyValueStore {
        val map = initial.toMutableMap()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
    }

    @Test
    fun `fresh desktop and android installs default to OFF`() {
        assertEquals(BitPerfectMode.OFF, BitPerfectPrefs.loadDesktop(MapStore()))
        assertEquals(BitPerfectMode.OFF, BitPerfectPrefs.loadAndroid(MapStore(), MapStore()))
    }

    @Test
    fun `desktop migrates non-destructively from legacy keys and respects subsequent toggle off`() {
        val store = MapStore(mapOf(BitPerfectPrefs.LEGACY_PC_EXCLUSIVE to "true"))
        assertEquals(BitPerfectMode.ON, BitPerfectPrefs.loadDesktop(store))
        assertEquals("true", store.get(BitPerfectPrefs.KEY_CANONICAL))
        assertEquals("true", store.get(BitPerfectPrefs.KEY_BACKUP_DESKTOP))
        // Legacy key must not be deleted or overwritten
        assertEquals("true", store.get(BitPerfectPrefs.LEGACY_PC_EXCLUSIVE))

        BitPerfectPrefs.saveDesktop(store, BitPerfectMode.OFF)
        assertEquals(BitPerfectMode.OFF, BitPerfectPrefs.loadDesktop(store))
    }

    @Test
    fun `android migrates non-destructively from legacy usb key`() {
        val sp = MapStore(mapOf(BitPerfectPrefs.LEGACY_USB_EXCLUSIVE to "true"))
        assertEquals(BitPerfectMode.ON, BitPerfectPrefs.loadAndroid(sp))
        assertEquals("true", sp.get(BitPerfectPrefs.KEY_CANONICAL))
        assertEquals("true", sp.get(BitPerfectPrefs.KEY_BACKUP_ANDROID))
        assertEquals("true", sp.get(BitPerfectPrefs.LEGACY_USB_EXCLUSIVE))
    }

    @Test
    fun `backup restore isolates android and desktop platform keys`() {
        assertNull(BitPerfectPrefs.resolveBackupRestore(isPhone = true, mapOf(BitPerfectPrefs.KEY_BACKUP_DESKTOP to "true")))
        assertNull(BitPerfectPrefs.resolveBackupRestore(isPhone = false, mapOf(BitPerfectPrefs.KEY_BACKUP_ANDROID to "true")))
        assertEquals(
            BitPerfectMode.ON,
            BitPerfectPrefs.resolveBackupRestore(isPhone = true, mapOf(BitPerfectPrefs.KEY_BACKUP_ANDROID to "true")),
        )
        assertEquals(
            BitPerfectMode.OFF,
            BitPerfectPrefs.resolveBackupRestore(isPhone = false, mapOf(BitPerfectPrefs.KEY_BACKUP_DESKTOP to "false")),
        )
    }

    @Test
    fun `allowEqInUsbExclusive defaults to true and persists cleanly`() {
        val sp = MapStore()
        val db = MapStore()
        assertEquals(true, BitPerfectPrefs.loadAllowEqInUsbExclusive(sp, db))

        BitPerfectPrefs.saveAllowEqInUsbExclusive(sp, db, false)
        assertEquals(false, BitPerfectPrefs.loadAllowEqInUsbExclusive(sp, db))
        assertEquals("false", sp.get(BitPerfectPrefs.KEY_ALLOW_EQ_IN_USB_EXCLUSIVE))
        assertEquals("false", db.get(BitPerfectPrefs.KEY_ALLOW_EQ_IN_USB_EXCLUSIVE))

        BitPerfectPrefs.saveAllowEqInUsbExclusive(sp, db, true)
        assertEquals(true, BitPerfectPrefs.loadAllowEqInUsbExclusive(sp, db))
    }
}
