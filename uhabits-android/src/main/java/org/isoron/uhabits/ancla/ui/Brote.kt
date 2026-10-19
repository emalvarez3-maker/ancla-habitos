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

package org.isoron.uhabits.ancla.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** The day's mood on the Today screen, which Dexter's face follows. */
enum class BroteMood { SLEEPY, CALM, HAPPY, JOY }

/** Growth stage, following the average automaticity of the user's habits. */
enum class BroteStage { SEED, SPROUT, PLANT, TREE }

/** A four-pointed spark, for a pleased mood. */
internal fun DrawScope.drawSpark(center: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - r)
        lineTo(center.x + r * 0.3f, center.y - r * 0.3f)
        lineTo(center.x + r, center.y)
        lineTo(center.x + r * 0.3f, center.y + r * 0.3f)
        lineTo(center.x, center.y + r)
        lineTo(center.x - r * 0.3f, center.y + r * 0.3f)
        lineTo(center.x - r, center.y)
        lineTo(center.x - r * 0.3f, center.y - r * 0.3f)
        close()
    }
    drawPath(p, color)
}
