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

package org.isoron.uhabits.ancla

import org.isoron.uhabits.ancla.consciousness.ConsciousnessStore
import org.isoron.uhabits.ancla.consciousness.Meditation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.KeyGenerator
import kotlin.math.abs

/** Guided practices last about what the user asked for, and each sit is remembered for Dexter. */
class MeditationTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun seconds(m: Meditation, minutes: Int) = m.steps(minutes).sumOf { it.pauseSeconds + Meditation.speakSeconds(it.text) }

    @Test
    fun aPracticeLastsAboutTheMinutesAsked() {
        for (m in Meditation.entries) for (minutes in listOf(3, 5, 10, 20)) {
            val s = seconds(m, minutes)
            // Within one round of the cycle (at most about two minutes for the body scan).
            assertTrue("$m $minutes min took $s s", abs(s - minutes * 60) <= 150)
        }
    }

    @Test
    fun itOpensRepeatsAndCloses() {
        for (m in Meditation.entries) {
            val steps = m.steps(10)
            assertEquals(m.opening, steps.take(m.opening.size))
            assertEquals(m.closing, steps.takeLast(m.closing.size))
            assertTrue("$m has at least one round", steps.size >= m.opening.size + m.cycle.size + m.closing.size)
            assertTrue(steps.all { it.text.isNotBlank() && it.pauseSeconds >= 0 })
        }
    }

    @Test
    fun theMinutesStayInRange() {
        assertEquals(Meditation.BREATHING.steps(Meditation.MAX_MINUTES), Meditation.BREATHING.steps(500))
        assertEquals(Meditation.BREATHING.steps(Meditation.MIN_MINUTES), Meditation.BREATHING.steps(-3))
    }

    @Test
    fun theKindComesFromWordsOrNames() {
        assertEquals(Meditation.BODY_SCAN, Meditation.of("body_scan"))
        assertEquals(Meditation.BODY_SCAN, Meditation.of("BODY_SCAN"))
        assertEquals(Meditation.BODY_SCAN, Meditation.of("escaneo del cuerpo"))
        assertEquals(Meditation.NOTING, Meditation.of("noting"))
        assertEquals(Meditation.NOTING, Meditation.of("notar pensamientos"))
        assertEquals(Meditation.KINDNESS, Meditation.of("kindness"))
        assertEquals(Meditation.KINDNESS, Meditation.of("compasión"))
        assertEquals(Meditation.BREATHING, Meditation.of("breathing"))
        assertEquals(Meditation.BREATHING, Meditation.of(null))
        assertEquals(Meditation.BREATHING, Meditation.of("algo que no conozco"))
    }

    @Test
    fun sitsAreKeptAndDescribed() {
        val today = LocalDate.of(2026, 10, 20)
        fun at(day: LocalDate) = day.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val file = File(Stress.tempDir(), "c.json")
        val c = ConsciousnessStore(file)
        assertNull("under a minute isn't a sit", c.addSit("Respiración", 0))
        c.addSit("Respiración", 5, at(today.minusDays(1)))
        c.addSit("Bondad amorosa", 10, at(today))
        c.addSit("Respiración", 20, at(today.minusDays(9))) // outside the week
        assertEquals(15, c.sitMinutesThisWeek(today))
        assertEquals("Bondad amorosa", c.sits().first().kind)
        assertEquals("kept, encrypted, across restarts", 3, ConsciousnessStore(file).sits().size)
        assertTrue(c.describe(today).contains("Meditación: última el $today (Bondad amorosa, 10 min); 15 min en los últimos 7 días"))
    }
}
