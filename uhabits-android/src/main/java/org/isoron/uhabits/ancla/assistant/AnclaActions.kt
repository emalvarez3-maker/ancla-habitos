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

package org.isoron.uhabits.ancla.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.ancla.TaskReminders
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.database.BookRepository
import org.isoron.uhabits.core.database.TaskRepository
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.DayTask
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.ReadingStats
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import java.text.Normalizer
import java.util.concurrent.CountDownLatch

/** The agent's tools, backed by Ancla's habits, tasks and library. Safe to call from a background thread. */
class AnclaActions(private val context: Context) : AgentActions {
    private val component = (context.applicationContext as HabitsApplication).component
    private val tasks = TaskRepository(component.db)
    private val books = BookRepository(component.db)
    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits, since Loop's models and commands expect it. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await()
        return result!!.getOrThrow()
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").trim()

    override fun createTask(title: String, date: String?, time: String?, remind: Boolean): String = onMain {
        val day = date?.let { d ->
            val p = d.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size == 3) LocalDate(p[0], p[1], p[2]) else null
        } ?: getToday()
        val minute = time?.let { t ->
            val p = t.split(":").mapNotNull { it.toIntOrNull() }
            if (p.size == 2 && p[0] in 0..23 && p[1] in 0..59) p[0] * 60 + p[1] else null
        }
        val task = DayTask(title = title.trim(), day = day, minuteOfDay = minute, remind = remind && minute != null)
        val saved = task.copy(id = tasks.insert(task))
        TaskReminders.sync(context, saved)
        buildString {
            append("Tarea creada: «${saved.title}»")
            if (day != getToday()) append(" para el $date")
            if (minute != null) append(" a las ${"%02d:%02d".format(minute / 60, minute % 60)}")
            if (saved.remind) append(", con recordatorio")
        }
    }

    override fun completeHabit(name: String): String = onMain {
        val wanted = normalize(name)
        val habits = component.habitList.filter { !it.isArchived }
        val habit = habits.firstOrNull { normalize(it.name) == wanted }
            ?: habits.firstOrNull { normalize(it.name).contains(wanted) || wanted.contains(normalize(it.name)) }
            ?: throw IllegalArgumentException("No hay un hábito llamado «$name». Hábitos: ${habits.joinToString { it.name }}")
        val value = if (habit.isNumerical) {
            (habit.targetValue / habit.frequency.denominator * 1000).toInt().coerceAtLeast(1000)
        } else {
            Entry.YES_MANUAL
        }
        component.commandRunner.run(CreateRepetitionCommand(component.habitList, habit, getToday(), value, ""))
        "Hábito «${habit.name}» marcado como hecho hoy"
    }

    override fun todaySummary(): String = onMain {
        val today = getToday()
        val s = TodaySummary.build(component.habitList, today, tasks.findForToday(today))
        buildString {
            appendLine("Hoy: ${s.doneCount} de ${s.totalCount} hechos. Mascota en fase ${s.phase}, nivel ${s.level}.")
            appendLine("Hábitos:")
            s.items.forEach { h ->
                append("- ${h.name}: ${if (h.isDone) "hecho" else "pendiente"}")
                if (h.isKeyDay && !h.isDone) append(" (DÍA CLAVE: ayer no se hizo)")
                if (h.anchor.isNotBlank()) append(", después de ${h.anchor}")
                appendLine()
            }
            appendLine("Tareas:")
            if (s.tasks.isEmpty()) appendLine("- ninguna")
            s.tasks.forEach { t ->
                append("- ${t.title}: ${if (t.isDone) "hecha" else "pendiente"}")
                t.minuteOfDay?.let { append(" a las %02d:%02d".format(it / 60, it % 60)) }
                if (t.isOverdue) append(" (atrasada)")
                appendLine()
            }
        }
    }

    override fun readingSummary(): String = onMain {
        val all = books.findAll()
        val today = getToday()
        val stats = ReadingStats.compute(all, books.findSessionsSince(today.startOfYear()), today)
        buildString {
            appendLine("Minutos leídos esta semana (lun-dom): ${stats.weekMinutes.joinToString()}. Este año: ${stats.yearMinutes} min, ${stats.booksFinishedThisYear} libros terminados.")
            all.filter { it.status == BookStatus.READING }.forEach {
                appendLine("Leyendo: ${it.title} de ${it.author}, ${(it.progress * 100).toInt()} %.")
            }
            val want = all.filter { it.status == BookStatus.WANT }
            if (want.isNotEmpty()) appendLine("Quiere leer: ${want.joinToString { it.title }}.")
        }
    }
}
