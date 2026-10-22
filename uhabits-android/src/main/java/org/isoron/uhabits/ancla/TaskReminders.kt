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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.DexterAnnouncer
import org.isoron.uhabits.core.database.TaskRepository
import org.isoron.uhabits.core.models.DayTask
import java.time.ZoneId
import java.time.ZonedDateTime

/** Schedules and shows notifications for Ancla's day tasks. */
object TaskReminders {
    /** Reminders ring like an alarm (alarm sound and volume, heard on vibrate too). */
    const val CHANNEL_ID = "ANCLA_TASK_ALARMS"

    /** The old channel, with a normal notification sound that vibrate mode silenced. */
    private const val OLD_CHANNEL_ID = "ANCLA_TASKS"
    const val ACTION_SHOW = "ec.ancla.habitos.ACTION_SHOW_TASK"
    const val ACTION_DONE = "ec.ancla.habitos.ACTION_TASK_DONE"
    const val EXTRA_TASK_ID = "taskId"
    private const val TAG = "TaskReminders"

    fun triggerMillis(task: DayTask): Long? {
        val minute = task.minuteOfDay ?: return null
        val d = task.day
        return ZonedDateTime.of(d.year, d.month, d.day, minute / 60, minute % 60, 0, 0, ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }

    private fun pending(context: Context, action: String, taskId: Long): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val code = (taskId % Int.MAX_VALUE).toInt() * 2 + if (action == ACTION_DONE) 1 else 0
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Schedules [task]'s reminder, or cancels it when it no longer needs one. True when one is scheduled. */
    fun sync(context: Context, task: DayTask): Boolean {
        val id = task.id ?: return false
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, ACTION_SHOW, id)
        val at = triggerMillis(task)
        if (!task.remind || task.isDone || at == null || at <= System.currentTimeMillis()) {
            alarms.cancel(pi)
            return false
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            // An alarm clock, like the Clock app's: the one kind Android never delays or silences to
            // save battery, and it shows the ⏰ in the status bar.
            val show = PendingIntent.getActivity(
                context, notificationId(id), Intent(context, TodayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
            )
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.d(TAG, "scheduled task=$id at=$at exact=$exact")
        return true
    }

    /** Whether a reminder will actually be seen and heard: notifications allowed, channel not muted. */
    fun canNotify(context: Context): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(context: Context, taskId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, ACTION_SHOW, taskId))
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    fun rescheduleAll(context: Context, repo: TaskRepository) {
        repo.findUpcomingReminders(getToday()).forEach { sync(context, it) }
    }

    private fun notificationId(taskId: Long) = 0x7A5C0000 + (taskId % 0xFFFF).toInt()

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.task_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            // Do Not Disturb lets alarms through by default; this asks for the same.
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
    }

    fun show(context: Context, task: DayTask) {
        val id = task.id ?: return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            notificationId(id),
            Intent(context, TodayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = task.notes.ifBlank { context.getString(R.string.task_notification_text) }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            // With the screen off, it lights the screen up like an alarm instead of waiting to be seen.
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .addAction(0, context.getString(R.string.task_action_done), pending(context, ACTION_DONE, id))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(id), notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification permission missing", e)
        }
        // And Dexter says it out loud, unless it is in quiet mode or the phone is silenced.
        DexterAnnouncer.announce(context, DexterAnnouncer.taskText(task.title))
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("TaskReminderReceiver", "received ${intent.action} task=${intent.getLongExtra(TaskReminders.EXTRA_TASK_ID, -1)}")
        val component = (context.applicationContext as HabitsApplication).component
        val repo = TaskRepository(component.db)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> TaskReminders.rescheduleAll(context, repo)
            TaskReminders.ACTION_SHOW -> {
                val task = repo.findById(intent.getLongExtra(TaskReminders.EXTRA_TASK_ID, -1)) ?: return
                if (!task.isDone) TaskReminders.show(context, task)
            }
            TaskReminders.ACTION_DONE -> {
                val id = intent.getLongExtra(TaskReminders.EXTRA_TASK_ID, -1)
                val task = repo.findById(id) ?: return
                repo.update(task.copy(isDone = true))
                TaskReminders.cancel(context, id)
                component.widgetUpdater.updateWidgets()
            }
        }
    }
}
