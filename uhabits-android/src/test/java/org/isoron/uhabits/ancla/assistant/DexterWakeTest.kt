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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** The recording "Oye Dexter" sends: a WAV Gemini accepts, and the pieces Vosk transcribes. */
class DexterWakeTest {
    /** 100 ms frames of 16 kHz audio, as the microphone gives them. */
    private fun frames(count: Int, rnd: Random = Random(7)) = List(count) { ShortArray(1600) { rnd.nextInt(-32768, 32768).toShort() } }

    /** The WAV writer as it was before it filled one buffer, to show the bytes are the same. */
    private fun referenceWav(frames: List<ShortArray>): ByteArray {
        val samples = frames.sumOf { it.size }
        val out = ByteArrayOutputStream(44 + samples * 2)
        fun int(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun short(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.write("RIFF".toByteArray()); int(36 + samples * 2); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(16000); int(32000); short(2); short(16)
        out.write("data".toByteArray()); int(samples * 2)
        for (f in frames) for (s in f) short(s.toInt())
        return out.toByteArray()
    }

    @Test
    fun theHeaderDescribes16kMonoPcm() {
        val wav = DexterWake.wav(frames(3))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44 + 3 * 1600 * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(wav.size - 8, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(DexterWake.RATE, b.getInt(24))
        assertEquals(DexterWake.RATE * 2, b.getInt(28)) // bytes per second
        assertEquals(2, b.getShort(32).toInt()) // bytes per sample
        assertEquals(16, b.getShort(34).toInt()) // bits
        assertEquals("data", String(wav, 36, 4))
        assertEquals(3 * 1600 * 2, b.getInt(40))
    }

    @Test
    fun samplesAreLittleEndianAndInOrder() {
        val wav = DexterWake.wav(listOf(shortArrayOf(1, -1, 0x1234, Short.MIN_VALUE), shortArrayOf(Short.MAX_VALUE)))
        val data = wav.copyOfRange(44, wav.size)
        assertArrayEquals(byteArrayOf(1, 0, -1, -1, 0x34, 0x12, 0, -128, -1, 127), data)
        assertEquals(44, DexterWake.wav(emptyList()).size)
    }

    @Test
    fun sameBytesAsBeforeAndFaster() {
        for (n in listOf(0, 1, 5, 120)) {
            val f = frames(n, Random(n))
            assertArrayEquals("$n frames", referenceWav(f), DexterWake.wav(f))
        }
        // Twelve seconds, the longest request: how long building the file takes.
        val long = frames(120)
        fun micros(build: () -> ByteArray): Double {
            repeat(20) { build() }
            val start = System.nanoTime()
            repeat(50) { build() }
            return (System.nanoTime() - start) / 1000.0 / 50
        }
        val before = micros { referenceWav(long) }
        val after = micros { DexterWake.wav(long) }
        println("DexterWake.wav 12 s: before %.0f µs, now %.0f µs".format(before, after))
        assertTrue("filling one buffer is not slower than 200 000 small writes", after < before)
    }

    @Test
    fun transcriptionGetsTheSamplesWithoutTheHeaderIn100msPieces() {
        val f = frames(25)
        f[0][0] = 0x0102
        val wav = DexterWake.wav(f)
        val chunks = DexterWake.pcmChunks(wav)
        assertEquals(25, chunks.size)
        assertTrue(chunks.all { it.size == 3200 })
        assertEquals("the first bytes are a sample, not the header", 0x02, chunks[0][0].toInt())
        assertArrayEquals(wav.copyOfRange(44, wav.size), chunks.fold(ByteArray(0)) { acc, c -> acc + c })
    }

    @Test
    fun aShortOrBrokenRecordingNeverFails() {
        // A last piece shorter than 100 ms.
        val wav = DexterWake.wav(listOf(ShortArray(1600), ShortArray(10)))
        assertEquals(listOf(3200, 20), DexterWake.pcmChunks(wav).map { it.size })
        // Only a header, or less than one.
        assertTrue(DexterWake.pcmChunks(DexterWake.wav(emptyList())).isEmpty())
        assertTrue(DexterWake.pcmChunks(ByteArray(10)).isEmpty())
        assertTrue(DexterWake.pcmChunks(ByteArray(0)).isEmpty())
        assertEquals(listOf(1), DexterWake.pcmChunks(ByteArray(45)).map { it.size })
    }
}
