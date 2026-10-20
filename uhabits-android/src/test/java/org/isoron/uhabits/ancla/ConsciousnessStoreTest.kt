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
import org.isoron.uhabits.ancla.consciousness.Dimension
import org.isoron.uhabits.ancla.consciousness.Quadrant
import org.isoron.uhabits.ancla.consciousness.Questions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.KeyGenerator

/** Consciencia: pauses, the wheel, the day's question and the examen; a mirror, kept encrypted. */
class ConsciousnessStoreTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val today = LocalDate.of(2026, 10, 20)
    private fun at(day: LocalDate, hour: Int = 12) = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun pausesNameTheQuadrantAndCountAStreak() {
        val dir = Stress.tempDir()
        val c = ConsciousnessStore(File(dir, "c.json"))
        c.addCheckIn("Ansioso", listOf("Pecho", "Hombros"), time = at(today))
        c.addCheckIn("Tranquilo", time = at(today.minusDays(1)))
        c.addCheckIn("Ansioso", listOf("Pecho"), time = at(today.minusDays(2)))
        c.addCheckIn("Inspirado", time = at(today.minusDays(4))) // the gap breaks the streak
        assertEquals(Quadrant.HIGH_UNPLEASANT, c.checkIns().first().quadrant)
        assertEquals(3, c.pauseStreak(today))
        val patterns = c.patterns(today)
        assertTrue(patterns.any { it.contains("mucha energía · desagradable") })
        assertTrue(patterns.any { it.contains("Pecho (2 veces)") })
        assertFalse(String(File(dir, "c.json").readBytes(), Charsets.ISO_8859_1).contains("Ansioso"))
        assertTrue(runCatching { c.addCheckIn("  ") }.isFailure)
    }

    @Test
    fun theWheelIsKeptPerWeekAndCarriesOver() {
        val c = ConsciousnessStore(File(Stress.tempDir(), "c.json"))
        c.setWheel(mapOf(Dimension.ATTENTION to 4, Dimension.BODY to 2), today)
        c.setWheel(mapOf(Dimension.BODY to 3), today.plusDays(1)) // same week: merged
        assertEquals(mapOf(Dimension.ATTENTION to 4, Dimension.BODY to 3), c.wheel(today))
        assertEquals("next week starts from the last one", 4, c.wheel(today.plusDays(7))[Dimension.ATTENTION])
        assertTrue(c.wheel(today.minusDays(30)).isEmpty())
        assertTrue(runCatching { c.setWheel(mapOf(Dimension.VALUES to 9), today) }.isFailure)
    }

    @Test
    fun reflectionAndExamenOfTheDay() {
        val c = ConsciousnessStore(File(Stress.tempDir(), "c.json"))
        assertNull(c.reflection(today))
        c.saveReflection("Revisé el celular al despertar sin pensarlo", today)
        assertEquals(Questions.of(today), c.reflection(today)!!.question)
        c.saveExamen("La charla con mi hermana", null, null, null, today)
        val e = c.saveExamen(null, "Escribí 20 minutos", "Le contesté mal a mi papá", "Respirar antes de responder", today)
        assertEquals("La charla con mi hermana", e.gratitude)
        assertEquals("Respirar antes de responder", e.tomorrow)
        assertEquals(1, c.examens().size)
        assertTrue(c.describe(today).contains("Examen de hoy hecho"))
    }

    @Test
    fun everyQuestionComesUpBeforeAnyRepeats() {
        val n = Questions.ALL.size
        assertEquals(n, (0 until n).map { Questions.of(today.plusDays(it.toLong())) }.toSet().size)
    }
}
