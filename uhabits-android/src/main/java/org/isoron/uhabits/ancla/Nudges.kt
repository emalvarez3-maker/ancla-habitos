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

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.database.TaskRepository
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import java.time.LocalDateTime
import java.time.ZoneId

/** When in the day Dexter checks in. */
enum class NudgeSlot(val hour: Int, val minute: Int) {
    MORNING(9, 30), MIDDAY(13, 30), EVENING(18, 30), NIGHT(21, 15)
}

/** A habit still to do today, with what Dexter needs to say about it. */
data class PendingHabit(val id: Long, val name: String, val anchor: String, val tiny: String, val keyDay: Boolean, val streak: Int)

/** A to-do still open: today's or one left from an earlier day. */
data class PendingTask(val title: String, val minute: Int?, val overdue: Boolean)

data class Nudge(val title: String, val text: String, val habitId: Long?)

/**
 * What Dexter writes, Duolingo-style but toward awareness: it names the habit, the plan the user
 * made for it ("después de…"), what's at stake (a key day, a streak), the two-minute way out, and a
 * question that makes them notice what they're doing right now. Pure, so it is tested on its own.
 */
object NudgeWriter {
    /** Short questions that bring attention back to the present (mindfulness, not guilt). */
    val QUESTIONS = listOf(
        "¿Qué estás haciendo justo ahora, y lo elegiste tú?",
        "¿Qué sientes en el cuerpo en este momento?",
        "¿Qué estás evitando con lo que haces ahora?",
        "Si te vieras desde afuera, ¿qué verías?",
        "¿Esto que haces ahora te acerca a quien quieres ser?",
        "Tres respiraciones lentas antes de seguir. ¿Qué notas?",
        "¿Estás en piloto automático o aquí presente?",
        "¿Qué historia te estás contando para no hacerlo?"
    )

    private fun pick(list: List<String>, seed: Int) = list[Math.floorMod(seed, list.size)]

    private fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun tasksLine(tasks: List<PendingTask>): String? {
        if (tasks.isEmpty()) return null
        val overdue = tasks.filter { it.overdue }
        val shown = tasks.sortedWith(compareBy<PendingTask>({ !it.overdue }, { it.minute ?: Int.MAX_VALUE })).take(3)
            .joinToString(", ") { t -> t.title + (t.minute?.let { " (${hhmm(it)})" } ?: "") + if (t.overdue) " (atrasada)" else "" }
        val more = if (tasks.size > 3) " y ${tasks.size - 3} más" else ""
        return (if (overdue.isNotEmpty()) "Pendientes, ${overdue.size} atrasada${if (overdue.size > 1) "s" else ""}: " else "Pendientes: ") + shown + more + "."
    }

