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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

/** The three-year Camino: goals added by voice or on screen, kept encrypted. */
class GoalStoreTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun store() = GoalStore(File(Stress.tempDir(), "goals.json"))

    @Test
    fun goalsAreOrderedByYearAndKeptEncrypted() {
        val dir = Stress.tempDir()
        val goals = GoalStore(File(dir, "goals.json"))
        goals.add("Trabajar en robótica", 2029)
        goals.add("Terminar la tesis", 2027, habit = "Escribir 20 minutos")
        goals.add("Viajar a Japón", 2028)
        assertEquals(listOf("Terminar la tesis", "Viajar a Japón", "Trabajar en robótica"), goals.all().map { it.title })
        assertFalse(String(File(dir, "goals.json").readBytes(), Charsets.ISO_8859_1).contains("Japón"))
        // A fresh store on the same file (the app and Dexter each have one) sees the same goals.
        assertEquals(3, GoalStore(File(dir, "goals.json")).all().size)
    }

    @Test
    fun sayingTheSameGoalTwiceUpdatesIt() {
        val goals = store()
        goals.add("Viajar a Japón", 2028)
        goals.add("viajar a japon", 2029, habit = "Ahorrar")
        val all = goals.all()
        assertEquals(1, all.size)
        assertEquals(2029, all[0].year)
        assertEquals(listOf("Ahorrar"), all[0].habits)
    }

    @Test
    fun updatesFindTheGoalByAnyPartOfItsName() {
        val goals = store()
        goals.add("Terminar la tesis de maestría", 2027)
        assertEquals(60, goals.update("la tesis", progress = 60)!!.progress)
        val done = goals.update("tesis", done = true)!!
        assertTrue(done.done)
        assertEquals(100, done.progress)
        assertFalse("can be marked as not done again", goals.update("tesis", done = false)!!.done)
        assertNull(goals.update("correr", progress = 10))
    }

    @Test
    fun removingAndBadInput() {
        val goals = store()
        goals.add("Correr 10 km", 2027)
        assertEquals("Correr 10 km", goals.remove("correr")!!.title)
        assertTrue(goals.all().isEmpty())
        assertTrue(runCatching { goals.add("  ", 2027) }.isFailure)
        assertTrue(runCatching { goals.add("Algo", 1500) }.isFailure)
        assertEquals("El Camino está vacío: todavía no hay metas.", GoalStore.describe(emptyList()))
    }
}
