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

import org.isoron.uhabits.ancla.robotics.Kutzbach
import org.isoron.uhabits.ancla.robotics.RobotConfig
import org.isoron.uhabits.ancla.robotics.Robots
import org.isoron.uhabits.ancla.robotics.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class Kinematics3DTest {
    private fun tip(config: RobotConfig, q: List<Double>) = Robots.frames(Robots.joints(config), q).last().origin

    @Test
    fun scaraMatchesItsClosedForm() {
        val q = listOf(0.4, -0.9, 0.25, 1.0)
        val p = tip(RobotConfig.SCARA, q)
        assertEquals(0.5 * cos(0.4) + 0.4 * cos(0.4 - 0.9), p.x, 1e-9)
        assertEquals(0.5 * sin(0.4) + 0.4 * sin(0.4 - 0.9), p.y, 1e-9)
        assertEquals(0.6 - 0.25 - 0.1, p.z, 1e-9)
    }

    @Test
    fun cylindricalAndPolarUseTheirCoordinates() {
        val c = tip(RobotConfig.CYLINDRICAL, listOf(1.1, 0.4, 0.6))
        assertEquals(0.6, hypot(c.x, c.y), 1e-9)
        assertEquals(0.3 + 0.4, c.z, 1e-9)
        val p = tip(RobotConfig.POLAR, listOf(0.7, 1.0, 0.55))
        assertEquals(0.55, (p - Vec3(0.0, 0.0, 0.5)).length, 1e-9)
    }

    @Test
    fun cartesianAxesAreLinearAndPerpendicular() {
        val base = tip(RobotConfig.CARTESIAN, listOf(0.5, 0.5, 0.4))
        val moves = (0..2).map { i -> tip(RobotConfig.CARTESIAN, listOf(0.5, 0.5, 0.4).mapIndexed { k, v -> if (k == i) v + 0.1 else v }) - base }
        moves.forEach { assertEquals(0.1, it.length, 1e-9) }
        for (i in 0..2) for (k in i + 1..2) assertEquals(0.0, moves[i].x * moves[k].x + moves[i].y * moves[k].y + moves[i].z * moves[k].z, 1e-9)
    }

    @Test
    fun angularWristCenterDoesNotDependOnTheWrist() {
        val joints = Robots.joints(RobotConfig.ANGULAR)
        val a = Robots.frames(joints, listOf(0.3, 0.8, 1.2, 0.0, 0.0, 0.0))[4].origin
        val b = Robots.frames(joints, listOf(0.3, 0.8, 1.2, 1.0, -0.7, 2.0))[4].origin
        assertEquals(0.0, (a - b).length, 1e-9) // spherical wrist: its axes meet at one point
    }

    @Test
    fun orientationsAndQuaternions() {
        for (m in listOf(Robots.eulerZyz(0.4, 1.0, -0.3), Robots.rpy(0.1, 0.2, 0.3))) {
            for (i in 0..2) for (j in 0..2) assertEquals(if (i == j) 1.0 else 0.0, (0..2).sumOf { k -> m[k][i] * m[k][j] }, 1e-12)
        }
        val q = Robots.quaternion(Robots.rz(PI / 2))
        assertEquals(cos(PI / 4), q[0], 1e-9)
        assertEquals(sin(PI / 4), q[3], 1e-9)
        val gimbal = Robots.rpy(0.3, PI / 2, 0.5)
        val same = Robots.rpy(0.5, PI / 2, 0.7)
        for (i in 0..2) for (j in 0..2) assertEquals(gimbal[i][j], same[i][j], 1e-9)
    }

    @Test
    fun kutzbachExamples() {
        val want = mapOf("Robot 6R" to 6, "SCARA" to 4, "Stewart" to 6, "Delta" to 9, "RSSR" to 2, "4 barras 3D" to -2)
        want.forEach { (name, dof) ->
            val (links, pairs) = Kutzbach.EXAMPLES.getValue(name)
            assertEquals(name, dof, Kutzbach.dof(links, pairs))
        }
    }
}
