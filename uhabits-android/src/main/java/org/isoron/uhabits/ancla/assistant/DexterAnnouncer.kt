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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R

/**
 * Dexter speaking on its own: when a task or habit reminder fires, it says it out loud, with the
 * app open or closed, unless the user put Dexter in quiet mode, the phone is on silent, vibrate or
 * Do Not Disturb, or a call is going on. The notification is shown either way.
 */
object DexterAnnouncer {
    private const val TAG = "AnclaVoice"

    /** Why Dexter keeps quiet right now, or null when it may speak. For logs and Dexter's answers. */
    fun quietReason(context: Context): String? {
        if (SecureKeys(context).quietMode) return "modo silencio de Dexter"
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return "teléfono en silencio o vibración"
        if (audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION) return "llamada en curso"
        val filter = context.getSystemService(NotificationManager::class.java).currentInterruptionFilter
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return "No molestar"
        return null
    }

    /** Says [text] out loud if Dexter may speak now; does nothing otherwise. */
    fun announce(context: Context, text: String) {
        val reason = quietReason(context)
        if (reason != null) {
            Log.d(TAG, "reminder not spoken: $reason")
            return
        }
        // "Oye Dexter" is running: it already has a warm voice and the microphone etiquette.
        DexterService.instance?.let {
            it.announce(text)
            return
        }
        val intent = Intent(context, AnnouncerService::class.java).putExtra(AnnouncerService.EXTRA_TEXT, text)
        try {
            // Reminder alarms are exact, which lets the app start this service from the background.
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.w(TAG, "announcer service refused: ${e.javaClass.simpleName}")
        }
    }

    fun taskText(title: String) = "Te recuerdo: $title."

    fun habitText(name: String) = "Es hora de: $name."
}

/** Plays a short chime and speaks one reminder, then stops. Lives a few seconds at most. */
class AnnouncerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val keys by lazy { SecureKeys(this) }
    private val queue = ArrayDeque<String>()
    private var speaking = false
    private var focus: AudioFocusRequest? = null

    private val speaker by lazy { Speaker(this, { keys.cloudVoice }, { keys.phoneVoice }) { } }

    private val timeout = Runnable { finish() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { queue.addLast(it) }
        next()
        return START_NOT_STICKY
    }

    private fun next() {
        if (speaking) return
        val text = queue.removeFirstOrNull() ?: return finish()
        speaking = true
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, MAX_MS)
        val audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
            .also { audio.requestAudioFocus(it) }
        // A short chime first, so the voice doesn't start mid-word on a silent room.
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            main.postDelayed({ tone.release() }, 400)
        }
        main.postDelayed({
            speaker.begin {
                speaking = false
                next()
            }
            speaker.append(text)
            speaker.finish()
        }, 350)
    }

    private fun finish() {
        main.removeCallbacksAndMessages(null)
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        speaker.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.dexter_announce_channel), NotificationManager.IMPORTANCE_MIN))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dexter_announce_speaking))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "ANCLA_DEXTER_SPEAKS"
        private const val NOTIFICATION_ID = 0x7A5D0001
        /** Safety stop if a voice never reports that it finished. */
        private const val MAX_MS = 30_000L
    }
}