    /** The message for [slot], or null when there is nothing worth interrupting the user for. */
    fun write(slot: NudgeSlot, habits: List<PendingHabit>, tasks: List<PendingTask>, doneHabits: Int, totalHabits: Int, seed: Int): Nudge? {
        val question = pick(QUESTIONS, seed + slot.ordinal)
        val taskLine = tasksLine(tasks)
        // The one that matters most now: a key day first (twice in a row is what breaks a habit), then the longest streak.
        val first = habits.sortedWith(compareByDescending<PendingHabit> { it.keyDay }.thenByDescending { it.streak }).firstOrNull()
        if (first == null) {
            if (slot != NudgeSlot.NIGHT && slot != NudgeSlot.MORNING && taskLine == null) return null
            return when {
                slot == NudgeSlot.NIGHT && totalHabits > 0 -> Nudge(
                    "Hoy cumpliste tus hábitos ($doneHabits/$totalHabits)",
                    "Nota cómo se siente haberlo hecho: eso es lo que los vuelve automáticos. " + (taskLine?.let { "$it " } ?: "") +
                        "¿Hacemos el examen de la noche? ¿Dónde reaccionaste hoy en vez de elegir?",
                    null
                )
                taskLine != null -> Nudge("Dexter: lo que tienes pendiente", "$taskLine $question", null)
                slot == NudgeSlot.MORNING -> Nudge("Buenos días", "Hoy no tienes nada pendiente. ¿Qué es lo único que haría que hoy valga la pena? $question", null)
                else -> null
            }
        }
        val plan = if (first.anchor.isNotBlank()) "Tu plan: después de ${first.anchor}." else "Aún no decides cuándo: ¿después de qué lo harás?"
        val tiny = if (first.tiny.isNotBlank()) "Versión mínima, que también cuenta: ${first.tiny}." else "Aunque sea 2 minutos: empezar es lo que cuenta."
        val others = habits.size - 1
        val rest = if (others > 0) " (y $others hábito${if (others > 1) "s" else ""} más)" else ""
        val text = when {
            first.keyDay -> pick(
                listOf(
                    "Ayer no hiciste ${first.name}. Un día no rompe un hábito; dos seguidos sí. $tiny",
                    "Hoy es día clave para ${first.name}: ayer se saltó. $plan $tiny"
                ), seed
            )
            slot == NudgeSlot.MORNING -> pick(
                listOf(
                    "Hoy te espera ${first.name}$rest. $plan Decidir cuándo duplica las probabilidades de hacerlo.",
                    "Buenos días. ${first.name}$rest te espera hoy. $plan"
                ), seed
            )
            first.streak >= 3 -> pick(
                listOf(
                    "Llevas ${first.streak} días seguidos con ${first.name}. No la dejes morir hoy. $tiny",
                    "${first.streak} días de racha en ${first.name}. Ya casi es parte de ti. $plan"
                ), seed
            )
            slot == NudgeSlot.NIGHT -> "Última llamada para ${first.name}$rest. $tiny"
            else -> pick(
                listOf(
                    "${first.name} sigue pendiente$rest. $plan",
                    "Oye, ${first.name}$rest. No me hagas fingir que no lo vi. $tiny",
                    "${first.name} te está esperando$rest. $tiny"
                ), seed
            )
        }
        val title = when {
            first.keyDay -> "Día clave: ${first.name}"
            slot == NudgeSlot.NIGHT -> "Antes de dormir: ${first.name}"
            else -> "Dexter: ${first.name}"
        }
        return Nudge(title, listOfNotNull(text, taskLine, question).joinToString(" "), first.id)
    }

    /** Days in a row before today with the habit done ([done] tells for each day back). */
    fun streak(done: (daysBack: Int) -> Boolean, max: Int = 365): Int {
        var n = 0
        while (n < max && done(n + 1)) n++
        return n
    }
}

/**
 * Dexter's check-ins through the day: four alarms (morning plan, midday, evening, last call), each
 * one a notification only when something is pending, with "Hecho" and "En 1 hora" buttons. Its text
 * is hidden on the lock screen.
 */
object Nudges {
    private const val TAG = "Nudges"
    private const val CHANNEL = "DEXTER_NUDGES"
    private const val ACTION_NUDGE = "ec.ancla.habitos.ACTION_NUDGE"
    const val ACTION_DONE = "ec.ancla.habitos.ACTION_NUDGE_DONE"
    const val ACTION_SNOOZE = "ec.ancla.habitos.ACTION_NUDGE_SNOOZE"
    private const val EXTRA_SLOT = "slot"
    private const val EXTRA_HABIT = "habit"
    private const val NOTIFICATION = 0x4E55
    private const val SNOOZE_MS = 60 * 60_000L

