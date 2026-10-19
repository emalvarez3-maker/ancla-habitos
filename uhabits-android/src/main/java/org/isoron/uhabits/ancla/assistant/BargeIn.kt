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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lets the user interrupt Dexter by talking, as in Claude's voice mode. While Dexter speaks,
 * the microphone listens through the phone's echo canceller (the voice-call input), and nothing
 * is recorded or sent anywhere: only the loudness of each 20 ms frame is looked at.
 *
 * Whatever of Dexter's own voice leaks past the echo canceller is tracked as a moving baseline,
 * so only a voice clearly louder than that, held for a moment, counts as the user cutting in.
 */
class BargeIn(private val onInterrupt: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning get() = running

    @SuppressLint("MissingPermission") // only started after the microphone permission was granted
    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, FRAME * 4))
            }.getOrNull()
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                running = false
                return@Thread
            }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            try {
                record.startRecording()
                val frame = ShortArray(FRAME)
                var baseline = MIN_LEVEL / 3.0
                var frames = 0
                var loud = 0
                while (running) {
                    val n = record.read(frame, 0, FRAME)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                    val level = sqrt(sum / n)
                    frames++
                    val threshold = max(MIN_LEVEL, baseline * RATIO)
                    if (frames > WARMUP_FRAMES && level > threshold) {
                        if (++loud >= HOLD_FRAMES) {
                            running = false
                            main.post(onInterrupt)
                            break
                        }
                    } else {
                        loud = 0
                        // Only quiet frames teach the baseline, so the user's voice never raises it.
                        baseline += (level - baseline) * (if (frames <= WARMUP_FRAMES) 0.3 else 0.05)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { record.stop() }
                record.release()
                aec?.release()
                ns?.release()
            }
        }.apply {
            name = "dexter-barge-in"
            start()
        }
    }

    /** Stops listening and releases the microphone before returning, so dictation can take it. */
    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(300) } }
        thread = null
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
        /** The first 300 ms only learn how loud Dexter sounds through the canceller. */
        const val WARMUP_FRAMES = 15
        /** About a quarter of a second of real speech: a cough or a click doesn't interrupt. */
        const val HOLD_FRAMES = 12
        /** How much louder than the leaked echo the user has to be. */
        const val RATIO = 3.0
        /** Floor for the threshold, in 16-bit RMS: normal speech at arm's length is well above it. */
        const val MIN_LEVEL = 900.0
    }
}
