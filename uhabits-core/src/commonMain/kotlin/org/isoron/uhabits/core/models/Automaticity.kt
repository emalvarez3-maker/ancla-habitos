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

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class AutomaticityPhase { INITIATION, LEARNING, STABILITY, AUTOMATIC }

enum class CoachSuggestionType { KEY_DAY, RESTART, LEVEL_UP, LEVEL_DOWN, FRESH_START }

data class CoachSuggestion(
    val type: CoachSuggestionType,
    /** New target value, for LEVEL_UP and LEVEL_DOWN. */
    val value: Double? = null
)

data class AutomaticityReport(
    /** Modeled automaticity, from 0 (none) to 1 (fully automatic). */
    val automaticity: Double,
    val phase: AutomaticityPhase,
    /** Repetitions in a row, forgiving one isolated miss ("never miss twice"). */
    val flexibleStreak: Int,
    val bestFlexibleStreak: Int,
    /** True when the current flexible streak survived an isolated miss. */
    val graceUsed: Boolean,
    val repetitions: Int,
    /** Missed expected repetitions in a row, up to yesterday. */
    val missesBeforeToday: Double,
    /** Fraction of expected repetitions completed in the last 14 days, or null if too new. */
    val completionRate: Double?,
    val repetitionsLeft: Int,
    val daysLeft: Int,
    val suggestions: List<CoachSuggestion>
)

/**
 * Habit formation model used by Ancla, based on Lally et al. (2010): automaticity grows
 * asymptotically with each repetition, so that 66 ideal repetitions reach 95%. An isolated
 * miss barely affects it; consecutive misses erode it.
 */
object Automaticity {
    /** Learning rate: (1 - K)^66 = 0.05. */
    val K = 1.0 - 0.05.pow(1.0 / 66)
    const val PARTIAL_WEIGHT = 0.6
    const val FIRST_MISS_DECAY = 0.015
    const val REPEATED_MISS_DECAY = 0.06
    const val AUTOMATIC_THRESHOLD = 0.95
    private const val WINDOW = 14

    fun phaseOf(a: Double): AutomaticityPhase = when {
        a >= 0.9 -> AutomaticityPhase.AUTOMATIC
        a >= 0.65 -> AutomaticityPhase.STABILITY
        a >= 0.25 -> AutomaticityPhase.LEARNING
        else -> AutomaticityPhase.INITIATION
    }

    private enum class Day { FULL, PARTIAL, MISS, REST }

    fun compute(habit: Habit, today: LocalDate): AutomaticityReport {
        val freq = habit.frequency
        val known = habit.computedEntries.getKnown()
        val first = known.lastOrNull()?.date
        if (first == null || first.isNewerThan(today)) return empty()

        // Each missed day weighs as much as one expected repetition would.
        val missWeight = if (habit.isNumerical) 1.0 / freq.denominator else freq.toDouble()
        val dailyShare = if (habit.isNumerical) habit.targetValue / freq.denominator else 1.0

        fun classify(date: LocalDate): Day {
            val value = habit.computedEntries.get(date).value
            if (habit.isNumerical) {
                if (value == Entry.SKIP) return Day.REST
                val amount = max(0, value) / 1000.0
                return if (habit.targetType == NumericalHabitType.AT_MOST) {
                    if (value == Entry.UNKNOWN) Day.MISS else if (amount <= dailyShare) Day.FULL else Day.MISS
                } else {
                    when {
                        value == Entry.UNKNOWN || amount <= 0.0 -> Day.MISS
                        amount >= dailyShare -> Day.FULL
                        else -> Day.PARTIAL
                    }
                }
            }
            return when (value) {
                Entry.YES_MANUAL -> Day.FULL
                Entry.YES_AUTO, Entry.SKIP -> Day.REST
                else -> Day.MISS
            }
        }

        var a = 0.0
        var misses = 0.0
        var missesBeforeToday = 0.0
        var flex = 0
        var best = 0
        var grace = false
        var reps = 0
        var date: LocalDate = first
        while (!date.isNewerThan(today)) {
            val day = classify(date)
            when (day) {
                Day.FULL, Day.PARTIAL -> {
                    val m = if (day == Day.FULL) 1.0 else PARTIAL_WEIGHT
                    a += K * m * (1 - a)
                    reps++
                    flex++
                    best = max(best, flex)
                    misses = 0.0
                }
                Day.MISS -> if (date != today) {
                    val wasIsolated = misses < 1.0
                    misses += missWeight
                    val decay = if (misses <= 1.0) FIRST_MISS_DECAY else REPEATED_MISS_DECAY
                    a *= 1 - decay * missWeight
                    if (misses >= 2.0) {
                        flex = 0
                        grace = false
                    } else if (wasIsolated && misses >= 1.0 && flex > 0) {
                        grace = true
                    }
                }
                Day.REST -> Unit
            }
            if (date == today.minus(1)) missesBeforeToday = misses
            date = date.plus(1)
        }

        val rate = completionRate(habit, today, first, ::classify)
        val repsLeft = if (a >= AUTOMATIC_THRESHOLD) {
            0
        } else {
            ceil(ln((1 - AUTOMATIC_THRESHOLD) / (1 - a)) / ln(1 - K)).toInt()
        }
        val perDay = if (habit.isNumerical) 1.0 else freq.toDouble()
        val daysLeft = ceil(repsLeft / perDay).toInt()

        return AutomaticityReport(
            automaticity = a,
            phase = phaseOf(a),
            flexibleStreak = flex,
            bestFlexibleStreak = best,
            graceUsed = grace,
            repetitions = reps,
            missesBeforeToday = missesBeforeToday,
            completionRate = rate,
            repetitionsLeft = repsLeft,
            daysLeft = daysLeft,
            suggestions = suggest(habit, today, first, classify(today), missesBeforeToday, rate)
        )
    }

