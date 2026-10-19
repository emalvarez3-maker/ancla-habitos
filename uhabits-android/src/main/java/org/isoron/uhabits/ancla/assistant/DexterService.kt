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
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "Oye Dexter" everywhere: a microphone foreground service that keeps listening for the name
 * with Vosk (offline, nothing leaves the phone until the name is heard), then holds a spoken
 * conversation with Dexter without opening the app, showing the replies in its notification.
 */
class DexterService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val keys by lazy { SecureKeys(this) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    private var model: Model? = null
    private var wakeListener: SpeechService? = null
    private var paused = false
    private var inConversation = false
    private var agent: AssistantAgent? = null
    private var agentKey = ""
    private var lastReply = ""
    private var turn = 0

    /** Talking over Dexter cuts it off and starts the next request, as in the app. */
    private val interrupter = BargeIn {
        if (!inConversation) return@BargeIn
        turn++
        speaker.stop()
        listenForRequest()
    }

    private val speaker by lazy {
        Speaker(this, { keys.cloudVoice }, { keys.phoneVoice }) { speaking ->
            val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (speaking && inConversation && !paused && keys.bargeIn && micGranted && !dictation.isActive) interrupter.start() else interrupter.stop()
        }
    }

    private val dictation by lazy {
        Dictation(
            this,
            keys,
            onState = { s -> if (s is DictationState.Listening) update(getString(R.string.dexter_service_listening)) },
            onTranscript = { text -> if (text.isBlank()) endConversation() else runTurn { a, onEvent -> a.send(text, onEvent) } },
            onError = { endConversation() },
            onAudio = { file ->
                val audio = file.readBytes().also { file.delete() }
                runTurn { a, onEvent -> (a as GeminiAgent).sendAudio(audio, "audio/mp4", onEvent) }
            },
            onSilence = { endConversation() },
            preferGeminiAudio = true
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            keys.alwaysListening = false
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.dexter_service_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (model == null) loadModel()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        interrupter.stop()
        stopWake()
        dictation.release()
        speaker.shutdown()
        model?.close()
        super.onDestroy()
    }

    // --- Wake word (Vosk) ---

    private fun loadModel() {
        Thread {
            try {
                val dir = ensureModel(this) { percent -> update(getString(R.string.dexter_service_downloading, percent)) }
                val loaded = Model(dir.absolutePath)
                main.post {
                    model = loaded
                    startWake()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Vosk model failed: ${e.message}")
                main.post { update(getString(R.string.dexter_service_model_failed)) }
            }
        }.start()
    }

    private fun startWake() {
        val m = model ?: return
        if (paused || appInForeground || inConversation || wakeListener != null) return
        try {
            val recognizer = Recognizer(m, SAMPLE_RATE, "[\"oye dexter\", \"hey dexter\", \"ey dexter\", \"dexter\", \"[unk]\"]")
            wakeListener = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(wakeCallbacks) }
            update(getString(R.string.dexter_service_waiting))
        } catch (e: Exception) {
            Log.w(TAG, "wake start failed: ${e.message}")
            main.postDelayed({ startWake() }, 5000)
        }
    }

    private fun stopWake() {
        wakeListener?.let {
            it.stop()
            it.shutdown()
        }
        wakeListener = null
    }

    private val wakeCallbacks = object : RecognitionListener {
        private fun check(json: String?, key: String) {
            val text = runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")
            if ("dexter" in text) main.post { onWake() }
        }
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onError(exception: Exception?) {
            Log.w(TAG, "wake error: ${exception?.message}")
            main.post {
                stopWake()
                main.postDelayed({ startWake() }, 3000)
            }
        }
        override fun onTimeout() = Unit
    }

    private fun onWake() {
        if (inConversation || paused) return
        Log.d(TAG, "Oye Dexter (service)")
        stopWake()
        if (keys.usableAssistant == null) {
            update(getString(R.string.voice_need_key))
            main.postDelayed({ startWake() }, 3000)
            return
        }
        inConversation = true
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 160) }
        main.postDelayed({ listenForRequest() }, 300)
    }

    // --- Conversation without the app ---

    private fun listenForRequest() {
        if (!inConversation) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            endConversation()
            return
        }
        interrupter.stop() // frees the microphone
        dictation.start(DictationMode.CONVERSATION)
    }

    private fun currentAgent(): AssistantAgent? {
        val (provider, key) = keys.usableAssistant ?: return null
        if (agent == null || "$provider:$key" != agentKey) {
            agent = when (provider) {
                AssistantProvider.CLAUDE -> ClaudeAgent(key, AnclaActions(this))
                AssistantProvider.GEMINI -> GeminiAgent(key, AnclaActions(this))
            }
            agentKey = "$provider:$key"
        }
        return agent
    }

    private fun runTurn(call: (AssistantAgent, (AgentEvent) -> Unit) -> Unit) {
        // With the phone locked the speaker may not be its owner: start from a blank conversation,
        // so nothing said earlier can be asked back, and forget this one afterwards.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked) agent = null
        val a = currentAgent() ?: return endConversation()
        if (locked) agent = null
        val gen = ++turn
        update(getString(R.string.voice_pill_thinking))
        speaker.begin { if (gen == turn) listenForRequest() } // keep the conversation going, like the app's Jarvis mode
        Thread {
            val reply = StringBuilder()
            val failure = runCatching {
                call(a) { event ->
                    if (event is AgentEvent.Reply) {
                        reply.append(event.text)
                        main.post { if (gen == turn) speaker.append(event.text) }
                    }
                }
            }.exceptionOrNull()
            main.post {
                if (gen != turn) return@post
                lastReply = reply.toString().trim()
                if (failure != null) {
                    Log.w(TAG, "service turn failed: ${failure.javaClass.simpleName}")
                    speaker.stop()
                    endConversation(getString(R.string.voice_failed))
                } else {
                    update(lastReply.ifBlank { getString(R.string.dexter_service_waiting) })
                    speaker.finish()
                }
            }
        }.start()
    }

    private fun endConversation(message: String? = null) {
        inConversation = false
        interrupter.stop()
        turn++
        dictation.cancel()
        update(message ?: lastReply.ifBlank { getString(R.string.dexter_service_waiting) })
        main.postDelayed({ startWake() }, 400)
    }

    // --- Pausing while the app itself uses the microphone ---

    fun pause() {
        paused = true
        if (inConversation) {
            speaker.stop()
            endConversation()
        }
        stopWake()
    }

    fun resume() {
        paused = false
        startWake()
    }

    // --- Notification ---

    private fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.dexter_service_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, AssistantActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DexterService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dexter_service_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.dexter_service_stop), stop)
            .build()
    }

    private fun update(text: String) {
        if (instance == null) return
        notifications.notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        private const val TAG = "AnclaVoice"
        private const val CHANNEL = "ANCLA_DEXTER"
        private const val NOTIFICATION_ID = 7342
        private const val ACTION_STOP = "stop"
        private const val SAMPLE_RATE = 16000f
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"

        /** The running service, so the app can pause it while it uses the microphone itself. */
        @Volatile var instance: DexterService? = null
            private set

        /** True while Ancla itself is on screen and owns the microphone. */
        @Volatile var appInForeground = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DexterService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DexterService::class.java))
        }

        /** Downloads and unpacks the small Spanish Vosk model (~40 MB) once. Blocking. */
        fun ensureModel(context: Context, onProgress: (Int) -> Unit): File {
            val dir = File(context.filesDir, "vosk-es")
            val done = File(dir, ".complete")
            if (done.exists()) return dir
            dir.deleteRecursively()
            dir.mkdirs()
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 60000
            }
            try {
                val total = conn.contentLengthLong.coerceAtLeast(1)
                var read = 0L
                var lastPercent = -1
                val counting = object : java.io.FilterInputStream(conn.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            val p = (read * 100 / total).toInt()
                            if (p != lastPercent && p % 5 == 0) {
                                lastPercent = p
                                onProgress(p)
                            }
                        }
                        return n
                    }
                }
                ZipInputStream(counting.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // Drop the archive's top folder ("vosk-model-small-es-0.42/").
                        val name = entry.name.substringAfter('/', "")
                        if (name.isEmpty() || name.contains("..")) continue
                        val out = File(dir, name)
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            done.createNewFile()
            return dir
        }
    }
}
