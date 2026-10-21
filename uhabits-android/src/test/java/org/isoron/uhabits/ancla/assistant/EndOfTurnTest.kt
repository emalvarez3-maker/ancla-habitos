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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dexter waits while the user is still thinking, and answers quickly once they are done. */
class EndOfTurnTest {
    @Test
    fun aSentenceLeftHangingGetsMoreTime() {
        for (s in listOf("quiero que", "recuérdame comprar pan y", "pon una alarma para", "eh", "este", "Dexter, ¿me puedes decir cuál es", "pero")) {
            assertTrue(s, DexterWake.soundsUnfinished(s))
            assertEquals(s, DexterWake.UNFINISHED_SILENCE_MS, DexterWake.endSilence(s))
        }
    }

    @Test
    fun aFinishedSentenceEndsQuickly() {
        for (s in listOf("prende la linterna", "¿qué tengo hoy?", "recuérdame comprar pan mañana", "gracias", "pon música tranquila", "")) {
            assertFalse(s, DexterWake.soundsUnfinished(s))
            assertEquals(s, DexterWake.END_SILENCE_MS, DexterWake.endSilence(s))
        }
    }

    @Test
    fun theWaitsAreHumanSized() {
        assertTrue("a finished sentence still answers fast", DexterWake.END_SILENCE_MS in 900L..1500L)
        assertTrue("a pause to think is respected", DexterWake.UNFINISHED_SILENCE_MS in 2000L..3500L)
    }
}
