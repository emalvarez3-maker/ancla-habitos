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
import org.isoron.uhabits.core.models.Automaticity
import org.isoron.uhabits.core.models.AutomaticityPhase
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType

enum class TodayMood { SLEEPY, CALM, HAPPY, JOY }

data class TodayHabitItem(
    val habitId: Long,
    val name: String,
    val anchor: String,
    val tinyVersion: String,
    /** Minutes after midnight of the reminder, used for ordering; null when there is none. */
    val minuteOfDay: Int?,
    val isDone: Boolean,
    val isKeyDay: Boolean,
    val isNumerical: Boolean,
    val target: Double,
    val unit: String,
    /** Today's numerical value, or 0 for yes/no habits. */
    val value: Double
)

data class TodaySummary(
    val items: List<TodayHabitItem>,
    val doneCount: Int,
    val totalCount: Int,
    val mood: TodayMood,
    /** Average automaticity of all active habits, from 0 to 1. */
    val averageAutomaticity: Double,
    val phase: AutomaticityPhase,
    val xp: Int,
    val level: Int,
    val xpInLevel: Int
) {
    companion object {
        const val XP_PER_REPETITION = 10
        const val XP_PER_LEVEL = 400

        fun isDoneToday(habit: Habit, today: LocalDate): Boolean {
            val value = habit.computedEntries.get(today).value
            if (!habit.isNumerical) return value == Entry.YES_MANUAL
            if (value == Entry.UNKNOWN || value == Entry.SKIP) return false
            val amount = value.coerceAtLeast(0) / 1000.0
            val share = habit.targetValue / habit.frequency.denominator
            return when (habit.targetType) {
                NumericalHabitType.AT_LEAST -> amount >= share && amount > 0
                NumericalHabitType.AT_MOST -> amount <= share
            }
        }

        fun build(habitList: HabitList, today: LocalDate): TodaySummary {
            val habits = habitList.filter { !it.isArchived && it.id != null }
            var automaticitySum = 0.0
            var repetitions = 0
            val items = habits.map { h ->
                val report = Automaticity.compute(h, today)
                automaticitySum += report.automaticity
                repetitions += report.repetitions
                val raw = h.computedEntries.get(today).value
                TodayHabitItem(
                    habitId = h.id!!,
                    name = h.name,
                    anchor = h.anchor,
                    tinyVersion = h.tinyVersion,
                    minuteOfDay = h.reminder?.let { it.hour * 60 + it.minute },
                    isDone = isDoneToday(h, today),
                    isKeyDay = report.suggestions.any {
                        it.type == org.isoron.uhabits.core.models.CoachSuggestionType.KEY_DAY ||
                            it.type == org.isoron.uhabits.core.models.CoachSuggestionType.RESTART
                    },
                    isNumerical = h.isNumerical,
                    target = h.targetValue / h.frequency.denominator,
                    unit = h.unit,
                    value = if (h.isNumerical && raw > 0) raw / 1000.0 else 0.0
                )
            }.sortedWith(compareBy<TodayHabitItem>({ it.minuteOfDay ?: Int.MAX_VALUE }, { it.name.lowercase() }))

            val done = items.count { it.isDone }
            val total = items.size
            val keyPending = items.any { it.isKeyDay && !it.isDone }
            val mood = when {
                total > 0 && done == total -> TodayMood.JOY
                done == 0 && keyPending -> TodayMood.SLEEPY
                total > 0 && done * 2 >= total -> TodayMood.HAPPY
                else -> TodayMood.CALM
            }
            val average = if (habits.isEmpty()) 0.0 else automaticitySum / habits.size
            val xp = repetitions * XP_PER_REPETITION
            return TodaySummary(
                items = items,
                doneCount = done,
                totalCount = total,
                mood = mood,
                averageAutomaticity = average,
                phase = Automaticity.phaseOf(average),
                xp = xp,
                level = 1 + xp / XP_PER_LEVEL,
                xpInLevel = xp % XP_PER_LEVEL
            )
        }
    }
}
