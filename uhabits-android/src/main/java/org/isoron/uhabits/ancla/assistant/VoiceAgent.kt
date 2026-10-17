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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

data class AgentTurn(val heard: String, val reply: String, val actions: List<String>)

/** Connects dictation, the Claude agent and the UI state for one screen. */
class VoiceAgentController(private val activity: ComponentActivity, private val onChanged: () -> Unit = {}) {
    val keys = SecureKeys(activity)
    val dictation = mutableStateOf<DictationState>(DictationState.Idle)
    val thinking = mutableStateOf(false)
    val lastTurn = mutableStateOf<AgentTurn?>(null)
    val error = mutableStateOf<String?>(null)
    val turns = mutableStateOf<List<AgentTurn>>(emptyList())

    /** The reply being written right now, while it streams and is spoken. */
    val liveReply = mutableStateOf("")
    val speaking = mutableStateOf(false)
    val conversation = mutableStateOf(keys.conversationMode)
    val speak = mutableStateOf(keys.speakReplies)

    private var agent: AssistantAgent? = null
    private var agentKey = ""
    private var pendingMode: DictationMode? = null
    private var turnGeneration = 0
    private var resumed = true

    private val speaker = Speaker(activity, { keys.cloudVoice }, { keys.phoneVoice }) { speaking.value = it }

    /** The phone's Spanish voices, for the fallback voice picker. */
    fun phoneVoices() = speaker.phoneVoices()

    fun previewPhoneVoice(name: String) {
        cancel()
        speaker.previewPhone(activity.getString(R.string.voice_sample), name)
    }

    /** Plays a short sample of a voice from the settings sheet. */
    fun previewVoice(voiceName: String, geminiKey: String) {
        cancel()
        speaker.preview(activity.getString(R.string.voice_sample), geminiKey, voiceName)
    }

