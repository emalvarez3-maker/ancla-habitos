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
    fun woopAndTheGoalsOwnRoadmap() {
        val goals = store()
        goals.add("Trabajar en robótica", 2029)
        val woop = goals.setWoop("robótica", "Diseñar robots que ayuden en hospitales", "Me distraigo con el celular", null)!!
        assertTrue(woop.hasWoop)
        assertEquals("", woop.plan)
        goals.setWoop("robótica", null, null, "Si tomo el celular al estudiar, entonces lo dejo en otra habitación 25 min")
        goals.addMilestone("robótica", "Terminar el curso de robótica", "2027-06")
        goals.addMilestone("robótica", "Construir mi primer brazo", "2028-03")
        goals.addMilestone("robótica", "Pasantía", null)
        var g = goals.find("robótica")!!
        assertEquals(listOf("Terminar el curso de robótica", "Construir mi primer brazo", "Pasantía"), g.milestones.map { it.title })
        assertEquals(0, g.effectiveProgress)
        g = goals.setMilestoneDone("robótica", "curso")!!
        assertEquals(33, g.effectiveProgress)
        goals.setMilestoneDone("robótica", "brazo")
        g = goals.setMilestoneDone("robótica", "pasantía")!!
        assertTrue("the last milestone reaches the goal", g.done)
        assertEquals("Me distraigo con el celular", g.obstacle)
        assertTrue(GoalStore.describe(goals.all()).contains("plan si-entonces: Si tomo el celular"))
        assertTrue(runCatching { goals.setMilestoneDone("robótica", "algo inexistente") }.isFailure)
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
