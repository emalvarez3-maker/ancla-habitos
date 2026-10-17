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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the assistant's reply as it streams in: text is buffered and complete sentences are
 * spoken right away, so Brote starts talking before the reply is finished. Uses a natural Gemini
 * voice when [cloudVoice] returns a key and voice name, and Android's text-to-speech otherwise
 * or whenever Gemini fails (offline, quota).
 */
class Speaker(
    context: Context,
    private val cloudVoice: () -> Pair<String, String>? = { null },
    private val phoneVoice: () -> String = { "" },
    private val onSpeaking: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val buffer = StringBuilder()
    private val pending = AtomicInteger(0)
    private var finished = false
    private var onDone: (() -> Unit)? = null
    private var counter = 0

    /** Gemini key and voice for the current reply, or null to use the phone's voice. */
    private var cloud: Pair<String, String>? = null
    private var cloudGen = 0
    private val cloudText = StringBuilder()
    private var cloudPausedUntil = 0L

    /** How the Gemini voice should deliver the text. */
    var style = GeminiVoice.ASSISTANT_STYLE

    private val voice = GeminiVoice(
        onAudioStart = { gen -> main.post { if (gen == cloudGen) onSpeaking(true) } },
        onSegmentDone = { gen -> main.post { if (gen == cloudGen) utteranceEnded() } },
        onFailed = { gen, text ->
            main.post {
                if (gen != cloudGen) return@post
                // Quota or network trouble: finish this reply (and the next 20 seconds) with the phone's voice.
                cloud = null
                cloudPausedUntil = System.currentTimeMillis() + 20_000
                speakLocal(text)
                utteranceEnded()
            }
        }
    )

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            configure()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { onSpeaking(true) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { utteranceEnded() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { utteranceEnded() }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post { utteranceEnded() }
            }
        })
    }

    private fun configure() {
        tts.language = Locale("es", "US")
        applyPhoneVoice(phoneVoice())
        tts.setSpeechRate(1.0f)
        tts.setPitch(0.95f)
    }

    /** The phone's Spanish voices, by name, for the fallback voice picker. */
    fun phoneVoices(): List<String> = if (!ready) emptyList() else
        tts.voices.orEmpty()
            .filter { it.locale.language == "es" && Voice.QUALITY_NORMAL <= it.quality && "legacySetLanguageVoice" !in it.features.orEmpty() }
            .map { it.name }.sorted()

    /** Uses the voice named [name], or the default pick: a male es-US voice ("esd") when there is one. */
    private fun applyPhoneVoice(name: String) {
        if (!ready) return
        val voices = tts.voices.orEmpty().filter { it.locale.language == "es" && "legacySetLanguageVoice" !in it.features.orEmpty() }
        val pick = voices.firstOrNull { it.name == name }
            ?: voices.filter { it.locale.country == "US" && "esd" in it.name }.maxByOrNull { if (it.isNetworkConnectionRequired) 1 else 0 }
            ?: voices.maxByOrNull { (if (it.locale.country == "US") 100 else 0) + it.quality / 100 }
        pick?.let { tts.voice = it }
    }

    /** Starts a new reply; [onFinished] runs after the last sentence has been spoken. */
    fun begin(onFinished: (() -> Unit)? = null) {
        stop()
        applyPhoneVoice(phoneVoice())
        finished = false
        onDone = onFinished
        cloud = if (System.currentTimeMillis() < cloudPausedUntil) null else cloudVoice()
    }

    /** Adds streamed text; complete sentences are spoken immediately. */
    fun append(delta: String) {
        buffer.append(delta)
        while (true) {
            val text = buffer.toString()
            val end = Regex("[.!?¡¿…:;\\n](\\s|$)").find(text)?.range?.last
                // A long first sentence is cut at a comma so the voice starts sooner.
                ?: Regex(",\\s").findAll(text).map { it.range.last }.firstOrNull { it > 50 }
                    ?.takeIf { pending.get() == 0 && cloudText.isEmpty() }
                ?: break
            val sentence = text.substring(0, end + 1).trim()
            buffer.delete(0, end + 1)
            speak(sentence)
        }
    }

    /** No more text is coming: speaks whatever is left. */
    fun finish() {
        val rest = buffer.toString().trim()
        buffer.clear()
        if (rest.isNotEmpty()) speak(rest)
        flushCloud()
        finished = true
        if (pending.get() == 0) complete()
    }

    fun stop() {
        buffer.clear()
        cloudText.clear()
        pending.set(0)
        onDone = null
        finished = false
        cloudGen = voice.reset()
        if (ready) tts.stop()
        onSpeaking(false)
    }

    /** Says a short sample with the given voice, for the settings screen. */
    fun preview(text: String, key: String, voiceName: String) {
        stop()
        cloudPausedUntil = 0
        cloud = if (voiceName == BroteVoices.PHONE || key.isBlank()) null else key to voiceName
        applyPhoneVoice(phoneVoice())
        speak(text)
        flushCloud()
        finished = true
    }

    /** Says a short sample with one of the phone's voices. */
    fun previewPhone(text: String, name: String) {
        stop()
        cloud = null
        applyPhoneVoice(name)
        speak(text)
        finished = true
    }

    fun shutdown() {
        stop()
        voice.shutdown()
        tts.shutdown()
    }

    private fun speak(sentence: String) {
        val clean = sentence.replace(Regex("[*_#`>]"), "").trim()
        if (clean.isEmpty()) return
        if (cloud == null) {
            speakLocal(clean)
            return
        }
        // Short replies go out as one request when the text is complete: the whole reply gets one
        // natural intonation and the free quota lasts longer. Long replies are sent in parts.
        cloudText.append(clean).append(' ')
        if (cloudText.length >= GROUP_CHARS) flushCloud()
    }

    private fun flushCloud() {
        val (key, name) = cloud ?: return
        val text = cloudText.toString().trim()
        cloudText.clear()
        if (text.isEmpty()) return
        pending.incrementAndGet()
        voice.speak(cloudGen, key, name, text, style)
    }

    private fun speakLocal(text: String) {
        if (!ready) return
        pending.incrementAndGet()
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "brote-${counter++}")
    }

    private fun utteranceEnded() {
        if (pending.get() > 0 && pending.decrementAndGet() > 0) return
        if (finished) complete()
    }

    private fun complete() {
        onSpeaking(false)
        val done = onDone
        onDone = null
        finished = false
        done?.invoke()
    }

    companion object {
        private const val GROUP_CHARS = 280
    }
}
