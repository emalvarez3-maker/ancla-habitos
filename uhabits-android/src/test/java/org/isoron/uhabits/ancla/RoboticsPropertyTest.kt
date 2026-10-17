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
import org.isoron.uhabits.ancla.robotics.LinkageModel
import org.isoron.uhabits.ancla.robotics.LinkagePreset
import org.isoron.uhabits.ancla.robotics.LinkagePresets
import org.isoron.uhabits.ancla.robotics.LinkageSolver
import org.isoron.uhabits.ancla.robotics.NodeKind
import org.isoron.uhabits.ancla.robotics.RobotConfig
import org.isoron.uhabits.ancla.robotics.RobotMath
import org.isoron.uhabits.ancla.robotics.RobotMath.Point
import org.isoron.uhabits.ancla.robotics.Robots
import org.isoron.uhabits.ancla.robotics.SpatialPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Properties that must hold for any input, checked on thousands of random cases. */
class RoboticsPropertyTest {
    private fun lengthsKept(m: LinkageModel, tol: Double = 2e-3) = m.bodies.all { b ->
        b.lengths.all { (k, len) -> abs(hypot(m.node(k.first).x - m.node(k.second).x, m.node(k.first).y - m.node(k.second).y) - len) <= tol * max(len, 1e-6) }
    }

