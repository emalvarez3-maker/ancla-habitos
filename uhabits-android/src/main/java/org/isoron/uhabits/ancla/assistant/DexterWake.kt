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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Listens for "Oye Dexter" with Vosk (offline; nothing leaves the phone until the name is heard)
 * and, unlike a plain wake word, keeps hearing after it: "Oye Dexter, pon una alarma a las seis"
 * said in one breath arrives whole. The last seconds of audio are kept in a small ring buffer, so
 * the request is recorded from the name on, and ends after a short pause.
 *
 * [onRequest] gets the recording (16 kHz mono WAV, name included) when the user kept talking;
 * [onWakeOnly] runs when they said only the name and paused, so the caller asks what they need.
 * Both run on the listening thread, which has already stopped and released the microphone.
 *
 * With [listenOnly] there is no name to wait for: it records the next request right away (after
 * Dexter's tone, or after it answered) and [onWakeOnly] means nobody spoke.
 */
class DexterWake(
    private val model: Model,
    private val onRequest: (wav: ByteArray) -> Unit,
    private val onWakeOnly: () -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onSilenced: () -> Unit = {},
    /** What the user has said so far, live, while a request is being recorded. */
    private val onPartial: (String) -> Unit = {},
    private val listenOnly: Boolean = false
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** False once the listener ended by itself (microphone busy, or after the name was heard). */
    val alive get() = thread?.isAlive == true

    /** Android's id for this listener's recording while it runs, to tell it apart from other apps' recordings. */
    @Volatile var sessionId: Int? = null
        private set

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "dexter-wake").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) it.join(1000) }
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, FRAME * 4))
        sessionId = record.audioSessionId
        val recognizer = Recognizer(model, RATE.toFloat(), GRAMMAR).apply { setWords(true) }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // No microphone at all for a background service: same remedy as a silenced one.
                onSilenced()
                throw IllegalStateException("microphone busy")
            }
            record.startRecording()
            if (listenOnly) {
                // The room's noise from the first 200 ms, then straight to the request.
                val first = listOfNotNull(read(record), read(record))
                val noise = max(30.0, first.map { rms(it) }.average().takeIf { !it.isNaN() } ?: 60.0)
                if (first.isNotEmpty() && first.all { rms(it) == 0.0 }) onSilenced()
                val request = capture(record, first, noise, graceMs = 0, waitMs = LISTEN_WAIT_MS)
                val stopped = !running
                running = false
                record.stop()
                record.release()
                sessionId = null
                recognizer.close()
                if (stopped) return
                Log.d(TAG, if (request != null) "request heard: ${request.size * 100} ms" else "nobody spoke")
                if (request != null) onRequest(wav(request)) else onWakeOnly()
                return
            }
            val ring = ArrayDeque<ShortArray>()
            var noise = 300.0
            var zeros = 0
            var hits = 0
            while (running) {
                val frame = read(record) ?: break
                ring.addLast(frame)
                while (ring.size > RING_FRAMES) ring.removeFirst()
                val rms = rms(frame)
                onLevel((rms / 4000.0).coerceIn(0.0, 1.0).toFloat())
                // Android hands a silenced microphone over as pure zeros (another app, or a start it disallowed).
                zeros = if (rms == 0.0) zeros + 1 else 0
                if (zeros == SILENCED_FRAMES) onSilenced()
                // The room's noise, learned only from quiet frames.
                if (rms < noise * 2) noise = noise * 0.95 + rms * 0.05
                val final = recognizer.acceptWaveForm(frame, frame.size)
                val heardJson = if (final) recognizer.result else recognizer.partialResult
                val heard = JSONObject(heardJson).let { it.optString("text").ifEmpty { it.optString("partial") } }
                // Only the whole phrase counts ("oye/hey/ey dexter", never "dexter" alone), and only
                // once Vosk keeps it in two guesses in a row or a final result: a passing sound
                // that merely resembles the name is dropped.
                var nameConf = if (final) nameConfidence(heardJson) else null
                hits = if (WAKE_PHRASES.any { it in heard }) hits + 1 else 0
                if (hits < 2 && !(final && hits == 1)) continue

                // Someone calling Dexter talks toward the phone: the name must stand out of the room's
                // noise. A TV or voices across the room that merely resemble it don't.
                val peak = ring.takeLast(NAME_FRAMES).maxOf { rms(it) }
                if (!loudEnough(peak, noise)) {
                    Log.d(TAG, "wake word too quiet (peak ${peak.toInt()}, noise ${noise.toInt()}): ignored")
                    recognizer.reset()
                    hits = 0
                    continue
                }
                Log.d(TAG, "wake word heard")
                val request = capture(record, ring.toList(), noise) { f ->
                    if (recognizer.acceptWaveForm(f, f.size)) nameConf = maxOf(nameConf ?: 0.0, nameConfidence(recognizer.result) ?: 0.0)
                }
                if (running) nameConfidence(recognizer.finalResult)?.let { nameConf = maxOf(nameConf ?: 0.0, it) }
                // How sure Vosk is that the word was "dexter": a sound forced onto the name by the small
                // grammar comes back with a low confidence. Then it keeps listening, with no tone or card.
                if (running && !nameAccepted(nameConf)) {
                    Log.d(TAG, "wake word unsure (confidence ${"%.2f".format(nameConf)}): ignored")
                    recognizer.reset()
                    hits = 0
                    ring.clear()
                    continue
                }
                val stopped = !running // stop() was called meanwhile: nobody wants the result
                running = false
                record.stop()
                record.release()
                sessionId = null
                recognizer.close()
                if (stopped) return
                Log.d(TAG, (if (request != null) "request said with the name: ${request.size * 100} ms" else "name only") + ", confidence " + (nameConf?.let { "%.2f".format(it) } ?: "?"))
                if (request != null) onRequest(wav(request)) else onWakeOnly()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake listener stopped: ${e.message}")
        }
        runCatching { record.stop() }
        record.release()
        sessionId = null
        recognizer.close()
    }

    /**
     * Keeps recording after the name. Returns the audio from the buffered seconds to the end of the
     * request, or null when the user said only the name: no speech within [WAIT_MS].
     */
    private fun capture(
        record: AudioRecord,
        buffered: List<ShortArray>,
        noise: Double,
        graceMs: Long = GRACE_MS,
        waitMs: Long = WAIT_MS,
        feed: (ShortArray) -> Unit = {}
    ): List<ShortArray>? {
        val frames = buffered.toMutableList()
        val threshold = max(noise * 3.0, MIN_SPEECH_RMS)
        val start = System.currentTimeMillis()
        // The name itself may still be ending: speech counts as the request after a short grace.
        var spoke = false
        var speechFrames = 0
        var lastLoud = start
        var firstLoud = -1
        // What the user is saying, transcribed offline as they speak: shown live, and used to tell a
        // pause to think ("quiero que... eh...") from the end of what they wanted to say.
        val words = runCatching { Recognizer(model, RATE.toFloat()) }.getOrNull()
        val said = StringBuilder()
        var live = ""
        try {
            while (running) {
                val frame = read(record) ?: return if (spoke) frames else null
                frames += frame
                feed(frame)
                if (words != null) {
                    val text = if (words.acceptWaveForm(frame, frame.size)) {
                        said.append(' ').append(JSONObject(words.result).optString("text"))
                        said.toString()
                    } else {
                        said.toString() + " " + JSONObject(words.partialResult).optString("partial")
                    }.trim().replace(Regex("\\s+"), " ")
                    if (text != live) {
                        live = text
                        onPartial(text)
                    }
                }
                val now = System.currentTimeMillis()
                val loud = rms(frame) > threshold
                onLevel((rms(frame) / 4000.0).coerceIn(0.0, 1.0).toFloat())
                if (loud) {
                    lastLoud = now
                    if (firstLoud < 0) firstLoud = frames.size - 1
                    if (now - start > graceMs && ++speechFrames >= MIN_SPEECH_FRAMES) spoke = true
                }
                when {
                    !spoke && now - start > waitMs -> return null
                    spoke && now - lastLoud > endSilence(live) -> return trimmed(frames, firstLoud)
                    now - start > MAX_MS -> return if (spoke) trimmed(frames, firstLoud) else null
                }
            }
            return null
        } finally {
            words?.close()
        }
    }

    /**
     * After the tone, the wait before the user spoke is dropped (keeping 300 ms of lead-in). With the
     * name, nothing is dropped: the recording must still hold "Oye Dexter" for the check that someone
     * really called it.
     */
    private fun trimmed(frames: List<ShortArray>, firstLoud: Int) =
        if (!listenOnly || firstLoud < 0) frames else frames.drop(max(0, firstLoud - LEAD_FRAMES))

    private fun read(record: AudioRecord): ShortArray? {
        val frame = ShortArray(FRAME)
        var n = 0
        while (n < FRAME) {
            val got = record.read(frame, n, FRAME - n)
            if (got <= 0) return null
            n += got
        }
        return frame
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return sqrt(sum / frame.size)
    }

    companion object {
        private const val TAG = "AnclaVoice"
        const val RATE = 16000
        private const val FRAME = 1600 // 100 ms

        /** About two and a half seconds before the name was recognized, so "Oye" isn't cut. */
        private const val RING_FRAMES = 25
        private val WAKE_PHRASES = listOf("oye dexter", "hey dexter", "ey dexter")

        // Common words in the grammar give everyday speech something to match other than the name.
        private const val GRAMMAR = "[\"oye dexter\", \"hey dexter\", \"ey dexter\", \"oye\", \"hey\", \"de\", \"que\", \"el\", \"la\", \"texto\", \"[unk]\"]"

        /** Two seconds of exact zeros: the microphone is silenced. */
        private const val SILENCED_FRAMES = 20

        /** The last 1.5 s before the name was recognized: where "Oye Dexter" was said. */
        private const val NAME_FRAMES = 15

        /** Below this Vosk was guessing: the sound was forced onto "dexter" by the small grammar. */
        const val MIN_NAME_CONFIDENCE = 0.75

        /**
         * A voice toward the phone, above the room: 3.5× the noise and at least this loud. The voice
         * recognition microphone gives low levels (a quiet room is ~35, a voice at arm's length ~600),
         * so the floor only keeps out faint sounds; the room ratio does the real work.
         */
        const val MIN_NAME_RMS = 220.0

        /** How sure a Vosk result (with words) is of "dexter", or null when it doesn't contain it. */
        fun nameConfidence(json: String?): Double? {
            val words = runCatching { JSONObject(json ?: return null).optJSONArray("result") }.getOrNull() ?: return null
            return (0 until words.length()).map { words.getJSONObject(it) }
                .filter { it.optString("word") == "dexter" }
                .maxOfOrNull { it.optDouble("conf", 0.0) }
        }

        /** An unknown confidence (no final result reached us) doesn't block; a low one does. */
        fun nameAccepted(confidence: Double?) = confidence == null || confidence >= MIN_NAME_CONFIDENCE

        fun loudEnough(peakRms: Double, noise: Double) = peakRms >= max(MIN_NAME_RMS, noise * 3.5)

        /** The quietest a voice can be to count as speech, whatever the room (see [MIN_NAME_RMS]). */
        private const val MIN_SPEECH_RMS = 120.0
        private const val GRACE_MS = 250L
        private const val MIN_SPEECH_FRAMES = 3
        private const val WAIT_MS = 1400L
        /** A finished sentence ends the turn after this much silence: quick, like Claude's voice mode. */
        const val END_SILENCE_MS = 1100L

        /** A sentence left hanging ("y...", "quiero que...", "eh...") gets this long to go on. */
        const val UNFINISHED_SILENCE_MS = 2500L

        private val CONTINUATIONS = setOf(
            "y", "o", "u", "e", "pero", "que", "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "en", "con", "sin", "para",
            "por", "porque", "como", "cuando", "donde", "si", "entonces", "pues", "eh", "em", "mm", "este", "esto", "osea", "a", "al", "mi",
            "tu", "su", "mis", "tus", "sus", "se", "me", "te", "le", "les", "lo", "nos", "mas", "muy", "tambien", "luego", "sobre", "hasta",
            "desde", "entre", "hacia", "cual", "quien", "es", "ser", "estar", "quiero", "puedes", "podrias", "dime"
        )

        /** Whether what was said so far sounds unfinished: it ends on a word a sentence can't end on. */
        fun soundsUnfinished(text: String): Boolean {
            val last = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").trim().substringAfterLast(" ")
            return last.isNotEmpty() && last in CONTINUATIONS
        }

        /** The silence that ends the user's turn, given what they have said so far. */
        fun endSilence(text: String) = if (soundsUnfinished(text)) UNFINISHED_SILENCE_MS else END_SILENCE_MS
        /** The longest turn: plenty to explain something, as in a real conversation. */
        private const val MAX_MS = 45_000L

        /** After the tone, how long to wait for the user to start talking. */
        private const val LISTEN_WAIT_MS = 6000L
        private const val LEAD_FRAMES = 3

        /**
         * What was said in [wav], transcribed offline by Vosk with its whole vocabulary: good enough
         * for short commands ("prende la linterna"), so they run without the AI. Blocking.
         */
        fun transcribe(model: Model, wav: ByteArray): String {
            val recognizer = Recognizer(model, RATE.toFloat())
            try {
                for (chunk in pcmChunks(wav)) recognizer.acceptWaveForm(chunk, chunk.size)
                return JSONObject(recognizer.finalResult).optString("text")
            } finally {
                recognizer.close()
            }
        }

        /** The samples of a [wav] made by [wav], without its 44-byte header, in 100 ms pieces for Vosk. */
        internal fun pcmChunks(wav: ByteArray): List<ByteArray> {
            val start = minOf(WAV_HEADER, wav.size)
            return (start until wav.size step FRAME * 2).map { wav.copyOfRange(it, minOf(it + FRAME * 2, wav.size)) }
        }

        private const val WAV_HEADER = 44

        /** 16-bit mono PCM as a WAV file, which Gemini and Whisper both take. */
        fun wav(frames: List<ShortArray>): ByteArray {
            val samples = frames.sumOf { it.size }
            // One buffer filled in place: writing two bytes at a time made 200 000 small arrays for 12 s.
            val out = ByteBuffer.allocate(WAV_HEADER + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            out.put("data".toByteArray()).putInt(samples * 2)
            for (f in frames) {
                out.asShortBuffer().put(f)
                out.position(out.position() + f.size * 2)
            }
            return out.array()
        }
    }
}
