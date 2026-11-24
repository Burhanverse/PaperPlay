package io.max.paperplay.ui.app

import io.max.paperplay.core.library.ListenTotal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStatsTest {
    private val today = epochDay("2026-11-24")!!
    private fun day(back: Int) = io.max.paperplay.core.app.dayOf((today - back) * 86_400_000L)
    private fun stats(vararg back: Int, all: List<ListenTotal> = emptyList()) =
        computeProfileStats(back.associate { day(it) to 600_000L }, emptyList(), all, today)

    @Test
    fun epochDayParses() {
        assertEquals(0L, epochDay("1970-01-01"))
        assertEquals(1L, epochDay("1970-01-02"))
        assertNull(epochDay("nope"))
    }

    @Test
    fun streakCountsBackFromToday() {
        val s = stats(0, 1, 2, 5)
        assertEquals(3, s.currentStreak)
        assertEquals(3, s.longestStreak)
        assertTrue(s.listenedToday)
    }

    @Test
    fun streakSurvivesAnEmptyToday() {
        val s = stats(1, 2)
        assertEquals(2, s.currentStreak)
        assertFalse(s.listenedToday)
    }

    @Test
    fun streakBreaksOnAGap() {
        val s = stats(2, 3, 4, 5, 6)
        assertEquals(0, s.currentStreak)
        assertEquals(5, s.longestStreak)
    }

    @Test
    fun heatGridEndsOnTodayAndHidesTheFuture() {
        val s = stats(0)
        assertEquals(17 * 7, s.heat.size)
        val row = ((today + 3).mod(7L)).toInt()
        assertEquals(600_000L, s.heat[16 * 7 + row])
        if (row < 6) assertEquals(-1L, s.heat[16 * 7 + row + 1])
        assertEquals(600_000L, s.week.last())
    }

    @Test
    fun heatLevels() {
        assertEquals(0, heatLevel(0))
        assertEquals(1, heatLevel(60_000))
        assertEquals(2, heatLevel(30 * 60_000L))
        assertEquals(3, heatLevel(90 * 60_000L))
        assertEquals(4, heatLevel(3 * 3_600_000L))
    }

    @Test
    fun personalityByHabit() {
        assertEquals("Newcomer", personality(3, 5, 100_000).name)
        assertEquals("Loyalist", personality(20, 100, 10 * 3_600_000L).name)
        assertEquals("Explorer", personality(200, 250, 10 * 3_600_000L).name)
        assertEquals("Balanced", personality(50, 100, 10 * 3_600_000L).name)
    }

    @Test
    fun badgesFollowHoursAndStreak() {
        val hours = listOf(ListenTotal("a", 12 * 3_600_000L, 1, "t", "x"))
        val s = stats(0, 1, 2, 3, 4, 5, 6, all = hours)
        assertEquals(listOf(true, false, false, true, false, false), s.badges.map { it.earned })
    }

    @Test
    fun topArtistSplitsCredits() {
        val month = listOf(ListenTotal("a", 100, 1, "t", "A, B"), ListenTotal("b", 50, 1, "u", "B"))
        val s = computeProfileStats(emptyMap(), month, month, today)
        assertEquals("B" to 150L, s.topArtist)
    }
}
