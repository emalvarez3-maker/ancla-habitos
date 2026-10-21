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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Borra la tarea de comprar pan" finds the right one, and an unclear name asks instead of guessing. */
class NameMatchTest {
    private val tasks = listOf("Comprar pan", "Pagar el arriendo", "Enviar informe de física", "Enviar informe de química", "Llamar a mamá")

    private fun pick(wanted: String) = NameMatch.pick(tasks, wanted) { it }

    @Test
    fun exactAndLooseNamesAreFound() {
        assertEquals(NameMatch.Result.Found("Comprar pan"), pick("comprar pan"))
        assertEquals(NameMatch.Result.Found("Comprar pan"), pick("la tarea de comprar pan"))
        assertEquals(NameMatch.Result.Found("Pagar el arriendo"), pick("arriendo"))
        assertEquals("accents and case don't matter", NameMatch.Result.Found("Llamar a mamá"), pick("LLAMAR A MAMA"))
        assertEquals(NameMatch.Result.Found("Enviar informe de física"), pick("el informe de fisica"))
    }

    @Test
    fun anUnclearNameAsksWhichOne() {
        val r = pick("enviar informe")
        assertTrue(r is NameMatch.Result.Several)
        assertEquals(2, (r as NameMatch.Result.Several).items.size)
    }

    @Test
    fun nothingCloseIsNone() {
        assertEquals(NameMatch.Result.None, pick("ir al gimnasio"))
        assertEquals(NameMatch.Result.None, pick(""))
        assertEquals("filler words alone match nothing", NameMatch.Result.None, pick("la tarea"))
        assertEquals(NameMatch.Result.None, NameMatch.pick(emptyList<String>(), "pan") { it })
    }
}
