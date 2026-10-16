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

package org.isoron.uhabits.ancla

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.ListHabitsActivity
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.TodayScreen
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.ui.screens.today.TodayHabitItem
import org.isoron.uhabits.core.ui.screens.today.TodaySummary

/** Ancla's home screen: today's habits, the mascot and the day's progress. */
class TodayActivity : AppCompatActivity(), CommandRunner.Listener {

    private val component get() = (application as HabitsApplication).component
    private val summary = mutableStateOf<TodaySummary?>(null)
    private val hopKey = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnclaTheme {
                TodayScreen(
                    summary = summary.value,
                    hopKey = hopKey.intValue,
                    onToggle = ::toggle,
                    onOpen = ::openHabit,
                    onAdd = { HabitTypeDialog().show(supportFragmentManager, "habitType") },
                    onHabits = { startActivity(Intent(this, ListHabitsActivity::class.java)) },
                    onSettings = { startActivity(component.intentFactory.startSettingsActivity(this)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        component.commandRunner.addListener(this)
        refresh()
    }

    override fun onPause() {
        component.commandRunner.removeListener(this)
        super.onPause()
    }

    override fun onCommandFinished(command: Command) {
        refresh()
    }

    private fun refresh() {
        summary.value = TodaySummary.build(component.habitList, getToday())
    }

    private fun toggle(item: TodayHabitItem) {
        val habit = component.habitList.getById(item.habitId) ?: return
        val value = when {
            !item.isNumerical -> if (item.isDone) Entry.NO else Entry.YES_MANUAL
            item.isDone -> 0
            else -> (item.target * 1000).toInt().coerceAtLeast(1000)
        }
        if (!item.isDone) hopKey.intValue += 1
        component.commandRunner.run(CreateRepetitionCommand(component.habitList, habit, getToday(), value, ""))
    }

    private fun openHabit(item: TodayHabitItem) {
        val habit = component.habitList.getById(item.habitId) ?: return
        startActivity(component.intentFactory.startShowHabitActivity(this, habit))
    }
}
