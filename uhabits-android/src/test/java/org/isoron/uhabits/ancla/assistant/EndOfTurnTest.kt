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
    fun theWaitsLeaveRoomToFinish() {
        // The user found 1.1 s cut them off mid-sentence: Dexter now waits, and "Enviar" is the fast way.
        assertTrue("a pause to breathe never sends", DexterWake.END_SILENCE_MS in 2500L..3500L)
        assertTrue("a sentence left hanging gets longer still", DexterWake.UNFINISHED_SILENCE_MS in 4000L..5500L)
    }

    @Test
    fun theRingFillsOnlyDuringARealPause() {
        assertEquals("talking", 0f, DexterWake.pauseProgress(0, DexterWake.END_SILENCE_MS), 0f)
        assertEquals("a breath between words", 0f, DexterWake.pauseProgress(DexterWake.PAUSE_SHOWN_MS, DexterWake.END_SILENCE_MS), 0f)
        val half = DexterWake.PAUSE_SHOWN_MS + (DexterWake.END_SILENCE_MS - DexterWake.PAUSE_SHOWN_MS) / 2
        assertEquals(0.5f, DexterWake.pauseProgress(half, DexterWake.END_SILENCE_MS), 0.01f)
        assertEquals("full when it sends", 1f, DexterWake.pauseProgress(DexterWake.END_SILENCE_MS, DexterWake.END_SILENCE_MS), 0f)
        assertEquals(1f, DexterWake.pauseProgress(99_000, DexterWake.END_SILENCE_MS), 0f)
    }
}
