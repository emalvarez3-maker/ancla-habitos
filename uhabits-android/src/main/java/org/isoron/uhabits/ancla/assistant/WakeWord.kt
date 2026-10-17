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
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.text.Normalizer

/**
 * Listens for "Oye Dexter" while the app is open, using Android's speech recognizer in a loop.
 * Calls [onWake] with whatever was said after the name ("oye Dexter, anota comprar pan"),
 * or an empty string when the user only called it.
 */
class WakeWord(private val context: Context, private val onWake: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var heardName = false
    private var failures = 0

    val isActive get() = active

    fun start() {
        if (active || !SpeechRecognizer.isRecognitionAvailable(context)) return
        active = true
        failures = 0
        listen()
    }

    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        heardName = false
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Dictation.SPEECH_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Dictation.SPEECH_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                // A pause after "Oye Dexter" means the user is waiting for us.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            }
        )
    }

    private fun restart(delay: Long) {
        if (!active) return
        main.postDelayed({ listen() }, delay)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            failures = 0
        }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (!heardName && find(text) != null) {
                heardName = true
                Log.d(TAG, "wake word heard: $text")
            }
        }

        override fun onResults(results: Bundle?) {
            val options = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val match = options.firstNotNullOfOrNull { find(it) }
            if (match != null) {
                Log.d(TAG, "wake: «${options.first()}» → «$match»")
                stop()
                onWake(match)
            } else {
                restart(150)
            }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(150)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> stop()
                else -> {
                    // Busy, network or server hiccups: back off a little more each time.
                    failures++
                    recognizer?.destroy()
                    recognizer = null
                    Log.d(TAG, "wake recognizer error $error")
                    restart((1000L * failures).coerceAtMost(15_000L))
                }
            }
        }
    }

    companion object {
        private const val TAG = "AnclaVoice"

        /** Ways the recognizer may spell "Dexter" in Spanish. */
        private val NAME = Regex("\\b(dexter|dexte|dextre|dexster|dekster|deckster|dextor|texter|dexta)\\b")

        /** Lowercase without accents, keeping one character per character so indexes still match. */
        private fun normalize(s: String) = String(s.lowercase().map { Normalizer.normalize(it.toString(), Normalizer.Form.NFD).first() }.toCharArray())

        /** Returns what follows the name if [text] calls Dexter, else null. */
        fun find(text: String): String? {
            val clean = normalize(text)
            val m = NAME.find(clean) ?: return null
            return text.substring(m.range.last + 1).trim().trimStart(',', '.', ' ').trim()
        }
    }
}
