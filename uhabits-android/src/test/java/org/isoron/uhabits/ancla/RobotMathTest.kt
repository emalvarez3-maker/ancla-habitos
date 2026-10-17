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

import org.isoron.uhabits.ancla.robotics.RobotMath
import org.isoron.uhabits.ancla.robotics.RobotMath.Arm2R
import org.isoron.uhabits.ancla.robotics.RobotMath.Point
import org.isoron.uhabits.ancla.robotics.RobotMath.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class RobotMathTest {
    private val arm = Arm2R(1.0, 0.7)

    @Test
    fun forwardKinematicsOfAStraightArm() {
        val tip = arm.tip(0.0, 0.0)
        assertEquals(1.7, tip.x, 1e-9)
        assertEquals(0.0, tip.y, 1e-9)
        val up = arm.tip(PI / 2, -PI / 2)
        assertEquals(0.7, up.x, 1e-9)
        assertEquals(1.0, up.y, 1e-9)
    }

    @Test
    fun inverseKinematicsReachesTheTargetWithBothElbows() {
        val target = Point(0.9, 0.8)
        for (elbowUp in listOf(true, false)) {
            val ik = arm.solve(target, elbowUp)
            assertTrue(ik.reachable)
            val tip = arm.tip(ik.t1, ik.t2)
            assertEquals(target.x, tip.x, 1e-9)
            assertEquals(target.y, tip.y, 1e-9)
            // Elbow up: the elbow is on the left of the base→tip line (positive cross product).
            val elbow = arm.elbow(ik.t1)
            val cross = target.x * elbow.y - target.y * elbow.x
            assertEquals(elbowUp, cross > 0)
        }
    }

    @Test
    fun unreachableTargetsGoToTheNearestPoint() {
        val ik = arm.solve(Point(3.0, 0.0), elbowUp = true)
        assertFalse(ik.reachable)
        val tip = arm.tip(ik.t1, ik.t2)
        assertEquals(1.7, hypot(tip.x, tip.y), 1e-9)
        val inside = arm.solve(Point(0.1, 0.0), elbowUp = false)
        assertFalse(inside.reachable)
        assertEquals(0.3, hypot(arm.tip(inside.t1, inside.t2).x, arm.tip(inside.t1, inside.t2).y), 1e-9)
    }

    @Test
    fun jacobianMatchesFiniteDifferences() {
        val t1 = 0.4
        val t2 = 1.1
        val j = arm.jacobian(t1, t2)
        val h = 1e-6
        val d1 = arm.tip(t1 + h, t2)
        val d2 = arm.tip(t1, t2 + h)
        val p = arm.tip(t1, t2)
        assertEquals((d1.x - p.x) / h, j.a, 1e-5)
        assertEquals((d2.x - p.x) / h, j.b, 1e-5)
        assertEquals((d1.y - p.y) / h, j.c, 1e-5)
        assertEquals((d2.y - p.y) / h, j.d, 1e-5)
        assertEquals(1.0 * 0.7 * kotlin.math.sin(t2), j.det, 1e-9)
        val straight = arm.jacobian(0.3, 0.0)
        assertEquals(0.0, straight.det, 1e-12)
        assertEquals(0.0, straight.ellipse().second, 1e-6) // singular: the ellipse collapses to a line
    }

    @Test
    fun homogeneousTransformRotatesThenTranslates() {
        val p = RobotMath.transform(Point(1.0, 0.0), PI / 2, 2.0, 3.0)
        assertEquals(2.0, p.x, 1e-9)
        assertEquals(4.0, p.y, 1e-9)
    }

    @Test
    fun proportionalOnlyLeavesSteadyStateError() {
        val r = RobotMath.pidStep(kp = 20.0, ki = 0.0, kd = 5.0)
        assertFalse(r.unstable)
        assertEquals(20.0 / 24.0, r.finalValue, 1e-3) // Kp / (k + Kp)
        assertNotNull(r.settlingTime)
    }

    @Test
    fun integralRemovesTheErrorAndExcessiveIntegralIsUnstable() {
        val pid = RobotMath.pidStep(kp = 20.0, ki = 8.0, kd = 3.0, duration = 20.0)
        assertEquals(1.0, pid.finalValue, 1e-2)
        assertTrue(pid.overshootPercent >= 0)
        // (b + Kd)(k + Kp) < Ki → unstable (Routh–Hurwitz)
        val bad = RobotMath.pidStep(kp = 0.0, ki = 40.0, kd = 0.0)
        assertTrue(bad.unstable || bad.settlingTime == null)
        assertNull(bad.settlingTime)
    }

    @Test
    fun differentialDriveFollowsACircle() {
        var pose = Pose(0.0, 0.0, 0.0)
        val v = 0.5
        val w = 0.5 // radius 1
        repeat(1000) { pose = RobotMath.drive(pose, v, w, 2 * PI / w / 1000) }
        assertEquals(0.0, pose.x, 1e-6)
        assertEquals(0.0, pose.y, 1e-6)
        val half = RobotMath.drive(Pose(0.0, 0.0, 0.0), v, w, PI / w)
        assertEquals(2.0, half.y, 1e-9)
        val (right, left) = RobotMath.wheels(0.5, 1.0, 0.3)
        assertEquals(0.65, right, 1e-9)
        assertEquals(0.35, left, 1e-9)
    }

    @Test
    fun lowPassSmoothsAndFormatsUseADot() {
        assertEquals(listOf(0.0, 0.5, 0.75), RobotMath.lowPass(listOf(0.0, 1.0, 1.0), 0.5))
        assertEquals("1.50", RobotMath.fmt(1.5))
        assertEquals("0.00", RobotMath.fmt(-0.001))
    }

    @Test
    fun trajectoriesStartAndEndAtRestAndMatchTheLesson() {
        for (p in RobotMath.Profile.entries) {
            val (start, v0) = RobotMath.trajectory(p, 0.0, 90.0, 2.0, 0.0, 0.5)
            val (end, v1) = RobotMath.trajectory(p, 0.0, 90.0, 2.0, 2.0, 0.5)
            assertEquals(0.0, start, 1e-9)
            assertEquals(90.0, end, 1e-9)
            assertEquals(0.0, v0, 1e-9)
            assertEquals(0.0, v1, 1e-9)
            val peak = (0..2000).maxOf { RobotMath.trajectory(p, 0.0, 90.0, 2.0, it / 1000.0, 0.5).second }
            assertEquals(RobotMath.peakSpeed(p, 90.0, 2.0, 0.5), peak, 1e-3)
        }
        assertEquals(45.0, RobotMath.trajectory(RobotMath.Profile.CUBIC, 0.0, 90.0, 2.0, 1.0).first, 1e-9)
        assertEquals(67.5, RobotMath.peakSpeed(RobotMath.Profile.CUBIC, 90.0, 2.0), 1e-9)
        assertEquals(60.0, RobotMath.peakSpeed(RobotMath.Profile.TRAPEZOID, 90.0, 2.0, 0.5), 1e-9)
    }

    @Test
    fun pendulumHoldsWhereGravityBalancesTheMotor() {
        val p = RobotMath.Pendulum()
        var state = 0.0 to 0.0
        val torque = p.maxGravityTorque * kotlin.math.sin(PI / 6)
        repeat(20000) { state = RobotMath.pendulumStep(state.first, state.second, torque, 0.5, p, 0.001) }
        assertEquals(PI / 6, state.first, 1e-4)
        // Small free oscillations: period 2π·sqrt(l/g) ≈ 1.42 s
        var th = 0.05
        var w = 0.0
        var crossings = 0
        var firstCross = 0.0
        var lastCross = 0.0
        for (i in 1..10000) {
            val prev = th
            RobotMath.pendulumStep(th, w, 0.0, 0.0, p, 0.0005).let { th = it.first; w = it.second }
            if (prev > 0 && th <= 0) {
                if (crossings == 0) firstCross = i * 0.0005 else lastCross = i * 0.0005
                crossings++
            }
        }
        assertEquals(2 * PI * kotlin.math.sqrt(0.5 / 9.81), (lastCross - firstCross) / (crossings - 1), 2e-3)
    }
}
