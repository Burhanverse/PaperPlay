package io.max.paperplay.core.eq

import io.max.paperplay.core.db.LibraryDatabase
import io.max.paperplay.core.db.libraryDriver
import io.max.paperplay.player.ParametricBand
import io.max.paperplay.player.ParametricEqProfile
import io.max.paperplay.player.PeqChannel
import io.max.paperplay.player.PeqFilterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoEqDatabaseTest {
    @Test
    fun gate2_fts5SearchFindsIconicModelsUnder20ms() {
        val catalog = openAutoEqCatalog()
        try {
            assertTrue(catalog.isAvailable, "Bundled autoeq.db must be available")
            assertTrue(catalog.entryCount > 500, "Expected full AutoEQ catalog, got ${catalog.entryCount}")

            // Warm up SQLite page cache once
            catalog.search("Sennheiser", limit = 10)

            val queries = listOf(
                "HD 650" to "650",
                "Moondrop Chu 2" to "chu",
                "Truthear Zero" to "zero",
            )
            for ((q, expectToken) in queries) {
                val t0 = System.nanoTime()
                val hits = catalog.search(q, limit = 25)
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
                assertTrue(hits.isNotEmpty(), "Expected AutoEQ FTS5 hits for '$q'")
                assertTrue(
                    hits.any { it.model.lowercase().contains(expectToken) },
                    "Expected '$expectToken' in results for '$q', got ${hits.take(5).map { it.model }}",
                )
                assertTrue(elapsedMs < 20.0, "FTS5 search for '$q' took ${"%.2f".format(elapsedMs)} ms (budget < 20 ms)")
                val profile = hits.first().toProfile()
                assertTrue(profile.bands.isNotEmpty(), "AutoEQ profile '${profile.name}' should contain bands")
            }

            assertTrue(catalog.sources().isNotEmpty(), "Catalog must list measurement sources")
            assertTrue(catalog.forms().isNotEmpty(), "Catalog must list form factors")
        } finally {
            catalog.close()
        }
    }

    @Test
    fun eqRepositoryPersistsPresetsAndPerDeviceMappings() {
        val driver = libraryDriver(null)
        val db = LibraryDatabase(driver)
        var now = 1000L
        val repo = EqRepository(db) { now++ }

        val custom = ParametricEqProfile(
            id = "preset-hd650",
            name = "HD 650 Warm",
            source = "Custom",
            preampDb = -4.5f,
            autoPreamp = true,
            limiterEnabled = true,
            bands = listOf(
                ParametricBand(1, true, PeqFilterType.LSC, PeqChannel.STEREO, 105f, 4.5f, 0.71f),
                ParametricBand(2, true, PeqFilterType.PK, PeqChannel.LEFT, 3200f, -2.0f, 2.5f),
            ),
        )
        repo.savePreset(custom)
        val presets = repo.allPresets()
        assertEquals(1, presets.size)
        assertEquals("HD 650 Warm", presets[0].name)
        assertEquals(2, presets[0].bands.size)
        assertEquals(PeqChannel.LEFT, presets[0].bands[1].channel)

        repo.assignToDevice("usb:1234:5678", "FiiO KA13", custom, enabled = true)
        val mapping = repo.assignmentForDevice("usb:1234:5678")
        assertNotNull(mapping)
        assertEquals("FiiO KA13", mapping.deviceLabel)
        assertTrue(mapping.enabled)
        assertEquals("HD 650 Warm", mapping.profile.name)

        repo.unassignDevice("usb:1234:5678")
        assertEquals(null, repo.assignmentForDevice("usb:1234:5678"))
        repo.deletePreset("preset-hd650")
        assertTrue(repo.allPresets().isEmpty())
    }

    @Test
    fun autoEqUpdaterParsesIndexMarkdown() {
        val updater = AutoEqUpdater(io.max.paperplay.core.integrations.defaultHttpFetcher())
        val sample = """
            # Index
            - [Sennheiser HD 650](./oratory1990/over-ear/Sennheiser%20HD%20650) by oratory1990
            - [Moondrop Chu 2](./crinacle/711%20in-ear/Moondrop%20Chu%202) by crinacle
        """.trimIndent()
        val parsed = updater.parseIndexMarkdown(sample)
        assertEquals(2, parsed.size)
        assertEquals("Sennheiser HD 650", parsed[0].model)
        assertEquals("oratory1990", parsed[0].source)
        assertEquals("over-ear", parsed[0].form)
        assertEquals("Moondrop Chu 2", parsed[1].model)
        assertEquals("in-ear", parsed[1].form)
    }
}
