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
 * Speaks the assistant's reply as it streams in: text is buffered and each complete sentence
 * is queued on Android's text-to-speech, so Brote starts talking before the reply is finished.
 */
class Speaker(context: Context, private val onSpeaking: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val buffer = StringBuilder()
    private val pending = AtomicInteger(0)
    private var finished = false
    private var onDone: (() -> Unit)? = null
    private var counter = 0

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
        val locale = Locale.getDefault().takeIf { it.language == "es" } ?: Locale("es", "US")
        tts.language = locale
        // Prefer a high-quality, network-free neural voice in Spanish when the phone has one.
        tts.voices?.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired && Voice.QUALITY_HIGH <= it.quality }
            ?.maxByOrNull { (if (it.locale.country == locale.country) 10 else 0) + it.quality / 100 }
            ?.let { tts.voice = it }
        tts.setSpeechRate(1.07f)
        tts.setPitch(0.95f)
    }

    /** Starts a new reply; [onFinished] runs after the last sentence has been spoken. */
    fun begin(onFinished: (() -> Unit)? = null) {
        stop()
        finished = false
        onDone = onFinished
    }

    /** Adds streamed text; complete sentences are spoken immediately. */
    fun append(delta: String) {
        buffer.append(delta)
        while (true) {
            val text = buffer.toString()
            val end = Regex("[.!?¡¿…:;\\n](\\s|$)").find(text)?.range?.last ?: break
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
        finished = true
        if (pending.get() == 0) complete()
    }

    fun stop() {
        buffer.clear()
        pending.set(0)
        onDone = null
        finished = false
        if (ready) tts.stop()
        onSpeaking(false)
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speak(sentence: String) {
        val clean = sentence.replace(Regex("[*_#`>]"), "").trim()
        if (clean.isEmpty() || !ready) return
        pending.incrementAndGet()
        tts.speak(clean, TextToSpeech.QUEUE_ADD, null, "brote-${counter++}")
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
}
