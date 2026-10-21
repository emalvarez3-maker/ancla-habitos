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
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.isoron.uhabits.ancla.ui.OwlState
import org.json.JSONObject
import org.vosk.Model
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
    private var wakeListener: DexterWake? = null

    /** Dexter on top of other apps while you talk to it, like Siri (when allowed to draw there). */
    private val overlay: DexterOverlay by lazy {
        DexterOverlay(this, object : DexterOverlay.Controls {
            override fun close() {
                turn++
                speaker.stop()
                endConversation()
                overlay.hide()
            }

            override fun setQuiet(on: Boolean) {
                keys.quietMode = on
                overlay.quiet(on)
                // Silence takes effect at once; the answer keeps arriving as text on the card.
                if (on) silenceNow()
            }

            override fun pauseListening() = pause(byUser = true)
        })
    }

    /** Quiet mode with the card on screen: answers are shown, not spoken. */
    private val textOnly get() = keys.quietMode && overlay.allowed

    /** Stops the voice mid-answer; the turn then goes back to listening when its text is complete. */
    private fun silenceNow() {
        speaker.stop()
    }

    private var paused = false
    private var inConversation = false
    private var agent: AssistantAgent? = null
    private var agentKey = ""
    private var lastReply = ""
    private var turn = 0

    /** Talking over Dexter cuts it off and starts the next request, as in the app. */
    private val interrupter: BargeIn = BargeIn {
        if (!inConversation) return@BargeIn
        turn++
        speaker.stop()
        listenForRequest()
    }

    private val speaker: Speaker by lazy {
        Speaker(this, { keys.cloudVoice }, { keys.phoneVoice }) { speaking ->
            if (speaking) overlay.show(OwlState.SPEAKING)
            val micGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (speaking && inConversation && !paused && keys.bargeIn && micGranted && !dictation.isActive) interrupter.start() else interrupter.stop()
        }
    }

    private val dictation: Dictation by lazy {
        Dictation(
            this,
            keys,
            onState = { s ->
                when (s) {
                    is DictationState.Listening -> {
                        update(getString(R.string.dexter_service_listening))
                        overlay.show(OwlState.LISTENING, youSaid = s.partial.takeIf { it.isNotBlank() })
                        overlay.level(s.level)
                    }
                    DictationState.Transcribing -> overlay.show(OwlState.THINKING)
                    DictationState.Idle -> Unit
                }
            },
            onTranscript = { text ->
                if (text.isBlank()) {
                    endConversation()
                } else {
                    overlay.show(OwlState.THINKING, youSaid = text)
                    runTurn { a, onEvent -> a.send(text, onEvent) }
                }
            },
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

    /**
     * The screen going off drops Dexter's last answer from the notification, which the lock screen
     * shows in full when the user lets it show notification content.
     */
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastReply = ""
            if (!inConversation) update(getString(if (keys.listeningPaused) R.string.dexter_service_paused else R.string.dexter_service_waiting))
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        ContextCompat.registerReceiver(this, screenOff, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        DexterTile.update(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            keys.alwaysListening = false
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(getString(R.string.dexter_service_starting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        when (intent?.action) {
            ACTION_PAUSE -> pause(byUser = true)
            ACTION_RESUME -> resumeListening()
            ACTION_MIC -> {
                // Started again while Dexter's invisible screen is on top: the microphone is ours again.
                Log.d(TAG, "microphone handed back")
                micLost = false
                unregisterUnlock()
                if (!inConversation) {
                    stopWake()
                    startWake()
                }
            }
        }
        if (model == null) loadModel() else if (keys.listeningPaused) update(getString(R.string.dexter_service_paused))
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        interrupter.stop()
        stopWake()
        unregisterUnlock()
        runCatching { unregisterReceiver(screenOff) }
        stopRequestListener()
        overlay.hide()
        dictation.release()
        speaker.shutdown()
        model?.close()
        super.onDestroy()
        DexterTile.update(this)
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
        if (keys.listeningPaused) {
            update(getString(R.string.dexter_service_paused))
            // The pause ends by itself: listen again then.
            main.removeCallbacks(pauseEnd)
            main.postDelayed(pauseEnd, (keys.listeningPausedUntil - System.currentTimeMillis()).coerceAtLeast(1000) + 500)
            return
        }
        // The app being open no longer stops it: "Oye Dexter" works on every tab, with the card. [paused]
        // is set only while the app itself uses the microphone (its dictation, or Dexter's chat screen).
        if (paused || inConversation || wakeListener != null) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val listener = DexterWake(
            m,
            onRequest = { wav -> main.post { wakeEnded(); onRequestWithName(wav) } },
            onWakeOnly = { main.post { wakeEnded(); onWake() } },
            onSilenced = {
                Log.w(TAG, "microphone silenced by Android")
                main.post { regainMicrophone() }
            }
        )
        wakeListener = listener
        listener.start()
        update(getString(R.string.dexter_service_waiting))
        // If the microphone was busy (a call, another app) the listener ends at once: try again later.
        main.postDelayed(wakeWatchdog, 5000)
    }

    /** Restarts the wake listener if it died on its own (microphone taken by a call or another app). */
    private val wakeWatchdog = object : Runnable {
        override fun run() {
            val listener = wakeListener ?: return
            if (!listener.alive && !inConversation) {
                Log.w(TAG, "wake listener ended, restarting")
                wakeListener = null
                startWake()
            } else {
                main.postDelayed(this, 5000)
            }
        }
    }

    // --- Getting the microphone back after a background start ---

    private var micLost = false
    private var unlockReceiver: android.content.BroadcastReceiver? = null

    /**
     * Android silenced the microphone (the service was started in the background). Dexter's
     * invisible screen gets it back: now if the phone is in use, or right after it's unlocked.
     */
    private fun regainMicrophone() {
        if (micLost) return
        micLost = true
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked) {
            openMicScreen()
        } else {
            update(getString(R.string.dexter_service_silenced))
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    unregisterUnlock()
                    openMicScreen()
                }
            }
            unlockReceiver = receiver
            ContextCompat.registerReceiver(this, receiver, android.content.IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun openMicScreen() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            // Without "Display over other apps" Android won't let it open from the background.
            Log.w(TAG, "can't reclaim the microphone: no overlay permission")
            update(getString(R.string.dexter_service_silenced))
            micLost = false
            return
        }
        Log.d(TAG, "reclaiming the microphone")
        runCatching {
            startActivity(Intent(this, DexterMicActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }.onFailure {
            Log.w(TAG, "mic screen refused: ${it.javaClass.simpleName}")
            micLost = false
        }
    }

    private fun unregisterUnlock() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
    }

    /** The listener finished by itself after hearing the name; it already released the microphone. */
    private fun wakeEnded() {
        main.removeCallbacks(wakeWatchdog)
        wakeListener = null
    }

    private fun stopWake() {
        main.removeCallbacks(wakeWatchdog)
        wakeListener?.stop()
        wakeListener = null
    }

    private fun canStart(): Boolean {
        if (inConversation || paused) return false
        if (keys.usableAssistant == null) {
            update(getString(R.string.voice_need_key))
            main.postDelayed({ startWake() }, 3000)
            return false
        }
        return true
    }

    /** "Oye Dexter" and a pause: a tone, then Dexter listens for the request. */
    private fun onWake() {
        if (!canStart()) return
        Log.d(TAG, "Oye Dexter (service)")
        inConversation = true
        overlay.clear()
        overlay.quiet(keys.quietMode)
        overlay.show(OwlState.LISTENING)
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 160) }
        // What comes after the tone may be a TV after a false wake: the AI checks it was for Dexter.
        checkNextRequest = true
        main.postDelayed({ listenForRequest() }, 300)
    }

    /** "Oye Dexter, pon una alarma..." in one breath: the recording goes straight to Dexter. */
    private fun onRequestWithName(wav: ByteArray) {
        if (!canStart()) return
        val gemini = keys.geminiKey
        if (keys.usableAssistant?.first != AssistantProvider.GEMINI && gemini.isBlank()) {
            // Claude with no Gemini key: nothing here can transcribe the recording, so ask again.
            onWake()
            return
        }
        Log.d(TAG, "Oye Dexter with the request (service)")
        inConversation = true
        overlay.clear()
        overlay.quiet(keys.quietMode)
        handleRequest(wav, afterWake = true)
    }

    /**
     * A recorded request: an everyday command ("prende la linterna") runs at once, understood on the
     * phone by Vosk; anything else goes to the AI. [afterWake]: recorded with the name, so the AI
     * first checks that someone really called Dexter.
     */
    private fun handleRequest(wav: ByteArray, afterWake: Boolean, nameInAudio: Boolean = true) {
        if (!inConversation) return
        val m = model
        val token = turn
        if (!afterWake) overlay.show(OwlState.THINKING)
        Thread {
            val started = System.currentTimeMillis()
            val heard = m?.let { runCatching { DexterWake.transcribe(it, wav) }.getOrNull() }.orEmpty()
            val quick = QuickCommands.match(heard)
            Log.d(TAG, "offline: ${if (quick != null) quick.tool else "no quick command"} in ${System.currentTimeMillis() - started} ms")
            main.post {
                if (token != turn || !inConversation) return@post
                if (quick != null) runQuick(heard, quick) else sendRecording(wav, afterWake, nameInAudio)
            }
        }.start()
    }

    private fun sendRecording(wav: ByteArray, afterWake: Boolean, nameInAudio: Boolean = true) {
        val gemini = keys.geminiKey
        runTurn(verifyFirst = afterWake) { a, onEvent ->
            if (a is GeminiAgent) {
                a.sendAudio(wav, "audio/wav", onEvent, afterWake = afterWake, nameInAudio = nameInAudio)
            } else {
                val text = GeminiText.transcribe(gemini, wav, "audio/wav")
                // The recording starts at the name: without it in the words, nobody called Dexter
                // (a TV, a song), and its words must not act or confirm anything.
                if (afterWake && nameInAudio && !PhoneSafety.callsDexter(text)) {
                    onEvent(AgentEvent.Reply(PhoneSafety.IGNORE))
                    return@runTurn
                }
                overlay.show(OwlState.THINKING, youSaid = text)
                a.send(text, onEvent)
            }
        }
    }

    /** Runs an everyday command without the AI, says what it did and keeps listening. */
    private fun runQuick(heard: String, command: QuickCommand) {
        val gen = ++turn
        overlay.show(OwlState.THINKING, youSaid = heard, answer = "")
        Thread {
            val actions = AnclaActions(this)
            val result = runCatching { actions.phoneTool(command.tool, JSONObject(command.args)) }
                .getOrElse { it.message ?: getString(R.string.voice_failed) }
            // The AI sees it next time, like everything else said to Dexter.
            actions.conversation()?.let { log -> runCatching { log.add(true, heard); log.add(false, result) } }
            main.post {
                if (gen != turn) return@post
                lastReply = result
                update(result)
                overlay.show(OwlState.SPEAKING, answer = result)
                if (textOnly) {
                    listenForRequest()
                    return@post
                }
                speaker.begin { if (gen == turn) listenForRequest() }
                speaker.append(result)
                speaker.finish()
            }
        }.start()
    }

    // --- Conversation without the app ---

    /** Records the next request with the fast listener when Gemini can understand it, else with dictation. */
    private var requestListener: DexterWake? = null

    /** The next recorded request follows a bare "Oye Dexter": the AI first checks it was meant for Dexter. */
    private var checkNextRequest = false

    private fun stopRequestListener() {
        requestListener?.stop()
        requestListener = null
    }

    private fun listenForRequest() {
        if (!inConversation) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            endConversation()
            return
        }
        interrupter.stop() // frees the microphone
        overlay.show(OwlState.LISTENING, youSaid = "")
        val m = model
        val geminiHears = keys.usableAssistant?.first == AssistantProvider.GEMINI || keys.geminiKey.isNotBlank()
        if (m == null || !geminiHears) {
            dictation.start(DictationMode.CONVERSATION)
            return
        }
        stopRequestListener()
        update(getString(R.string.dexter_service_listening))
        val listener = DexterWake(
            m,
            onRequest = { wav ->
                main.post {
                    requestListener = null
                    val check = checkNextRequest
                    checkNextRequest = false
                    handleRequest(wav, afterWake = check, nameInAudio = false)
                }
            },
            onWakeOnly = { main.post { requestListener = null; checkNextRequest = false; if (inConversation) endConversation() } },
            onLevel = { overlay.level(it) },
            onSilenced = { main.post { regainMicrophone() } },
            listenOnly = true
        )
        requestListener = listener
        listener.start()
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

    /** [verifyFirst]: a recording started by the wake word; the card waits until it's known to be for Dexter. */
    private fun runTurn(verifyFirst: Boolean = false, call: (AssistantAgent, (AgentEvent) -> Unit) -> Unit) {
        // With the phone locked the speaker may not be its owner: start from a blank conversation,
        // so nothing said earlier can be asked back, and forget this one afterwards.
        val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked) agent = null
        val a = currentAgent() ?: return endConversation()
        if (locked) agent = null
        val gen = ++turn
        update(getString(R.string.voice_pill_thinking))
        if (!verifyFirst) overlay.show(OwlState.THINKING, answer = "")
        speaker.begin { if (gen == turn) listenForRequest() } // keep the conversation going, like the app's Jarvis mode
        Thread {
            val reply = StringBuilder()
            // The first characters wait until they can't be the "nobody called me" answer, so a
            // false wake never says or shows anything.
            var released = false
            var shown = !verifyFirst
            fun out(text: String) = main.post {
                if (gen != turn) return@post
                if (!shown) {
                    shown = true
                    overlay.show(OwlState.THINKING, answer = "")
                }
                if (!textOnly) speaker.append(text)
                overlay.appendReply(text)
            }
            val failure = runCatching {
                call(a) { event ->
                    if (event is AgentEvent.Reply) {
                        reply.append(event.text)
                        if (released) {
                            out(event.text)
                        } else if (!PhoneSafety.IGNORE.startsWith(reply.toString().trim())) {
                            released = true
                            out(reply.toString())
                        }
                    }
                }
            }.exceptionOrNull()
            val ignored = failure == null && reply.toString().trim() == PhoneSafety.IGNORE
            if (failure == null && !released && !ignored) out(reply.toString())
            main.post {
                if (gen != turn) return@post
                if (ignored) {
                    Log.d(TAG, "false wake: nobody was talking to Dexter")
                    turn++
                    speaker.stop()
                    overlay.hide()
                    endConversation(getString(R.string.dexter_service_waiting))
                    return@post
                }
                lastReply = reply.toString().trim()
                if (failure != null) {
                    Log.w(TAG, "service turn failed: ${failure.javaClass.simpleName}: ${failure.message?.take(120)}")
                    speaker.stop()
                    overlay.show(OwlState.IDLE, answer = getString(R.string.voice_failed))
                    endConversation(getString(R.string.voice_failed))
                } else {
                    update(lastReply.ifBlank { getString(R.string.dexter_service_waiting) })
                    if (textOnly) {
                        // Nothing was spoken (or the voice was cut): go straight back to listening.
                        speaker.stop()
                        listenForRequest()
                    } else {
                        speaker.finish()
                    }
                }
            }
        }.start()
    }

    /** A reminder said out loud with the service's voice; skipped while the user is talking to Dexter. */
    fun announce(text: String) {
        if (inConversation) return
        stopWake() // the wake word would hear Dexter's own voice
        val gen = ++turn
        overlay.clear()
        overlay.quiet(keys.quietMode)
        overlay.show(OwlState.SPEAKING, answer = text)
        speaker.begin {
            if (gen != turn || inConversation) return@begin
            overlay.hideSoon()
            startWake()
        }
        speaker.append(text)
        speaker.finish()
    }

    private fun endConversation(message: String? = null) {
        inConversation = false
        interrupter.stop()
        turn++
        dictation.cancel()
        stopRequestListener()
        overlay.hideSoon()
        update(message ?: lastReply.ifBlank { getString(R.string.dexter_service_waiting) })
        main.postDelayed({ startWake() }, 400)
    }

    // --- Pausing while the app itself uses the microphone ---

    /**
     * Stops listening. [byUser]: the user paused "Oye Dexter" (card, tile or notification), and it
     * stays paused until they resume it; otherwise the app took the microphone for a moment.
     */
    fun pause(byUser: Boolean = false) {
        if (byUser) {
            keys.listeningPaused = true
            overlay.hide()
        } else {
            paused = true
        }
        if (inConversation) {
            turn++
            speaker.stop()
            endConversation()
        }
        stopRequestListener()
        stopWake()
        if (byUser) {
            update(getString(R.string.dexter_service_paused))
            DexterTile.update(this)
        }
    }

    fun resume() {
        paused = false
        startWake()
    }

    private val pauseEnd = Runnable {
        DexterTile.update(this)
        startWake()
    }

    /** "Oye Dexter" listens again after the user paused it. */
    fun resumeListening() {
        main.removeCallbacks(pauseEnd)
        keys.listeningPaused = false
        DexterTile.update(this)
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
        val pausedByUser = keys.listeningPaused
        val toggle = PendingIntent.getService(
            this, 2, Intent(this, DexterService::class.java).setAction(if (pausedByUser) ACTION_RESUME else ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE
        )
        // Dexter's last answer can be personal (money, goals, messages): the lock screen only says it is there.
        val public = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dexter_service_title))
            .setSilent(true)
            .build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.dexter_service_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(if (pausedByUser) R.string.dexter_service_resume else R.string.overlay_pause), toggle)
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
        private const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_MIC = "mic"
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
