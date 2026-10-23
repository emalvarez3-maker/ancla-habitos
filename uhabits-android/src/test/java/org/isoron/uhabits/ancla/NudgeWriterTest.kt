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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dexter's check-ins name what's pending, what's at stake and a way out, and stay quiet when nothing is. */
class NudgeWriterTest {
    private val workout = PendingHabit(1, "Ejercicio", "desayunar", "10 flexiones", keyDay = false, streak = 0)
    private val read = PendingHabit(2, "Leer", "", "", keyDay = false, streak = 5)

    @Test
    fun aKeyDayComesFirst() {
        val n = NudgeWriter.write(NudgeSlot.MIDDAY, listOf(read, workout.copy(keyDay = true)), emptyList(), 0, 2, 0)!!
        assertEquals("Día clave: Ejercicio", n.title)
        assertEquals(1L, n.habitId)
        assertTrue(n.text, n.text.contains("10 flexiones"))
    }

    @Test
    fun aStreakIsWhatsAtStake() {
        val n = NudgeWriter.write(NudgeSlot.EVENING, listOf(workout, read), emptyList(), 0, 2, 0)!!
        assertEquals("the longest streak goes first", 2L, n.habitId)
        assertTrue(n.text, n.text.contains("5 días"))
    }

    @Test
    fun theMorningAsksForAPlan() {
        val n = NudgeWriter.write(NudgeSlot.MORNING, listOf(workout), emptyList(), 0, 1, 0)!!
        assertTrue(n.text, n.text.contains("después de desayunar"))
        val noPlan = NudgeWriter.write(NudgeSlot.MORNING, listOf(read.copy(streak = 0)), emptyList(), 0, 1, 0)!!
        assertTrue(noPlan.text, noPlan.text.contains("¿después de qué lo harás?"))
    }

    @Test
    fun everyNudgeEndsInAnAwarenessQuestion() {
        for (slot in NudgeSlot.entries) for (seed in 0..9) {
            val n = NudgeWriter.write(slot, listOf(workout), emptyList(), 0, 1, seed)!!
            assertTrue(n.text, NudgeWriter.QUESTIONS.any { n.text.endsWith(it) })
        }
    }

    @Test
    fun tasksAreRemindedWithOverdueFirst() {
        val tasks = listOf(PendingTask("Pagar luz", 18 * 60, false), PendingTask("Enviar informe", null, true))
        val n = NudgeWriter.write(NudgeSlot.MIDDAY, emptyList(), tasks, 1, 1, 0)!!
        assertNull(n.habitId)
        assertTrue(n.text, n.text.contains("Pendientes, 1 atrasada: Enviar informe (atrasada), Pagar luz (18:00)."))
        val many = (1..5).map { PendingTask("T$it", null, false) }
        assertTrue(NudgeWriter.write(NudgeSlot.EVENING, emptyList(), many, 0, 0, 0)!!.text.contains("y 2 más"))
    }

    @Test
    fun nothingPendingMeansSilenceExceptToCloseTheDay() {
        assertNull(NudgeWriter.write(NudgeSlot.MIDDAY, emptyList(), emptyList(), 2, 2, 0))
        assertNull(NudgeWriter.write(NudgeSlot.EVENING, emptyList(), emptyList(), 2, 2, 0))
        val night = NudgeWriter.write(NudgeSlot.NIGHT, emptyList(), emptyList(), 2, 2, 0)
        assertNotNull(night)
        assertTrue(night!!.title.contains("2/2"))
        assertTrue(night.text.contains("examen"))
    }

    @Test
    fun theStreakCountsDaysInARowBeforeToday() {
        val done = setOf(1, 2, 3, 5)
        assertEquals(3, NudgeWriter.streak({ it in done }))
        assertEquals(0, NudgeWriter.streak({ false }))
        assertEquals(10, NudgeWriter.streak({ true }, max = 10))
    }

    @Test
    fun deadlinesComeUpAheadOfTime() {
        val due = listOf(Deadline("Informe de física", 1), Deadline("Matrícula", 3), Deadline("Viaje", 10))
        assertEquals("Fechas límite: Informe de física (mañana), Matrícula (en 3 días).", NudgeWriter.deadlinesLine(due))
        assertNull("nothing in the next three days", NudgeWriter.deadlinesLine(listOf(Deadline("Viaje", 10))))
        val morning = NudgeWriter.write(NudgeSlot.MORNING, emptyList(), emptyList(), 0, 0, 0, due)!!
        assertTrue(morning.text, morning.text.contains("Informe de física (mañana)"))
        val evening = NudgeWriter.write(NudgeSlot.EVENING, listOf(workout), emptyList(), 0, 1, 0, due)!!
        assertTrue(evening.text, evening.text.contains("Fechas límite"))
        assertNull("midday stays quiet about deadlines", NudgeWriter.write(NudgeSlot.MIDDAY, emptyList(), emptyList(), 0, 0, 0, due))
    }
}
