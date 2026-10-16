/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import kotlin.math.pow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomaticityTest : BaseUnitTest() {
    private lateinit var habit: Habit
    private lateinit var today: LocalDate

    @BeforeTest
    override fun setUp() {
        super.setUp()
        today = getToday()
        habit = fixtures.createEmptyHabit()
    }

    private fun yes(vararg offsets: Int) {
        offsets.forEach { habit.originalEntries.add(Entry(today.minus(it), Entry.YES_MANUAL)) }
        habit.recompute()
    }

    private fun report() = Automaticity.compute(habit, today)

    @Test
    fun testEmptyHabit() {
        val r = report()
        assertEquals(0.0, r.automaticity)
        assertEquals(AutomaticityPhase.INITIATION, r.phase)
        assertTrue(r.repetitionsLeft in 66..67, "got ${r.repetitionsLeft}")
    }

    @Test
    fun test66RepetitionsReach95Percent() {
        yes(*(0 until 66).toList().toIntArray())
        val r = report()
        assertTrue(kotlin.math.abs(r.automaticity - 0.95) < 1e-9, "got ${r.automaticity}")
        assertEquals(AutomaticityPhase.AUTOMATIC, r.phase)
        assertEquals(66, r.flexibleStreak)
        assertTrue(r.repetitionsLeft <= 1, "got ${r.repetitionsLeft}")
    }

    @Test
    fun testIsolatedMissKeepsFlexibleStreak() {
        yes(0, 1, 2, 4, 5, 6)
        val r = report()
        assertEquals(6, r.flexibleStreak)
        assertTrue(r.graceUsed)
        val expected = (1 - (1 - Automaticity.K).pow(3)) * (1 - Automaticity.FIRST_MISS_DECAY)
        val afterThreeMore = 1 - (1 - expected) * (1 - Automaticity.K).pow(3)
        assertTrue(kotlin.math.abs(r.automaticity - afterThreeMore) < 1e-9)
    }

    @Test
    fun testTwoMissesBreakFlexibleStreak() {
        yes(0, 1, 4, 5, 6)
        val r = report()
        assertEquals(2, r.flexibleStreak)
        assertEquals(3, r.bestFlexibleStreak)
    }

    @Test
    fun testKeyDayAfterOneMiss() {
        yes(2, 3, 4)
        val r = report()
        assertEquals(1.0, r.missesBeforeToday)
        assertEquals(listOf(CoachSuggestion(CoachSuggestionType.KEY_DAY)), r.suggestions.filter { it.type != CoachSuggestionType.FRESH_START })
    }

    @Test
    fun testRestartAfterTwoMisses() {
        yes(3, 4, 5)
        assertTrue(report().suggestions.any { it.type == CoachSuggestionType.RESTART })
    }

    @Test
    fun testNoKeyDayWhenDoneToday() {
        yes(0, 2, 3)
        assertTrue(report().suggestions.none { it.type == CoachSuggestionType.KEY_DAY })
    }

    @Test
    fun testCompletionRateNeedsOneWeek() {
        yes(0, 1, 2)
        assertNull(report().completionRate)
        yes(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
        assertEquals(1.0, report().completionRate)
    }

    @Test
    fun testLevelUpAndDown() {
        assertEquals(2.0, Automaticity.levelUp(1.0))
        assertEquals(35.0, Automaticity.levelUp(30.0))
        assertEquals(21.0, Automaticity.levelDown(30.0))
        assertEquals(1.0, Automaticity.levelDown(1.0))
    }

    @Test
    fun testNumericalLevelUpSuggestion() {
        habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST)
        habit.frequency = Frequency.DAILY
        habit.targetValue = 10.0
        for (i in 0 until 20) habit.originalEntries.add(Entry(today.minus(i), 12_000))
        habit.recompute()
        val up = report().suggestions.firstOrNull { it.type == CoachSuggestionType.LEVEL_UP }
        assertEquals(12.0, up?.value)
    }

    @Test
    fun testNumericalPartialCountsLess() {
        habit = fixtures.createEmptyNumericalHabit(NumericalHabitType.AT_LEAST)
        habit.frequency = Frequency.DAILY
        habit.targetValue = 10.0
        habit.originalEntries.add(Entry(today, 5_000))
        habit.recompute()
        val r = report()
        assertTrue(kotlin.math.abs(r.automaticity - Automaticity.K * Automaticity.PARTIAL_WEIGHT) < 1e-9)
    }
}