    private fun completionRate(
        habit: Habit,
        today: LocalDate,
        first: LocalDate,
        classify: (LocalDate) -> Day
    ): Double? {
        val start = today.minus(WINDOW - 1)
        if (first.isNewerThan(today.minus(6))) return null
        val from = if (first.isNewerThan(start)) first else start
        val days = from.daysUntil(today) + 1
        var done = 0.0
        var d = from
        while (!d.isNewerThan(today)) {
            when (classify(d)) {
                Day.FULL -> done += 1.0
                Day.PARTIAL -> done += PARTIAL_WEIGHT
                else -> Unit
            }
            d = d.plus(1)
        }
        val expected = if (habit.isNumerical) days.toDouble() else days * habit.frequency.toDouble()
        return min(1.0, done / max(1.0, expected))
    }

    private fun suggest(
        habit: Habit,
        today: LocalDate,
        first: LocalDate,
        todayStatus: Day,
        missesBeforeToday: Double,
        rate: Double?
    ): List<CoachSuggestion> {
        val out = mutableListOf<CoachSuggestion>()
        val doneToday = todayStatus == Day.FULL || todayStatus == Day.PARTIAL
        if (!doneToday && missesBeforeToday >= 2.0) {
            out.add(CoachSuggestion(CoachSuggestionType.RESTART))
        } else if (!doneToday && missesBeforeToday >= 1.0) {
            out.add(CoachSuggestion(CoachSuggestionType.KEY_DAY))
        }
        val tracked = first.daysUntil(today) + 1
        val canAdjust = habit.isNumerical &&
            habit.targetType == NumericalHabitType.AT_LEAST &&
            habit.targetValue > 0 &&
            tracked >= WINDOW
        if (canAdjust && rate != null) {
            if (rate >= 0.85) {
                out.add(CoachSuggestion(CoachSuggestionType.LEVEL_UP, levelUp(habit.targetValue)))
            } else if (rate < 0.5) {
                val down = levelDown(habit.targetValue)
                if (down < habit.targetValue) out.add(CoachSuggestion(CoachSuggestionType.LEVEL_DOWN, down))
            }
        }
        val freshStart = today.dayOfWeek == DayOfWeek.MONDAY || today.day == 1
        if (freshStart && rate != null && rate < 0.6) out.add(CoachSuggestion(CoachSuggestionType.FRESH_START))
        return out
    }

    /** About 15% harder, and always at least one unit more for small targets. */
    fun levelUp(target: Double): Double = if (target < 5) floor(target) + 1 else ceil(target * 1.15)

    /** About 30% easier, never below one unit. */
    fun levelDown(target: Double): Double = max(1.0, floor(target * 0.7))

    private fun empty(): AutomaticityReport {
        val repsLeft = ceil(ln(1 - AUTOMATIC_THRESHOLD) / ln(1 - K)).toInt()
        return AutomaticityReport(
            automaticity = 0.0,
            phase = AutomaticityPhase.INITIATION,
            flexibleStreak = 0,
            bestFlexibleStreak = 0,
            graceUsed = false,
            repetitions = 0,
            missesBeforeToday = 0.0,
            completionRate = null,
            repetitionsLeft = repsLeft,
            daysLeft = repsLeft,
            suggestions = emptyList()
        )
    }
}