    @Test
    fun inverseKinematicsAlwaysLandsOnTheTargetOrTheNearestReachablePoint() {
        val rnd = Random(30)
        repeat(Stress.iterations(20_000)) {
            val arm = RobotMath.Arm2R(rnd.nextDouble(0.1, 2.0), rnd.nextDouble(0.1, 2.0))
            val target = Point(rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-5.0, 5.0))
            for (up in listOf(true, false)) {
                val ik = arm.solve(target, up)
                val tip = arm.tip(ik.t1, ik.t2)
                assertTrue(ik.t1.isFinite() && ik.t2.isFinite())
                val r = hypot(target.x, target.y)
                if (ik.reachable) {
                    assertEquals(target.x, tip.x, 1e-6)
                    assertEquals(target.y, tip.y, 1e-6)
                } else {
                    val expected = r.coerceIn(arm.minReach, arm.maxReach)
                    assertEquals(expected, hypot(tip.x, tip.y), 1e-6)
                }
                if (ik.reachable && abs(ik.t2) > 1e-6) assertEquals(up, ik.t2 < 0)
            }
        }
    }

    @Test
    fun jacobianMatchesNumericalDerivativesEverywhere() {
        val rnd = Random(31)
        repeat(Stress.iterations(5_000)) {
            val arm = RobotMath.Arm2R(rnd.nextDouble(0.1, 2.0), rnd.nextDouble(0.1, 2.0))
            val t1 = rnd.nextDouble(-PI, PI)
            val t2 = rnd.nextDouble(-PI, PI)
            val j = arm.jacobian(t1, t2)
            val h = 1e-6
            val p = arm.tip(t1, t2)
            val d1 = arm.tip(t1 + h, t2)
            val d2 = arm.tip(t1, t2 + h)
            assertEquals((d1.x - p.x) / h, j.a, 1e-4)
            assertEquals((d2.x - p.x) / h, j.b, 1e-4)
            assertEquals((d1.y - p.y) / h, j.c, 1e-4)
            assertEquals((d2.y - p.y) / h, j.d, 1e-4)
            val (major, minor, _) = j.ellipse()
            assertEquals(abs(j.det), major * minor, 1e-6) // area of the velocity ellipse = |det J|
        }
    }

    @Test
    fun everyDhChainProducesProperRotations() {
        val rnd = Random(32)
        repeat(Stress.iterations(3_000)) {
            val config = RobotConfig.entries[rnd.nextInt(RobotConfig.entries.size)]
            val joints = Robots.joints(config)
            val q = joints.map { rnd.nextDouble(it.min, it.max) }
            Robots.frames(joints, q).forEach { f ->
                for (i in 0..2) for (k in 0..2) {
                    val dot = (0..2).sumOf { r -> f[r, i] * f[r, k] }
                    assertEquals(if (i == k) 1.0 else 0.0, dot, 1e-9)
                }
                val det = f[0, 0] * (f[1, 1] * f[2, 2] - f[1, 2] * f[2, 1]) - f[0, 1] * (f[1, 0] * f[2, 2] - f[1, 2] * f[2, 0]) + f[0, 2] * (f[1, 0] * f[2, 1] - f[1, 1] * f[2, 0])
                assertEquals(1.0, det, 1e-9)
                assertEquals(listOf(0.0, 0.0, 0.0, 1.0), (0..3).map { f[3, it] })
            }
        }
    }

    @Test
    fun quaternionsRebuildTheSameRotation() {
        val rnd = Random(33)
        repeat(Stress.iterations(10_000)) {
            val r = if (rnd.nextBoolean()) Robots.eulerZyz(rnd.nextDouble(-PI, PI), rnd.nextDouble(0.0, PI), rnd.nextDouble(-PI, PI))
            else Robots.rpy(rnd.nextDouble(-PI, PI), rnd.nextDouble(-PI / 2, PI / 2), rnd.nextDouble(-PI, PI))
            val (w, x, y, z) = Robots.quaternion(r).toList()
            assertEquals(1.0, w * w + x * x + y * y + z * z, 1e-9)
            assertTrue(w >= 0)
            val back = arrayOf(
                doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
                doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
                doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y))
            )
            for (i in 0..2) for (k in 0..2) assertEquals(r[i][k], back[i][k], 1e-9)
        }
    }

    @Test
    fun pidStabilityAgreesWithRouthHurwitz() {
        val rnd = Random(34)
        var checked = 0
        repeat(Stress.iterations(400)) {
            val kp = rnd.nextDouble(0.0, 60.0)
            val ki = rnd.nextDouble(0.0, 40.0)
            val kd = rnd.nextDouble(0.0, 15.0)
            val margin = (1 + kd) * (4 + kp) - ki
            if (abs(margin) < 8) return@repeat // too close to the edge to decide in 20 s
            val r = RobotMath.pidStep(kp, ki, kd, duration = 20.0)
            assertTrue(r.positions.all { it.isFinite() })
            assertTrue(r.overshootPercent >= 0)
            r.settlingTime?.let { assertTrue(it in 0.0..20.0) }
            if (margin > 0) {
                assertFalse("kp=$kp ki=$ki kd=$kd should be stable", r.unstable)
                assertTrue(abs(r.positions.last()) < 2.0)
            } else {
                val tail = r.positions.takeLast(200)
                assertTrue("kp=$kp ki=$ki kd=$kd should grow", r.unstable || tail.max() - tail.min() > 0.05)
            }
            checked++
        }
        assertTrue(checked > Stress.iterations(100))
    }

    @Test
    fun trajectoriesAreSmoothMonotonicAndHitTheirEnds() {
        val rnd = Random(35)
        repeat(Stress.iterations(2_000)) {
            val q0 = rnd.nextDouble(-180.0, 180.0)
            val qf = rnd.nextDouble(-180.0, 180.0)
            val T = rnd.nextDouble(0.2, 10.0)
            val ta = rnd.nextDouble(0.01, 0.5) * T
            for (p in RobotMath.Profile.entries) {
                var last = q0
                for (i in 0..400) {
                    val (q, v) = RobotMath.trajectory(p, q0, qf, T, i * T / 400, ta)
                    assertTrue(q.isFinite() && v.isFinite())
                    if (qf >= q0) assertTrue(q >= last - 1e-9) else assertTrue(q <= last + 1e-9)
                    assertTrue(abs(v) <= abs(RobotMath.peakSpeed(p, qf - q0, T, ta)) + 1e-9)
                    last = q
                }
                assertEquals(qf, RobotMath.trajectory(p, q0, qf, T, T, ta).first, 1e-9)
                assertEquals(qf, RobotMath.trajectory(p, q0, qf, T, T * 5, ta).first, 1e-9) // clamped after the end
            }
        }
    }

    @Test
    fun aFrictionlessPendulumKeepsItsEnergy() {
        val p = RobotMath.Pendulum()
        val rnd = Random(36)
        repeat(Stress.iterations(20)) {
            var th = rnd.nextDouble(-3.0, 3.0)
            var w = rnd.nextDouble(-5.0, 5.0)
            fun energy() = 0.5 * p.mass * p.length * p.length * w * w - p.mass * p.gravity * p.length * cos(th)
            val e0 = energy()
            repeat(10_000) { RobotMath.pendulumStep(th, w, 0.0, 0.0, p, 0.001).let { th = it.first; w = it.second } }
            assertEquals(e0, energy(), 1e-6 * max(1.0, abs(e0)))
        }
    }

    @Test
    fun differentialDriveIntegrationIsExactForAnyStep() {
        val rnd = Random(37)
        repeat(Stress.iterations(3_000)) {
            val v = rnd.nextDouble(-1.0, 1.0)
            val w = rnd.nextDouble(-3.0, 3.0)
            val t = rnd.nextDouble(0.0, 10.0)
            val one = RobotMath.drive(RobotMath.Pose(0.0, 0.0, 0.0), v, w, t)
            var many = RobotMath.Pose(0.0, 0.0, 0.0)
            repeat(100) { many = RobotMath.drive(many, v, w, t / 100) }
            assertEquals(one.x, many.x, 1e-6)
            assertEquals(one.y, many.y, 1e-6)
            assertEquals(cos(one.theta), cos(many.theta), 1e-6)
            assertEquals(sin(one.theta), sin(many.theta), 1e-6)
        }
    }

    // --- Mechanisms ---

    @Test
    fun randomDrawingsAlwaysCountConsistently() {
        val rnd = Random(38)
        repeat(Stress.iterations(3_000)) {
            var m = LinkageModel()
            repeat(rnd.nextInt(1, 12)) {
                val kind = NodeKind.entries[rnd.nextInt(3)]
                m = m.addNode(rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0), kind).first
            }
            repeat(rnd.nextInt(0, 16)) {
                val a = m.nodes.random(rnd).id
                val b = m.nodes.random(rnd).id
                m = m.addBar(a, b)
                if (rnd.nextInt(5) == 0 && m.bodies.size >= 2) m = m.weld(m.bodies.random(rnd).id, m.bodies.random(rnd).id)
                if (rnd.nextInt(6) == 0 && m.bodies.isNotEmpty()) m = m.withMotor(m.bodies.random(rnd).id) ?: m
                if (rnd.nextInt(8) == 0 && m.nodes.size > 1) m = m.deleteNode(m.nodes.random(rnd).id)
            }
            // Structure stays valid: bodies point at existing nodes, the motor at a grounded body.
            val ids = m.nodes.map { it.id }.toSet()
            m.bodies.forEach { b -> assertTrue(b.nodes.size >= 2 && b.nodes.all { it in ids }) }
            m.motor?.let { mo -> assertTrue(m.bodies.any { it.id == mo.body && mo.pivot in it.nodes } && m.node(mo.pivot).kind == NodeKind.GROUND) }
            val c = m.count()
            // Grübler bookkeeping: every pair joins existing links, sliders add one P pair each.
            assertEquals(c.sliderNumbers.size, c.pairs.count { it.prismatic })
            assertEquals(3 * (c.links - 1) - 2 * c.j1, c.dof)
            val linkNumbers = (1..c.links).toSet()
            c.pairs.forEach { p -> assertTrue(p.joins.first in linkNumbers && p.joins.second in linkNumbers && p.joins.first != p.joins.second) }
            // The solver never produces NaN and never stretches a link when it accepts a move.
            val node = m.nodes.random(rnd)
            val dragged = LinkageSolver.drag(m, node.id, node.x + rnd.nextDouble(-1.0, 1.0), node.y + rnd.nextDouble(-1.0, 1.0))
            assertTrue(dragged.nodes.all { it.x.isFinite() && it.y.isFinite() })
            if (dragged != m) assertTrue("drag stretched a link: $m", lengthsKept(dragged))
            m.nodes.filter { it.kind == NodeKind.GROUND }.forEach { g -> assertEquals(g.x, dragged.node(g.id).x, 0.0) }
            LinkageSolver.turn(m, 0.05)?.let { assertTrue("turn stretched a link: $m", lengthsKept(it)) }
        }
    }

    @Test
    fun tenTurnsOfEveryPresetDoNotDrift() {
        for (preset in LinkagePreset.entries) {
            var m = LinkagePresets.build(preset)
            if (m.motor == null) continue
            var direction = 1.0
            var reversals = 0
            repeat(Stress.iterations(3_600)) {
                val next = LinkageSolver.turn(m, direction * 2 * PI / 360)
                if (next == null) {
                    direction = -direction
                    reversals++
                } else {
                    m = next
                }
            }
            assertTrue("$preset drifted", lengthsKept(m, 1e-3))
            m.nodes.filter { it.kind == NodeKind.SLIDER }.forEach { s ->
                assertEquals(0.0, (s.x - s.lineX) * sin(s.lineAngle) - (s.y - s.lineY) * cos(s.lineAngle), 1e-6)
            }
            assertTrue("$preset stuck", reversals < Stress.iterations(3_600) / 2)
        }
    }

    @Test
    fun spatialGruebler() {
        assertEquals(6, Kutzbach.dof(7, mapOf(SpatialPair.R to 6)))
        assertEquals(0, Kutzbach.dof(1, emptyMap()))
        assertEquals(3, Kutzbach.dof(2, mapOf(SpatialPair.S to 1)))
    }
}