    private fun alarm(context: Context, slot: NudgeSlot, code: Int = 0x4E00) = PendingIntent.getBroadcast(
        context, code, Intent(context, NudgeReceiver::class.java).setAction(ACTION_NUDGE).putExtra(EXTRA_SLOT, slot.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The next check-in after now. Idempotent: one alarm, moved to the next slot each time. */
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        val (slot, at) = NudgeSlot.entries.map { it to now.toLocalDate().atTime(it.hour, it.minute) }.firstOrNull { it.second.isAfter(now) }
            ?: (NudgeSlot.MORNING to now.toLocalDate().plusDays(1).atTime(NudgeSlot.MORNING.hour, NudgeSlot.MORNING.minute))
        set(context, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm(context, slot))
    }

    private fun set(context: Context, millis: Long, pi: PendingIntent) = runCatching {
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
    }.onFailure { Log.w(TAG, "could not schedule: ${it.javaClass.simpleName}") }

    private fun isDone(h: Habit, daysBack: Int): Boolean {
        val day = getToday().minus(daysBack)
        val v = h.computedEntries.get(day).value
        return if (h.isNumerical) v > 0 else v == Entry.YES_MANUAL || v == Entry.YES_AUTO
    }

    fun deliver(context: Context, slot: NudgeSlot) {
        val component = (context.applicationContext as HabitsApplication).component
        val today = getToday()
        val summary = TodaySummary.build(component.habitList, today, TaskRepository(component.db).findForToday(today))
        val habits = summary.items.filter { !it.isDone }.map { item ->
            val h = component.habitList.getById(item.habitId)
            PendingHabit(item.habitId, item.name, item.anchor, item.tinyVersion, item.isKeyDay, h?.let { NudgeWriter.streak({ d -> isDone(it, d) }) } ?: 0)
        }
        val tasks = summary.tasks.filter { !it.isDone }.map { PendingTask(it.title, it.minuteOfDay, it.isOverdue) }
        val nudge = NudgeWriter.write(slot, habits, tasks, summary.items.count { it.isDone }, summary.items.size, (today.daysSince2000 * 7 + slot.ordinal)) ?: return
        show(context, nudge)
    }

    private fun show(context: Context, nudge: Nudge) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.nudge_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0x4E56, Intent(context, TodayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val hidden = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.nudge_public)).build()
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nudge.title)
            .setContentText(nudge.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // Habits and tasks are personal: on the lock screen only "Dexter tiene algo para ti".
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
        nudge.habitId?.let { id ->
            val done = PendingIntent.getBroadcast(
                context, 0x4E57, Intent(context, NudgeReceiver::class.java).setAction(ACTION_DONE).putExtra(EXTRA_HABIT, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(0, context.getString(R.string.nudge_done), done)
        }
        val snooze = PendingIntent.getBroadcast(
            context, 0x4E58, Intent(context, NudgeReceiver::class.java).setAction(ACTION_SNOOZE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        b.addAction(0, context.getString(R.string.nudge_snooze), snooze)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION, b.build()) }
    }

    /** "Hecho" on the notification: marks the habit done today, like a tap in Hoy. */
    fun markDone(context: Context, habitId: Long) {
        val component = (context.applicationContext as HabitsApplication).component
        val habit = component.habitList.getById(habitId) ?: return
        val value = if (habit.isNumerical) (habit.targetValue / habit.frequency.denominator * 1000).toInt().coerceAtLeast(1000) else Entry.YES_MANUAL
        component.commandRunner.run(CreateRepetitionCommand(component.habitList, habit, getToday(), value, ""))
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
    }

    /** "En 1 hora": the same check-in again later (as the midday one: it doesn't replan the day). */
    fun snooze(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION)
        set(context, System.currentTimeMillis() + SNOOZE_MS, alarm(context, NudgeSlot.MIDDAY, code = 0x4E01))
    }

    fun slotOf(intent: Intent) = intent.getStringExtra(EXTRA_SLOT)?.let { s -> runCatching { NudgeSlot.valueOf(s) }.getOrNull() }

    fun habitOf(intent: Intent) = intent.getLongExtra(EXTRA_HABIT, -1L).takeIf { it >= 0 }
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Nudges.ACTION_DONE -> Nudges.habitOf(intent)?.let { runCatching { Nudges.markDone(context, it) } }
            Nudges.ACTION_SNOOZE -> Nudges.snooze(context)
            else -> {
                runCatching { Nudges.slotOf(intent)?.let { Nudges.deliver(context, it) } }.onFailure { Log.w("Nudges", "deliver failed: ${it.javaClass.simpleName}") }
                // A snoozed check-in is a one-off; the day's schedule goes on either way.
                Nudges.schedule(context)
            }
        }
    }
}