    private val engine = Dictation(
        activity,
        keys,
        onState = { dictation.value = it },
        onTranscript = { send(it) },
        onError = { error.value = it },
        onAudio = { sendAudio(it) },
        onSilence = { /* no speech in a Jarvis turn: the loop just stops */ }
    )

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingMode
        pendingMode = null
        if (granted && mode != null) engine.start(mode) else if (!granted) error.value = activity.getString(R.string.voice_need_mic)
        if (granted && keys.alwaysListening && DexterService.instance == null) DexterService.start(activity)
    }

    // --- "Oye Dexter" ---

    val wakeWord = mutableStateOf(keys.wakeWord)
    private val handler = Handler(Looper.getMainLooper())

    private val wake = WakeWord(activity) { rest ->
        activity.window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        // "Oye Dexter, anota comprar pan" goes straight in; a bare "Oye Dexter" opens the mic.
        if (rest.split(" ").count { it.isNotBlank() } >= 2) send(rest) else handler.postDelayed({ begin(DictationMode.CONVERSATION) }, 250)
    }

    private val micGranted
        get() = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Keeps the wake word listening only while nothing else uses the microphone or the speaker. */
    private val wakeCheck = object : Runnable {
        override fun run() {
            val idle = dictation.value == DictationState.Idle && !thinking.value && !speaking.value
            if (resumed && wakeWord.value && idle && micGranted && hasAssistantKey) wake.start() else wake.stop()
            if (resumed) handler.postDelayed(this, WAKE_CHECK_MS)
        }
    }

    /** "Oye Dexter" with the app closed, through [DexterService]. */
    val alwaysListening = mutableStateOf(keys.alwaysListening)

    fun setAlwaysListening(on: Boolean) {
        alwaysListening.value = on
        keys.alwaysListening = on
        if (on) {
            if (micGranted) DexterService.start(activity) else permission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            DexterService.stop(activity)
        }
    }

    fun setWakeWord(on: Boolean) {
        wakeWord.value = on
        keys.wakeWord = on
        if (on && !micGranted) permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val hasAssistantKey get() = keys.usableAssistant != null

    private fun begin(mode: DictationMode) {
        error.value = null
        speaker.stop() // talking over Dexter interrupts it
        wake.stop()
        if (!resumed) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            engine.start(mode)
        } else {
            pendingMode = mode
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun holdStart() = begin(DictationMode.HOLD)
    fun holdEnd() = engine.stop()

    fun doubleTap() {
        if (engine.isActive) engine.stop() else begin(DictationMode.HANDS_FREE)
    }

    fun singleTap() {
        val s = dictation.value
        when {
            s is DictationState.Listening && s.mode != DictationMode.HOLD -> engine.stop()
            speaking.value -> speaker.stop()
            conversation.value -> begin(DictationMode.CONVERSATION)
            else -> error.value = activity.getString(R.string.voice_hint)
        }
    }

    fun cancel() {
        turnGeneration++
        engine.cancel()
        speaker.stop()
        thinking.value = false
    }

    fun setConversation(on: Boolean) {
        conversation.value = on
        keys.conversationMode = on
    }

    fun setSpeak(on: Boolean) {
        speak.value = on
        keys.speakReplies = on
        if (!on) speaker.stop()
    }

    fun onResume() {
        resumed = true
        // The app takes the microphone while it is open; the service waits.
        DexterService.appInForeground = true
        DexterService.instance?.pause()
        if (keys.alwaysListening && micGranted && DexterService.instance == null) DexterService.start(activity)
        handler.removeCallbacks(wakeCheck)
        handler.postDelayed(wakeCheck, WAKE_CHECK_MS)
    }

    fun onPause() {
        resumed = false
        DexterService.appInForeground = false
        DexterService.instance?.resume()
        handler.removeCallbacks(wakeCheck)
        wake.stop()
        cancel()
    }

    fun dismiss() {
        lastTurn.value = null
        error.value = null
    }

    private fun currentAgent(): AssistantAgent? {
        val (provider, key) = keys.usableAssistant ?: run {
            error.value = activity.getString(R.string.voice_need_key)
            return null
        }
        if (agent == null || "$provider:$key" != agentKey) {
            agent = when (provider) {
                AssistantProvider.CLAUDE -> ClaudeAgent(key, AnclaActions(activity))
                AssistantProvider.GEMINI -> GeminiAgent(key, AnclaActions(activity))
            }
            agentKey = "$provider:$key"
        }
        return agent
    }

    /** Sends typed or dictated text to the agent. */
    fun send(text: String) {
        if (text.isBlank()) return
        val current = currentAgent() ?: return
        runTurn(text) { onEvent -> current.send(text, onEvent) }
    }

    /** Sends recorded speech straight to Gemini (understands and acts in one call). */
    private fun sendAudio(file: java.io.File) {
        val current = currentAgent() as? GeminiAgent
        if (current == null) {
            file.delete()
            if (error.value == null) error.value = activity.getString(R.string.voice_failed)
            return
        }
        val audio = file.readBytes().also { file.delete() }
        runTurn(activity.getString(R.string.voice_message_label)) { onEvent -> current.sendAudio(audio, "audio/mp4", onEvent) }
    }

    /** Runs one assistant turn: streams the text on screen and speaks it as it arrives. */
    private fun runTurn(heard: String, call: ((AgentEvent) -> Unit) -> Unit) {
        val gen = ++turnGeneration
        val started = SystemClock.elapsedRealtime()
        thinking.value = true
        error.value = null
        liveReply.value = ""
        lastTurn.value = null
        if (speak.value) speaker.begin { onSpokenFully(gen) } else speaker.stop()
        Thread {
            val replies = StringBuilder()
            val actions = mutableListOf<String>()
            val failure = runCatching {
                call { event ->
                    when (event) {
                        is AgentEvent.Reply -> {
                            if (replies.isEmpty()) Log.d("AnclaVoice", "first words after ${SystemClock.elapsedRealtime() - started} ms")
                            replies.append(event.text)
                            activity.runOnUiThread {
                                if (gen != turnGeneration) return@runOnUiThread
                                liveReply.value = replies.toString()
                                if (speak.value) speaker.append(event.text)
                            }
                        }
                        is AgentEvent.Action -> actions.add(event.description)
                    }
                }
            }.exceptionOrNull()
            activity.runOnUiThread {
                if (gen != turnGeneration) return@runOnUiThread
                thinking.value = false
                liveReply.value = ""
                if (speak.value) speaker.finish() else onSpokenFully(gen)
                if (failure != null) {
                    error.value = activity.getString(
                        when ((failure as? AgentException)?.kind) {
                            AgentException.Kind.BAD_KEY -> R.string.voice_bad_key
                            AgentException.Kind.RATE_LIMIT -> R.string.voice_rate_limit
                            AgentException.Kind.NETWORK -> R.string.voice_network
                            AgentException.Kind.REFUSED -> R.string.voice_refused
                            else -> R.string.voice_failed
                        }
                    )
                }
                if (replies.isNotEmpty() || actions.isNotEmpty()) {
                    val turn = AgentTurn(heard, replies.toString().trim(), actions)
                    lastTurn.value = turn
                    turns.value = turns.value + turn
                }
                if (failure != null) speaker.stop()
                onChanged()
            }
        }.start()
    }

    /** Jarvis mode: once Brote has finished talking, listen for the next request. */
    private fun onSpokenFully(gen: Int) {
        if (gen != turnGeneration || !conversation.value || !resumed || error.value != null) return
        begin(DictationMode.CONVERSATION)
    }

    fun release() {
        handler.removeCallbacks(wakeCheck)
        wake.stop()
        engine.release()
        speaker.shutdown()
    }

    private companion object {
        const val WAKE_CHECK_MS = 700L
    }
}

/**
 * Microphone button: hold to talk (release to send), double tap for hands-free,
 * tap again to finish hands-free.
 */
@Composable
fun MicButton(controller: VoiceAgentController, modifier: Modifier = Modifier, size: Int = 60) {
    val c = Ancla.colors
    val state = controller.dictation.value
    val listening = state is DictationState.Listening
    val level = (state as? DictationState.Listening)?.level ?: 0f
    val pulse by animateFloatAsState(if (listening) 1f + level * 0.25f else 1f, label = "micPulse")
    val desc = stringResource(R.string.voice_mic_desc)
    Box(
        modifier
            .size(size.dp)
            .scale(pulse)
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(if (listening) c.mint else c.lilac)
            .semantics {
                contentDescription = desc
                role = Role.Button
            }
            .pointerInput(controller) {
                awaitEachGesture {
                    awaitFirstDown()
                    val quickUp = withTimeoutOrNull(HOLD_MS) { waitForUpOrCancellation() }
                    if (quickUp == null) {
                        controller.holdStart()
                        waitForUpOrCancellation()
                        controller.holdEnd()
                    } else {
                        val second = withTimeoutOrNull(DOUBLE_TAP_MS) { awaitFirstDown() }
                        if (second != null) {
                            waitForUpOrCancellation()
                            controller.doubleTap()
                        } else {
                            controller.singleTap()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size((size * 0.42f).dp)) {
            val w = this.size.width
            val h = this.size.height
            val white = Color.White
            drawRoundRect(white, Offset(w * 0.32f, 0f), Size(w * 0.36f, h * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.18f))
            drawArc(white, 0f, 180f, false, Offset(w * 0.14f, h * 0.22f), Size(w * 0.72f, h * 0.56f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f, cap = StrokeCap.Round))
            drawLine(white, Offset(w / 2, h * 0.78f), Offset(w / 2, h * 0.98f), w * 0.09f, StrokeCap.Round)
        }
    }
}

private const val HOLD_MS = 250L
private const val DOUBLE_TAP_MS = 300L

/** Floating pill shown while recording, transcribing or thinking. */
@Composable
fun DictationPill(controller: VoiceAgentController, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    val state = controller.dictation.value
    val thinking = controller.thinking.value
    val speaking = controller.speaking.value
    val visible = state != DictationState.Idle || thinking || speaking
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state is DictationState.Listening) {
        while (state is DictationState.Listening) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    AnimatedVisibility(visible, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut() + scaleOut(targetScale = 0.9f), modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(c.ink)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (state is DictationState.Listening) Color(0xFFFF6B5E) else c.lilac))
                val label = when {
                    state is DictationState.Listening && state.mode == DictationMode.HOLD -> stringResource(R.string.voice_pill_hold)
                    state is DictationState.Listening && state.mode == DictationMode.CONVERSATION -> stringResource(R.string.voice_pill_conversation)
                    state is DictationState.Listening -> {
                        val secs = ((now - state.startedAt) / 1000).coerceAtLeast(0)
                        stringResource(R.string.voice_pill_free, secs / 60, secs % 60)
                    }
                    state is DictationState.Transcribing -> stringResource(R.string.voice_pill_transcribing)
                    thinking -> stringResource(R.string.voice_pill_thinking)
                    else -> stringResource(R.string.voice_pill_speaking)
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = c.onInk, modifier = Modifier.weight(1f))
                if (state is DictationState.Listening) Waveform(state.level)
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(c.onInk.copy(alpha = 0.12f))
                        .combinedClickableCompat(Role.Button, { controller.cancel() }),
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = c.onInk, style = MaterialTheme.typography.labelLarge) }
            }
            val partial = (state as? DictationState.Listening)?.partial.orEmpty()
            if (partial.isNotBlank()) {
                Text(partial, style = MaterialTheme.typography.bodyMedium, color = c.onInk.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Waveform(level: Float) {
    val c = Ancla.colors
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f, 0.9f, 1f, 0.7f, 0.4f).forEachIndexed { i, k ->
            val h by animateFloatAsState(6f + 18f * (level * k).coerceIn(0f, 1f), label = "wave$i")
            Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(c.mint))
        }
    }
}

