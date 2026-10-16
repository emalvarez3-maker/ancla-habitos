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

package org.isoron.uhabits.core.ui.screens.habits.show.views

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.commands.ChangeHabitTargetCommand
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.SaveSelfReportCommand
import org.isoron.uhabits.core.models.Automaticity
import org.isoron.uhabits.core.models.AutomaticityReport
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.PaletteColor
import org.isoron.uhabits.core.ui.views.Theme

data class AutomaticityCardState(
    val color: PaletteColor,
    val report: AutomaticityReport,
    val habitName: String,
    val isNumerical: Boolean,
    val unit: String,
    val anchor: String,
    val place: String,
    val tinyVersion: String,
    val ifThen: String,
    val theme: Theme
)

class AutomaticityCardPresenter(
    val habit: Habit,
    val habitList: HabitList,
    val commandRunner: CommandRunner
) {
    fun onApplyLevel(newTarget: Double) {
        if (!habit.isNumerical || newTarget <= 0) return
        commandRunner.run(ChangeHabitTargetCommand(habitList, habit, newTarget))
    }

    /** [answers] are the four SRBAI answers, each from 1 to 7. */
    fun onSaveSelfReport(answers: List<Int>) {
        val value = Automaticity.normalizeSelfReport(answers)
        commandRunner.run(SaveSelfReportCommand(habitList, habit, value, getToday()))
    }

    companion object {
        fun buildState(habit: Habit, theme: Theme): AutomaticityCardState {
            return AutomaticityCardState(
                color = habit.color,
                report = Automaticity.compute(habit, getToday()),
                habitName = habit.name,
                isNumerical = habit.isNumerical,
                unit = habit.unit,
                anchor = habit.anchor,
                place = habit.place,
                tinyVersion = habit.tinyVersion,
                ifThen = habit.ifThen,
                theme = theme
            )
        }
    }
}
