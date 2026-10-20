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
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/** The wisdom of the day: stable all day, and every saying comes up before any repeats. */
class WisdomTest {
    @Test
    fun everySayingComesUpOnceBeforeRepeating() {
        val start = LocalDate.of(2026, 10, 20)
        val n = Wisdom.SAYINGS.size
        val seen = (0 until n).map { Wisdom.of(start.plusDays(it.toLong())) }.toSet()
        assertEquals(n, seen.size)
        assertEquals(Wisdom.of(start), Wisdom.of(start))
        assertNotEquals(Wisdom.of(start), Wisdom.of(start.plusDays(1)))
        assertEquals(n, Wisdom.SAYINGS.map { it.text }.toSet().size)
    }
}