/** Shows the agent's last reply and what it did; tap to dismiss. */
@Composable
fun AgentResultCard(controller: VoiceAgentController, onOpenChat: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    val turn = controller.lastTurn.value
    val err = controller.error.value
    val live = controller.liveReply.value
    AnimatedVisibility(turn != null || err != null || live.isNotBlank(), enter = fadeIn() + scaleIn(initialScale = 0.95f), exit = fadeOut(), modifier = modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(16.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(c.card)
                .combinedClickableCompat(Role.Button, { controller.dismiss() })
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (err != null) {
                Text(err, style = MaterialTheme.typography.bodyLarge, color = c.amberInk)
            } else if (live.isNotBlank()) {
                Text(live.trim(), style = MaterialTheme.typography.bodyLarge, color = c.ink)
            } else if (turn != null) {
                Text("«${turn.heard}»", style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                turn.actions.forEach { a ->
                    Text("✓ $a", style = MaterialTheme.typography.titleMedium, color = c.mintInk)
                }
                if (turn.reply.isNotBlank()) Text(turn.reply, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.voice_open_chat), style = MaterialTheme.typography.labelLarge, color = c.lilacInk,
                    modifier = Modifier.combinedClickableCompat(Role.Button, onOpenChat).padding(vertical = 6.dp))
                Text(stringResource(R.string.voice_dismiss), style = MaterialTheme.typography.labelLarge, color = c.muted,
                    modifier = Modifier.combinedClickableCompat(Role.Button, { controller.dismiss() }).padding(vertical = 6.dp))
            }
        }
    }
}
