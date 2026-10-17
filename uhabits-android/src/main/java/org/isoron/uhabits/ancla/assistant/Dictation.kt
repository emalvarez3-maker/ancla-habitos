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
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** HOLD: while pressed. HANDS_FREE: until tapped again. CONVERSATION: Jarvis turn, ends when the user stops talking. */
enum class DictationMode { HOLD, HANDS_FREE, CONVERSATION }

sealed class DictationState {
    data object Idle : DictationState()
    data class Listening(val mode: DictationMode, val startedAt: Long, val partial: String, val level: Float) : DictationState()
    data object Transcribing : DictationState()
}

/**
 * Wispr-Flow-style dictation for Ancla: hold to talk or hands-free (double tap), a 5-minute
 * safety stop, and cancel at any point. Transcribes with Whisper (Groq/OpenAI, with the user's
 * key) or, without a key, with Android's speech recognizer.
 */
class Dictation(
    private val context: Context,
    private val keys: SecureKeys,
    private val onState: (DictationState) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** Gemini mode: the recorded audio goes to the assistant as is (the receiver deletes the file). */
    private val onAudio: (File) -> Unit = {},
    /** A conversation turn ended without speech: the Jarvis loop should stop quietly. */
    private val onSilence: () -> Unit = {},
    /** Send recorded audio to Gemini whenever Gemini is the assistant (the service, where Android's recognizer is unreliable). */
    private val preferGeminiAudio: Boolean = false
) {
    private val main = Handler(Looper.getMainLooper())
    private var state: DictationState = DictationState.Idle
        set(v) {
            field = v
            onState(v)
        }

    private var provider = TranscriptionProvider.ANDROID
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var recognizer: SpeechRecognizer? = null
    private val heard = StringBuilder()
    private var generation = 0
    private var upload: HttpURLConnection? = null

    val isActive get() = state != DictationState.Idle

    private val autoStop = Runnable { stop() }
    // Simple voice-activity detection for conversation turns on recorded audio.
    private var heardSpeech = false
    private var lastLoud = 0L

    private val meter = object : Runnable {
        override fun run() {
            val s = state as? DictationState.Listening ?: return
            recorder?.let { r ->
                val amplitude = r.maxAmplitude
                val level = (amplitude / 12000f).coerceIn(0f, 1f)
                state = s.copy(level = level)
                val now = System.currentTimeMillis()
                if (amplitude > SPEECH_AMPLITUDE) {
                    heardSpeech = true
                    lastLoud = now
                }
                if (s.mode == DictationMode.CONVERSATION) {
                    if (heardSpeech && now - lastLoud > END_SILENCE_MS) {
                        stop()
                        return
                    }
                    if (!heardSpeech && now - s.startedAt > NO_SPEECH_MS) {
                        cancel()
                        onSilence()
                        return
                    }
                }
            }
            main.postDelayed(this, 90)
        }
    }

    fun start(mode: DictationMode) {
        if (isActive) return
        provider = if (preferGeminiAudio && keys.usableAssistant?.first == AssistantProvider.GEMINI) TranscriptionProvider.GEMINI else keys.effectiveTranscription
        heard.clear()
        heardSpeech = false
        lastLoud = System.currentTimeMillis()
        generation++
        state = DictationState.Listening(mode, System.currentTimeMillis(), "", 0f)
        main.postDelayed(autoStop, MAX_MILLIS)
        try {
            if (provider == TranscriptionProvider.ANDROID) startRecognizer() else startRecorder()
        } catch (e: Exception) {
            reset()
            onError(e.message ?: "No se pudo usar el micrófono")
        }
    }

    /** Finishes listening and transcribes what was said. */
    fun stop() {
        val listening = state as? DictationState.Listening ?: return
        main.removeCallbacks(autoStop)
        main.removeCallbacks(meter)
        if (provider == TranscriptionProvider.ANDROID) {
            state = DictationState.Transcribing
            recognizer?.stopListening()
            // Results arrive in onResults; if the recognizer already ended, deliver what we have.
            main.postDelayed({ if (state == DictationState.Transcribing && recognizer == null) deliver(heard.toString()) }, 300)
        } else {
            val file = audioFile
            stopRecorder()
            if (file == null || System.currentTimeMillis() - listening.startedAt < 500) {
                reset()
                return
            }
            if (provider == TranscriptionProvider.GEMINI) {
                reset()
                onAudio(file)
                return
            }
            state = DictationState.Transcribing
            val gen = generation
            Thread {
                val result = runCatching { whisper(file) }
                file.delete()
                main.post {
                    if (gen != generation) return@post
                    result.onSuccess { deliver(it) }.onFailure {
                        reset()
                        onError(it.message ?: "No se pudo transcribir")
                    }
                }
            }.start()
        }
    }

    /** Drops the recording, or abandons a transcription already running. */
    fun cancel() {
        generation++
        main.removeCallbacks(autoStop)
        main.removeCallbacks(meter)
        upload?.disconnect()
        stopRecorder()
        audioFile?.delete()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        reset()
    }

    fun release() = cancel()

    private fun reset() {
        state = DictationState.Idle
    }

    private fun deliver(text: String) {
        recognizer?.destroy()
        recognizer = null
        reset()
        val clean = text.trim()
        if (clean.isNotEmpty()) onTranscript(clean) else onError("No escuché nada. Intenta de nuevo.")
    }

    // --- Whisper (Groq or OpenAI) ---

    private fun startRecorder() {
        val file = File(context.cacheDir, "dictation-${UUID.randomUUID()}.m4a")
        audioFile = file
        recorder = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        main.post(meter)
    }

    private fun stopRecorder() {
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
    }

    private fun whisper(file: File): String {
        val (url, key, model) = when (provider) {
            TranscriptionProvider.GROQ -> Triple("https://api.groq.com/openai/v1/audio/transcriptions", keys.groqKey, "whisper-large-v3-turbo")
            else -> Triple("https://api.openai.com/v1/audio/transcriptions", keys.openAiKey, "whisper-1")
        }
        val boundary = "----Ancla${UUID.randomUUID()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        upload = conn
        try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("model", model)
                field("language", "es")
                field("response_format", "json")
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401) error("La clave de transcripción no es válida")
            if (code !in 200..299) error("Error de transcripción ($code)")
            return JSONObject(body).optString("text")
        } finally {
            upload = null
            conn.disconnect()
        }
    }

    // --- Android speech recognizer (no key needed) ---

    private fun startRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) error("Este teléfono no tiene reconocimiento de voz")
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val s = state as? DictationState.Listening ?: return
                state = s.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                val s = state
                if (s is DictationState.Listening && s.mode == DictationMode.HANDS_FREE &&
                    (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    listenAgain()
                    return
                }
                if (s is DictationState.Listening && s.mode == DictationMode.CONVERSATION && heard.isEmpty()) {
                    recognizer?.destroy()
                    recognizer = null
                    reset()
                    onSilence()
                    return
                }
                recognizer?.destroy()
                recognizer = null
                if (s is DictationState.Transcribing || heard.isNotEmpty()) deliver(heard.toString()) else {
                    reset()
                    if (error != SpeechRecognizer.ERROR_CLIENT) onError("No escuché nada. Intenta de nuevo.")
                }
            }
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    if (heard.isNotEmpty()) heard.append(' ')
                    heard.append(it)
                }
                val s = state
                if (s is DictationState.Listening && s.mode == DictationMode.HANDS_FREE) {
                    state = s.copy(partial = heard.toString())
                    listenAgain()
                } else {
                    deliver(heard.toString())
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val s = state as? DictationState.Listening ?: return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                state = s.copy(partial = listOf(heard.toString(), partial).filter { it.isNotBlank() }.joinToString(" "))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(recognizerIntent())
    }

    private fun listenAgain() {
        recognizer?.startListening(recognizerIntent())
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // Always Spanish, even when the phone itself is set to English.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SPEECH_LANGUAGE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
    }

    companion object {
        /** Safety stop so a forgotten hands-free session cannot record all afternoon. */
        const val MAX_MILLIS = 5 * 60 * 1000L

        /** Language for Android's recognizer: Latin American Spanish. */
        const val SPEECH_LANGUAGE = "es-US"

        /** Microphone amplitude above which we consider the user is talking. */
        private const val SPEECH_AMPLITUDE = 2500

        /** Silence after speech that ends a conversation turn. */
        private const val END_SILENCE_MS = 850L

        /** A conversation turn with no speech at all ends the Jarvis loop. */
        private const val NO_SPEECH_MS = 7000L
    }
}
