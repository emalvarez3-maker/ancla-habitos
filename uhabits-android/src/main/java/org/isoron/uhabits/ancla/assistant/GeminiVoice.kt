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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The Gemini voices offered in settings; [PHONE] means Android's own text-to-speech. */
object BroteVoices {
    const val PHONE = "phone"
    val GEMINI = listOf("Charon", "Iapetus", "Sadaltager", "Algenib", "Orus", "Algieba", "Kore", "Sulafat")
}

/**
 * Natural speech from Gemini's text-to-speech model, streamed: segments are synthesized in order
 * on one thread while a second thread plays the PCM as it arrives, so the next segment is
 * being generated while the current one is still playing.
 */
internal class GeminiVoice(
    private val onAudioStart: (Int) -> Unit,
    private val onSegmentDone: (Int) -> Unit,
    private val onFailed: (Int, String) -> Unit
) {
    private sealed class Chunk(val gen: Int) {
        class Pcm(gen: Int, val data: ByteArray) : Chunk(gen)
        class End(gen: Int) : Chunk(gen)
    }

    private val fetcher = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<Chunk>()

    @Volatile private var generation = 0

    @Volatile private var running = true

    /** Playback speed, applied when a reply starts; the pitch stays the same. */
    @Volatile var speed = 1f
    private var useMetadata = true
    private var useNewVoiceShape = true

    private val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
        .build()

    private var framesWritten = 0L

    private val player = Thread {
        var startedGen = -1
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (chunk.gen != generation) continue
            when (chunk) {
                is Chunk.Pcm -> {
                    if (startedGen != chunk.gen) {
                        startedGen = chunk.gen
                        if (track.playbackParams.speed != speed) {
                            runCatching { track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f) }
                        }
                        onAudioStart(chunk.gen)
                    }
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                    // Small slices, so a reset never leaves this thread blocked on a paused track.
                    var offset = 0
                    while (offset < chunk.data.size && chunk.gen == generation) {
                        val n = track.write(chunk.data, offset, minOf(SLICE, chunk.data.size - offset))
                        if (n <= 0) break
                        offset += n
                        framesWritten += n / 2
                    }
                }
                is Chunk.End -> {
                    // Last queued segment: wait until the speaker has actually played it.
                    if (queue.isEmpty()) drain(chunk.gen)
                    if (chunk.gen == generation) onSegmentDone(chunk.gen)
                }
            }
        }
    }.apply {
        name = "brote-voice"
        start()
    }

    private fun drain(gen: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (gen == generation && queue.isEmpty() && System.currentTimeMillis() < deadline) {
            if ((track.playbackHeadPosition.toLong() and 0xffffffffL) >= framesWritten) break
            Thread.sleep(30)
        }
    }

    /** Starts a new reply, dropping anything still queued from the last one. */
    fun reset(): Int {
        generation++
        queue.clear()
        track.pause()
        track.flush()
        framesWritten = 0
        // playbackHeadPosition restarts at 0 after flush on a paused track.
        return generation
    }

    fun speak(gen: Int, apiKey: String, voice: String, text: String, style: String) {
        fetcher.execute {
            if (gen != generation) return@execute
            val started = System.currentTimeMillis()
            try {
                synthesize(gen, apiKey, voice, text, style)
                Log.d(TAG, "segment ${text.length} chars synthesized in ${System.currentTimeMillis() - started} ms")
                queue.put(Chunk.End(gen))
            } catch (e: Exception) {
                Log.w(TAG, "Gemini TTS failed: ${e.javaClass.simpleName}")
                if (gen == generation) onFailed(gen, text)
            }
        }
    }

    private fun body(voice: String, text: String, style: String): JSONObject {
        val part = JSONObject().put("text", text)
        if (useMetadata) part.put("speechMetadata", JSONObject().put("style", style))
        val voiceConfig = if (useNewVoiceShape) {
            JSONObject().put("voice", voice)
        } else {
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
        }
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part))))
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", voiceConfig))
            )
    }

    /** Per model, when it may be used again after hitting its quota (each TTS model has its own). */
    private val limitedUntil = HashMap<String, Long>()

    private fun nextModel(): String? {
        val now = System.currentTimeMillis()
        return MODELS.firstOrNull { (limitedUntil[it] ?: 0) <= now }
    }

    private fun synthesize(gen: Int, apiKey: String, voice: String, text: String, style: String) {
        // On a quota error the next TTS model is tried; older request shapes are tried if the API rejects newer fields.
        repeat(MODELS.size + 2) {
            val model = nextModel() ?: throw IllegalStateException("all TTS models rate limited")
            val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            var reusable = false
            try {
                val sent = System.currentTimeMillis()
                conn.outputStream.use { it.write(body(voice, text, style).toString().toByteArray()) }
                val code = conn.responseCode
                if (code == 429 || code == 404 || code >= 500) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val wait = when {
                        code == 404 -> Long.MAX_VALUE / 2
                        code >= 500 -> 15 * 1000L // overloaded for a moment
                        err.contains("PerDay") -> 6 * 60 * 60 * 1000L
                        else -> 60 * 1000L
                    }
                    limitedUntil[model] = System.currentTimeMillis() + wait
                    Log.w(TAG, "$model HTTP $code, trying another voice model") // no body: it can quote the user's text
                    return@repeat
                }
                if (code == 400) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "TTS HTTP 400")
                    when {
                        useMetadata -> useMetadata = false
                        useNewVoiceShape -> useNewVoiceShape = false
                        else -> throw IllegalStateException("HTTP 400")
                    }
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                var gotAudio = false
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (gen != generation) return
                        if (!line.startsWith("data:")) continue
                        val parts = JSONObject(line.removePrefix("data:").trim())
                            .optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                        for (i in 0 until parts.length()) {
                            val inline = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                            var pcm = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                            if (pcm.size > 44 && String(pcm, 0, 4) == "RIFF") pcm = pcm.copyOfRange(44, pcm.size)
                            if (pcm.isNotEmpty()) {
                                if (!gotAudio) Log.d(TAG, "first audio after ${System.currentTimeMillis() - sent} ms")
                                gotAudio = true
                                queue.put(Chunk.Pcm(gen, pcm))
                            }
                        }
                    }
                }
                if (!gotAudio) throw IllegalStateException("no audio")
                reusable = true
                return
            } finally {
                // Read to the end: the socket stays pooled for the next segment. Abandoned: close it.
                if (!reusable) conn.disconnect()
            }
        }
        throw IllegalStateException("TTS request rejected")
    }

    fun shutdown() {
        running = false
        generation++
        fetcher.shutdownNow()
        track.release()
    }

    companion object {
        private const val TAG = "AnclaVoice"
        /** Tried in order; each has its own free quota. */
        val MODELS = listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts")
        private const val SAMPLE_RATE = 24000
        private const val SLICE = 2400 // 50 ms of audio
        /** Dexter's delivery: a brilliant, sardonic doctor rather than a cheerful assistant. */
        const val ASSISTANT_STYLE = "Deep, elegant, beautifully modulated baritone male voice, sophisticated and composed, " +
            "like a refined AI butler with a dry, sardonic wit: expressive intonation that rises and falls naturally, " +
            "deadpan comic timing on the sarcastic lines, brisk and fluent pace, quicker than everyday conversation, " +
            "with short pauses. Never robotic, never flat, never cheerful-salesman."

        /** Audiobook narration. */
        const val NARRATOR_STYLE = "Audiobook narrator: warm, deep and expressive male voice, measured storytelling pace, " +
            "clear diction, subtle shifts of tone for dialogue and characters, natural pauses between sentences."
    }
}
