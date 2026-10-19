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
import java.io.ByteArrayOutputStream
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
 */
class DexterWake(
    private val model: Model,
    private val onRequest: (wav: ByteArray) -> Unit,
    private val onWakeOnly: () -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onSilenced: () -> Unit = {}
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** False once the listener ended by itself (microphone busy, or after the name was heard). */
    val alive get() = thread?.isAlive == true

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
        val recognizer = Recognizer(model, RATE.toFloat(), GRAMMAR)
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                // No microphone at all for a background service: same remedy as a silenced one.
                onSilenced()
                throw IllegalStateException("microphone busy")
            }
            record.startRecording()
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
                val heard = JSONObject(if (final) recognizer.result else recognizer.partialResult)
                    .let { it.optString("text").ifEmpty { it.optString("partial") } }
                // Only the whole phrase counts ("oye/hey/ey dexter", never "dexter" alone), and only
                // once Vosk keeps it in two guesses in a row or a final result: a passing sound
                // that merely resembles the name is dropped.
                hits = if (WAKE_PHRASES.any { it in heard }) hits + 1 else 0
                if (hits < 2 && !(final && hits == 1)) continue

                Log.d(TAG, "wake word heard")
                val request = capture(record, ring.toList(), noise)
                val stopped = !running // stop() was called meanwhile: nobody wants the result
                running = false
                record.stop()
                record.release()
                recognizer.close()
                if (stopped) return
                Log.d(TAG, if (request != null) "request said with the name: ${request.size * 100} ms" else "name only")
                if (request != null) onRequest(wav(request)) else onWakeOnly()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake listener stopped: ${e.message}")
        }
        runCatching { record.stop() }
        record.release()
        recognizer.close()
    }

    /**
     * Keeps recording after the name. Returns the audio from the buffered seconds to the end of the
     * request, or null when the user said only the name: no speech within [WAIT_MS].
     */
    private fun capture(record: AudioRecord, buffered: List<ShortArray>, noise: Double): List<ShortArray>? {
        val frames = buffered.toMutableList()
        val threshold = max(noise * 3.0, MIN_SPEECH_RMS)
        val start = System.currentTimeMillis()
        // The name itself may still be ending: speech counts as the request after a short grace.
        var spoke = false
        var speechFrames = 0
        var lastLoud = start
        while (running) {
            val frame = read(record) ?: return if (spoke) frames else null
            frames += frame
            val now = System.currentTimeMillis()
            val loud = rms(frame) > threshold
            onLevel((rms(frame) / 4000.0).coerceIn(0.0, 1.0).toFloat())
            if (loud) {
                lastLoud = now
                if (now - start > GRACE_MS && ++speechFrames >= MIN_SPEECH_FRAMES) spoke = true
            }
            when {
                !spoke && now - start > WAIT_MS -> return null
                spoke && now - lastLoud > END_SILENCE_MS -> return frames
                now - start > MAX_MS -> return if (spoke) frames else null
            }
        }
        return null
    }

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

        private const val MIN_SPEECH_RMS = 350.0
        private const val GRACE_MS = 250L
        private const val MIN_SPEECH_FRAMES = 3
        private const val WAIT_MS = 1400L
        private const val END_SILENCE_MS = 900L
        private const val MAX_MS = 12_000L

        /** 16-bit mono PCM as a WAV file, which Gemini and Whisper both take. */
        fun wav(frames: List<ShortArray>): ByteArray {
            val samples = frames.sumOf { it.size }
            val out = ByteArrayOutputStream(44 + samples * 2)
            fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.write("RIFF".toByteArray()); int(36 + samples * 2); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(RATE); int(RATE * 2); short(2); short(16)
            out.write("data".toByteArray()); int(samples * 2)
            for (f in frames) for (s in f) short(s.toInt())
            return out.toByteArray()
        }
    }
}
