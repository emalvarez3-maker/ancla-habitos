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
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate

/** A one-off to-do for a given day (Ancla), as opposed to a repeating habit. */
data class DayTask(
    val id: Long? = null,
    val title: String,
    val notes: String = "",
    val day: LocalDate,
    /** Minutes after midnight, or null for "any time today". */
    val minuteOfDay: Int? = null,
    /** Whether a notification fires at [minuteOfDay]. */
    val remind: Boolean = false,
    val isDone: Boolean = false
) {
    /** True when the task belongs to an earlier day and is still pending. */
    fun isOverdue(today: LocalDate): Boolean = !isDone && day.isOlderThan(today)
}
