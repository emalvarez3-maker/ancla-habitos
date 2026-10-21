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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sounds that only resemble "Oye Dexter" must not wake Dexter, and what follows a false wake must not act. */
class FalseWakeTest {
    @Test
    fun theNamesConfidenceComesFromVoskWords() {
        val sure = """{"result":[{"conf":0.98,"word":"oye"},{"conf":0.93,"word":"dexter"}],"text":"oye dexter"}"""
        val forced = """{"result":[{"conf":0.61,"word":"hey"},{"conf":0.42,"word":"dexter"}],"text":"hey dexter"}"""
        assertEquals(0.93, DexterWake.nameConfidence(sure)!!, 1e-9)
        assertEquals(0.42, DexterWake.nameConfidence(forced)!!, 1e-9)
        assertNull("no name in it", DexterWake.nameConfidence("""{"result":[{"conf":1.0,"word":"oye"}],"text":"oye"}"""))
        assertNull("no words at all", DexterWake.nameConfidence("""{"text":""}"""))
        assertNull(DexterWake.nameConfidence("not json"))
        assertNull(DexterWake.nameConfidence(null))
    }

    @Test
    fun aGuessedNameIsDroppedAndAnUnknownOneIsNot() {
        assertTrue(DexterWake.nameAccepted(0.93))
        assertTrue(DexterWake.nameAccepted(DexterWake.MIN_NAME_CONFIDENCE))
        assertFalse(DexterWake.nameAccepted(0.42))
        assertTrue("no final result reached us: other checks decide", DexterWake.nameAccepted(null))
    }

    @Test
    fun theNameMustStandOutOfTheRoom() {
        assertTrue("someone talking to the phone in a quiet room", DexterWake.loudEnough(2400.0, 200.0))
        assertFalse("a voice across the room", DexterWake.loudEnough(450.0, 120.0))
        assertFalse("a loud TV that is only a little louder than itself", DexterWake.loudEnough(3000.0, 1200.0))
        assertTrue(DexterWake.loudEnough(4500.0, 1200.0))
    }

    @Test
    fun whatFollowsTheToneIsCheckedAndNeverConfirms() {
        assertTrue(PhoneSafety.WAKE_FOLLOW_TURN.contains(PhoneSafety.IGNORE))
        // A TV saying "sí, envíalo" after a false wake can't count as the user's words.
        assertFalse(PhoneSafety.saidByUser("envíalo", PhoneSafety.WAKE_FOLLOW_TURN))
        assertFalse(PhoneSafety.saidByUser("envíalo", PhoneSafety.WAKE_AUDIO_TURN))
    }
}
