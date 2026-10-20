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
import org.isoron.uhabits.ancla.ui.enableDexterEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.habits.edit.HabitTypeDialog
import org.isoron.uhabits.activities.habits.list.ListHabitsActivity
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.TaskDraft
import org.isoron.uhabits.ancla.ui.TodayScreen
import org.isoron.uhabits.ancla.ui.AprenderScreen
import org.isoron.uhabits.ancla.ui.Branch
import org.isoron.uhabits.ancla.ui.CaminoScreen
import org.isoron.uhabits.ancla.ui.DexterHome
import org.isoron.uhabits.ancla.ui.Space
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CommandRunner
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.database.TaskRepository
import org.isoron.uhabits.core.models.DayTask
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.ui.screens.today.TodayHabitItem
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import org.isoron.uhabits.core.ui.screens.today.TodayTaskItem

/** Ancla's home screen: today's habits and tasks, the mascot and the day's progress. */
class TodayActivity : AppCompatActivity(), CommandRunner.Listener {

    private val component get() = (application as HabitsApplication).component
    private val tasks by lazy { TaskRepository(component.db) }
    private val summary = mutableStateOf<TodaySummary?>(null)
    private val hopKey = mutableIntStateOf(0)
    private lateinit var voice: org.isoron.uhabits.ancla.assistant.VoiceAgentController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableDexterEdgeToEdge()
        super.onCreate(savedInstanceState)
        voice = org.isoron.uhabits.ancla.assistant.VoiceAgentController(this) { refresh() }
        Wisdom.schedule(this)
        val goals = GoalStore(this)
        val money = org.isoron.uhabits.ancla.finance.FinanceStore(this)
        val consciousness = org.isoron.uhabits.ancla.consciousness.ConsciousnessStore(this)
        setContent {
            AnclaTheme {
                DexterHome(onDexter = ::openAssistant, onBranch = ::openBranch) { space, openMenu, go ->
                    when (space) {
                        Space.HOY -> TodayScreen(
                            summary = summary.value,
                            hopKey = hopKey.intValue,
                            onToggle = ::toggle,
                            onOpen = ::openHabit,
                            onToggleTask = ::toggleTask,
                            onSaveTask = ::saveTask,
                            onDeleteTask = ::deleteTask,
                            onAddHabit = { HabitTypeDialog().show(supportFragmentManager, "habitType") },
                            onMenu = openMenu,
                            onConsciencia = { go(Space.CONSCIENCIA) },
                            voice = voice,
                            onAssistant = ::openAssistant
                        )
                        Space.CAMINO -> CaminoScreen(goals, openMenu, ::openAssistant)
                        Space.FINANZAS -> org.isoron.uhabits.ancla.finance.FinanzasScreen(money, openMenu)
                        Space.APRENDER -> AprenderScreen(openMenu, ::openBranch)
                        Space.CONSCIENCIA -> org.isoron.uhabits.ancla.consciousness.ConscienciaScreen(consciousness, openMenu, ::openAssistant)
                    }
                }
            }
        }
    }

    private fun openAssistant() {
        startActivity(org.isoron.uhabits.ancla.assistant.AssistantActivity.intent(this))
    }

    /** The branches of the Espacios panel and of Aprender, each on its own screen. */
    private fun openBranch(branch: Branch) {
        val intent = when (branch) {
            Branch.HABITS -> Intent(this, ListHabitsActivity::class.java)
            Branch.LIBRARY -> Intent(this, org.isoron.uhabits.ancla.reading.LibraryActivity::class.java)
            Branch.NOTES -> org.isoron.uhabits.ancla.reading.NotesActivity.intent(this)
            Branch.STUDY -> org.isoron.uhabits.ancla.reading.NotesActivity.intent(this, tab = "study")
            Branch.ROBOTICS -> org.isoron.uhabits.ancla.robotics.RoboticsActivity.intent(this)
            Branch.DEXTER -> org.isoron.uhabits.ancla.assistant.AssistantActivity.intent(this)
            Branch.PHONE -> org.isoron.uhabits.ancla.assistant.DexterSetupActivity.intent(this, org.isoron.uhabits.ancla.assistant.DexterSetupActivity.PHONE)
            Branch.WHATSAPP -> org.isoron.uhabits.ancla.assistant.DexterSetupActivity.intent(this, org.isoron.uhabits.ancla.assistant.DexterSetupActivity.WHATSAPP)
            Branch.SETTINGS -> component.intentFactory.startSettingsActivity(this)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        voice.onResume()
        component.commandRunner.addListener(this)
        refresh()
    }

    override fun onPause() {
        voice.onPause()
        component.commandRunner.removeListener(this)
        super.onPause()
    }

    override fun onCommandFinished(command: Command) {
        refresh()
    }

    override fun onDestroy() {
        if (::voice.isInitialized) voice.release()
        super.onDestroy()
    }

    private fun refresh() {
        val today = getToday()
        summary.value = TodaySummary.build(component.habitList, today, tasks.findForToday(today))
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

    private fun toggleTask(item: TodayTaskItem) {
        val task = tasks.findById(item.taskId) ?: return
        val updated = task.copy(isDone = !task.isDone)
        tasks.update(updated)
        if (updated.isDone) {
            hopKey.intValue += 1
            TaskReminders.cancel(this, item.taskId)
        } else {
            TaskReminders.sync(this, updated)
        }
        refresh()
    }

    private fun saveTask(draft: TaskDraft) {
        val title = draft.title.trim()
        if (title.isEmpty()) return
        val existing = draft.id?.let { tasks.findById(it) }
        val task = (existing ?: DayTask(title = title, day = getToday())).copy(
            title = title,
            notes = draft.notes.trim(),
            minuteOfDay = draft.minuteOfDay,
            remind = draft.remind && draft.minuteOfDay != null
        )
        val saved = if (existing == null) task.copy(id = tasks.insert(task)) else task.also { tasks.update(it) }
        TaskReminders.sync(this, saved)
        refresh()
    }

    private fun deleteTask(taskId: Long) {
        tasks.delete(taskId)
        TaskReminders.cancel(this, taskId)
        refresh()
    }

    private fun openHabit(item: TodayHabitItem) {
        val habit = component.habitList.getById(item.habitId) ?: return
        startActivity(component.intentFactory.startShowHabitActivity(this, habit))
    }
}
