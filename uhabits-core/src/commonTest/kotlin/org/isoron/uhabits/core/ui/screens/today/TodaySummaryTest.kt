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
package org.isoron.uhabits.core.ui.screens.today

import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.BaseUnitTest
import org.isoron.uhabits.core.models.DayTask
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodaySummaryTest : BaseUnitTest() {
    private lateinit var today: LocalDate

    @BeforeTest
    override fun setUp() {
        super.setUp()
        today = getToday()
    }

    private fun habit(name: String, vararg doneOffsets: Int): Habit {
        val h = fixtures.createEmptyHabit(name)
        habitList.add(h)
        doneOffsets.forEach { h.originalEntries.add(Entry(today.minus(it), Entry.YES_MANUAL)) }
        h.recompute()
        return h
    }

    @Test
    fun testEmpty() {
        val s = TodaySummary.build(habitList, today)
        assertEquals(0, s.totalCount)
        assertEquals(TodayMood.CALM, s.mood)
        assertEquals(1, s.level)
    }

    @Test
    fun testAllDoneIsJoy() {
        habit("A", 0, 1)
        habit("B", 0)
        val s = TodaySummary.build(habitList, today)
        assertEquals(2, s.doneCount)
        assertEquals(TodayMood.JOY, s.mood)
        assertEquals(3 * TodaySummary.XP_PER_REPETITION, s.xp)
    }

    @Test
    fun testPendingKeyDayIsSleepy() {
        habit("A", 2, 3)
        habit("B", 1)
        val s = TodaySummary.build(habitList, today)
        assertEquals(0, s.doneCount)
        assertTrue(s.items.first { it.name == "A" }.isKeyDay)
        assertEquals(TodayMood.SLEEPY, s.mood)
    }

    @Test
    fun testTasksCountTowardsProgress() {
        habit("A", 0)
        val tasks = listOf(
            DayTask(id = 1, title = "Informe", day = today, minuteOfDay = 540),
            DayTask(id = 2, title = "Atrasada", day = today.minus(1)),
            DayTask(id = 3, title = "Pagar", day = today, isDone = true)
        )
        val s = TodaySummary.build(habitList, today, tasks)
        assertEquals(4, s.totalCount)
        assertEquals(2, s.doneCount)
        assertEquals(listOf("Atrasada", "Informe", "Pagar"), s.tasks.map { it.title })
        assertTrue(s.tasks.first().isOverdue)
        assertEquals(TodayMood.HAPPY, s.mood)
    }

    @Test
    fun testHalfDoneIsHappy() {
        habit("A", 0)
        habit("B", 1)
        assertEquals(TodayMood.HAPPY, TodaySummary.build(habitList, today).mood)
    }
}
