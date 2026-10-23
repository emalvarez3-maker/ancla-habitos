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

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.DexterOwl
import org.isoron.uhabits.ancla.ui.OwlState
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import org.isoron.uhabits.core.database.TaskRepository

/**
 * A reminder that can't be missed: it turns the screen on, even locked, and rings like an alarm
 * clock (the alarm sound, looping, with vibration) until the user says "Hecho" or "En 10 min".
 * On a locked phone it only says that there is a reminder: what it is shows once the phone is
 * unlocked, so nobody reads the user's tasks off their lock screen.
 */
class ReminderAlarmActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val title = mutableStateOf("")
    private val locked = mutableStateOf(false)
    private var taskId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(intent)
        setContent { AnclaTheme { Screen() } }
        ring()
        // An alarm nobody answers stops after two minutes; the notification stays.
        main.postDelayed({ quiet(); finish() }, RING_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        load(intent)
    }

    override fun onResume() {
        super.onResume()
        locked.value = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        quiet()
        super.onDestroy()
    }

    private fun load(intent: Intent) {
        taskId = intent.getLongExtra(EXTRA_TASK, -1L)
        Thread {
            val repo = TaskRepository((applicationContext as HabitsApplication).component.db)
            val task = runCatching { repo.findById(taskId) }.getOrNull()
            main.post { title.value = task?.title.orEmpty() }
        }.start()
    }

    private fun ring() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@ReminderAlarmActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        }.getOrNull()
        runCatching {
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400, 700, 400), 0))
        }
    }

    private fun quiet() {
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        runCatching { getSystemService(Vibrator::class.java).cancel() }
    }

    private fun done() {
        quiet()
        val id = taskId
        Thread { runCatching { TaskReminders.markDone(applicationContext, id) } }.start()
        finish()
    }

    private fun snooze() {
        quiet()
        TaskReminders.snooze(this, taskId, SNOOZE_MINUTES)
        finish()
    }

    /** On a locked phone, "Mostrar" asks for the fingerprint (or PIN) before showing the task. */
    private fun reveal() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                locked.value = false
            }
        })
    }

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val c = Ancla.colors
        Column(
            Modifier.fillMaxSize().background(c.ground).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically)
        ) {
            DexterOwl(OwlState.SPEAKING, Modifier.size(170.dp))
            Text(stringResource(R.string.alarm_caption), style = MaterialTheme.typography.labelLarge, color = c.amber)
            Text(
                if (locked.value) stringResource(R.string.alarm_locked) else title.value.ifBlank { stringResource(R.string.alarm_caption) },
                style = MaterialTheme.typography.headlineMedium, color = c.ink, textAlign = TextAlign.Center
            )
            if (locked.value) Button(stringResource(R.string.alarm_show), c.card, c.ink) { reveal() }
            Button(stringResource(R.string.alarm_done), c.amber, c.onInk) { done() }
            Button(stringResource(R.string.alarm_snooze, SNOOZE_MINUTES), c.card, c.ink) { snooze() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Button(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
        Text(
            label, style = MaterialTheme.typography.titleMedium, color = fg, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(bg).combinedClickableCompat(Role.Button, onClick).padding(vertical = 18.dp)
        )
    }

    companion object {
        private const val EXTRA_TASK = "task"
        private const val RING_MS = 2 * 60_000L
        const val SNOOZE_MINUTES = 10

        fun intent(context: Context, taskId: Long): Intent =
            Intent(context, ReminderAlarmActivity::class.java).putExtra(EXTRA_TASK, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
