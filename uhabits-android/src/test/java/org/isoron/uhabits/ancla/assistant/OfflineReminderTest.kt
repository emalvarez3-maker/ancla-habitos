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
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** Reminders understood with no AI: what the offline transcript says, numbers spelled out. */
class OfflineReminderTest {
    private val noon = LocalDateTime.of(2026, 10, 22, 12, 0)
    private fun parse(s: String, now: LocalDateTime = noon) = OfflineReminder.parse(s, now)

    @Test
    fun inSomeMinutesOrHours() {
        assertEquals(SpokenReminder("Tomar agua", noon.plusMinutes(2)), parse("recuérdame tomar agua en dos minutos"))
        assertEquals(SpokenReminder("Tomar agua", noon.plusMinutes(2)), parse("oye dexter recuérdame en 2 minutos tomar agua"))
        assertEquals(SpokenReminder("Sacar la basura", noon.plusHours(1)), parse("pon un recordatorio en una hora para sacar la basura"))
        assertEquals(SpokenReminder("Revisar el horno", noon.plusMinutes(30)), parse("recuérdame dentro de media hora revisar el horno"))
        assertEquals(SpokenReminder("Estirar", noon.plusMinutes(45)), parse("recuérdame estirar en cuarenta y cinco minutos"))
    }

    @Test
    fun atAnHour() {
        assertEquals(SpokenReminder("Llamar a mamá", noon.withHour(21).withMinute(30)), parse("recuérdame llamar a mamá a las nueve y media de la noche"))
        assertEquals(SpokenReminder("Estudiar", noon.withHour(15).withMinute(15)), parse("recuérdame estudiar a las tres y cuarto de la tarde"))
        assertEquals("tomorrow morning", SpokenReminder("Correr", noon.plusDays(1).withHour(7).withMinute(0)), parse("recuérdame correr a las siete de la mañana"))
        assertEquals("a quarter to one", SpokenReminder("Almorzar", noon.withHour(12).withMinute(45)), parse("recuérdame almorzar a la una menos cuarto"))
        assertEquals("digits from the transcript", SpokenReminder("Pagar la luz", noon.withHour(18).withMinute(20)), parse("recuérdame pagar la luz a las 18 20"))
    }

    @Test
    fun anHourWithoutMorningOrNightIsTheNextOne() {
        // "A las 9" said at noon is tonight, not this morning or tomorrow morning.
        assertEquals(noon.withHour(21), parse("recuérdame leer a las nueve")!!.at)
        // Said at 8 am, it's this morning.
        assertEquals(noon.withHour(9), parse("recuérdame leer a las nueve", noon.withHour(8))!!.at)
        // Said at 11 pm, it's tomorrow morning.
        assertEquals(noon.plusDays(1).withHour(9), parse("recuérdame leer a las nueve", noon.withHour(23))!!.at)
    }

    @Test
    fun anythingUnclearGoesToTheAi() {
        assertNull("no time", parse("recuérdame comprar pan"))
        assertNull("not a reminder", parse("qué hora es en tokio"))
        assertNull("nothing to remember", parse("recuérdame en cinco minutos"))
        assertNull("an unknown unit", parse("recuérdame tomar agua en dos días"))
        assertNull(parse(""))
    }

    @Test
    fun spokenNumbers() {
        val w = { s: String -> s.split(' ') }
        assertEquals(2 to 1, OfflineReminder.number(w("dos"), 0))
        assertEquals(21 to 1, OfflineReminder.number(w("veintiuno"), 0))
        assertEquals(35 to 3, OfflineReminder.number(w("treinta y cinco"), 0))
        assertEquals(40 to 1, OfflineReminder.number(w("cuarenta minutos"), 0))
        assertEquals(7 to 1, OfflineReminder.number(w("7"), 0))
        assertNull(OfflineReminder.number(w("minutos"), 0))
    }
}
