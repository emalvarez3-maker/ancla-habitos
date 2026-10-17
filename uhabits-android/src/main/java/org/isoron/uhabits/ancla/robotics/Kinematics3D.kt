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

package org.isoron.uhabits.ancla.robotics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    val length get() = sqrt(x * x + y * y + z * z)
}

/** A homogeneous transform, row-major 4×4. */
class Mat4(val m: DoubleArray) {
    operator fun get(r: Int, c: Int) = m[r * 4 + c]

    operator fun times(o: Mat4): Mat4 {
        val out = DoubleArray(16)
        for (r in 0..3) for (c in 0..3) {
            var s = 0.0
            for (k in 0..3) s += this[r, k] * o[k, c]
            out[r * 4 + c] = s
        }
        return Mat4(out)
    }

    val origin get() = Vec3(this[0, 3], this[1, 3], this[2, 3])
    fun axis(i: Int) = Vec3(this[0, i], this[1, i], this[2, i])

    companion object {
        val IDENTITY = Mat4(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0))

        /** Denavit–Hartenberg: Rot(z, θ) · Trans(z, d) · Trans(x, a) · Rot(x, α). */
        fun dh(theta: Double, d: Double, a: Double, alpha: Double): Mat4 {
            val ct = cos(theta)
            val st = sin(theta)
            val ca = cos(alpha)
            val sa = sin(alpha)
            return Mat4(
                doubleArrayOf(
                    ct, -st * ca, st * sa, a * ct,
                    st, ct * ca, -ct * sa, a * st,
                    0.0, sa, ca, d,
                    0.0, 0.0, 0.0, 1.0
                )
            )
        }

        fun rotation(r: Array<DoubleArray>) = Mat4(
            doubleArrayOf(r[0][0], r[0][1], r[0][2], 0.0, r[1][0], r[1][1], r[1][2], 0.0, r[2][0], r[2][1], r[2][2], 0.0, 0.0, 0.0, 0.0, 1.0)
        )
    }
}

enum class JointType { R, P }

/** One row of a DH table; the joint variable adds to θ (R) or d (P). */
data class DhJoint(val type: JointType, val theta: Double, val d: Double, val a: Double, val alpha: Double, val min: Double, val max: Double, val home: Double)

enum class RobotConfig { CARTESIAN, CYLINDRICAL, POLAR, ANGULAR, SCARA }

object Robots {
    private const val D = PI / 180

    fun joints(config: RobotConfig): List<DhJoint> = when (config) {
        // PPP: three perpendicular prismatic axes.
        RobotConfig.CARTESIAN -> listOf(
            DhJoint(JointType.P, 0.0, 0.0, 0.0, -90 * D, 0.2, 0.9, 0.5),
            DhJoint(JointType.P, -90 * D, 0.0, 0.0, -90 * D, 0.2, 0.9, 0.5),
            DhJoint(JointType.P, 0.0, 0.0, 0.0, 0.0, 0.1, 0.7, 0.4)
        )
        // RPP: base rotation, vertical lift, radial reach.
        RobotConfig.CYLINDRICAL -> listOf(
            DhJoint(JointType.R, 0.0, 0.3, 0.0, 0.0, -170 * D, 170 * D, 30 * D),
            DhJoint(JointType.P, 0.0, 0.0, 0.0, -90 * D, 0.1, 0.8, 0.45),
            DhJoint(JointType.P, 0.0, 0.0, 0.0, 0.0, 0.2, 0.8, 0.5)
        )
        // RRP: base rotation, elevation, telescopic reach.
        RobotConfig.POLAR -> listOf(
            DhJoint(JointType.R, 0.0, 0.5, 0.0, -90 * D, -170 * D, 170 * D, 30 * D),
            DhJoint(JointType.R, 0.0, 0.0, 0.0, 90 * D, 20 * D, 160 * D, 60 * D),
            DhJoint(JointType.P, 0.0, 0.0, 0.0, 0.0, 0.2, 0.8, 0.5)
        )
        // RRR arm with a spherical wrist (6R), as in most industrial robots.
        RobotConfig.ANGULAR -> listOf(
            DhJoint(JointType.R, 0.0, 0.45, 0.0, 90 * D, -170 * D, 170 * D, 20 * D),
            DhJoint(JointType.R, 0.0, 0.0, 0.5, 0.0, -10 * D, 170 * D, 50 * D),
            DhJoint(JointType.R, 0.0, 0.0, 0.0, 90 * D, -80 * D, 200 * D, 70 * D),
            DhJoint(JointType.R, 0.0, 0.45, 0.0, -90 * D, -170 * D, 170 * D, 0.0),
            DhJoint(JointType.R, 0.0, 0.0, 0.0, 90 * D, -120 * D, 120 * D, 40 * D),
            DhJoint(JointType.R, 0.0, 0.12, 0.0, 0.0, -170 * D, 170 * D, 0.0)
        )
        // RRPR: two vertical rotations, a vertical lift and a tool rotation.
        RobotConfig.SCARA -> listOf(
            DhJoint(JointType.R, 0.0, 0.6, 0.5, 0.0, -150 * D, 150 * D, 30 * D),
            DhJoint(JointType.R, 0.0, 0.0, 0.4, 180 * D, -150 * D, 150 * D, 45 * D),
            DhJoint(JointType.P, 0.0, 0.0, 0.0, 0.0, 0.05, 0.45, 0.2),
            DhJoint(JointType.R, 0.0, 0.1, 0.0, 0.0, -170 * D, 170 * D, 0.0)
        )
    }

    /** ⁰Tᵢ for i = 0..n (the base frame first). */
    fun frames(joints: List<DhJoint>, q: List<Double>): List<Mat4> {
        val out = ArrayList<Mat4>(joints.size + 1)
        var t = Mat4.IDENTITY
        out.add(t)
        joints.forEachIndexed { i, j ->
            val a = if (j.type == JointType.R) Mat4.dh(j.theta + q[i], j.d, j.a, j.alpha) else Mat4.dh(j.theta, j.d + q[i], j.a, j.alpha)
            t = t * a
            out.add(t)
        }
        return out
    }

    // --- Orientation ---

    fun rx(a: Double) = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), -sin(a)), doubleArrayOf(0.0, sin(a), cos(a)))
    fun ry(a: Double) = arrayOf(doubleArrayOf(cos(a), 0.0, sin(a)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(a), 0.0, cos(a)))
    fun rz(a: Double) = arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))

    fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { k -> a[r][k] * b[k][c] } } }

    /** Euler ZYZ (moving axes): Rz(φ)·Ry(θ)·Rz(ψ). */
    fun eulerZyz(phi: Double, theta: Double, psi: Double) = mul(mul(rz(phi), ry(theta)), rz(psi))

    /** Roll-pitch-yaw (fixed axes x, y, z): Rz(α)·Ry(β)·Rx(γ). */
    fun rpy(roll: Double, pitch: Double, yaw: Double) = mul(mul(rz(yaw), ry(pitch)), rx(roll))

    /** Unit quaternion (w, x, y, z) of a rotation matrix (Shepperd's method, stable for any angle). */
    fun quaternion(r: Array<DoubleArray>): DoubleArray {
        val tr = r[0][0] + r[1][1] + r[2][2]
        val q = when {
            tr > 0 -> {
                val s = sqrt(tr + 1.0) * 2
                doubleArrayOf(0.25 * s, (r[2][1] - r[1][2]) / s, (r[0][2] - r[2][0]) / s, (r[1][0] - r[0][1]) / s)
            }
            r[0][0] > r[1][1] && r[0][0] > r[2][2] -> {
                val s = sqrt(1.0 + r[0][0] - r[1][1] - r[2][2]) * 2
                doubleArrayOf((r[2][1] - r[1][2]) / s, 0.25 * s, (r[0][1] + r[1][0]) / s, (r[0][2] + r[2][0]) / s)
            }
            r[1][1] > r[2][2] -> {
                val s = sqrt(1.0 + r[1][1] - r[0][0] - r[2][2]) * 2
                doubleArrayOf((r[0][2] - r[2][0]) / s, (r[0][1] + r[1][0]) / s, 0.25 * s, (r[1][2] + r[2][1]) / s)
            }
            else -> {
                val s = sqrt(1.0 + r[2][2] - r[0][0] - r[1][1]) * 2
                doubleArrayOf((r[1][0] - r[0][1]) / s, (r[0][2] + r[2][0]) / s, (r[1][2] + r[2][1]) / s, 0.25 * s)
            }
        }
        // Same rotation either sign; show the one with w ≥ 0.
        return if (q[0] < 0) DoubleArray(4) { -q[it] } else q
    }
}

/** Spatial pairs for the Grübler–Kutzbach calculator: name and degrees of freedom. */
enum class SpatialPair(val dof: Int) { R(1), P(1), H(1), C(2), U(2), S(3) }

object Kutzbach {
    fun dof(links: Int, pairs: Map<SpatialPair, Int>) = 6 * (links - 1) - pairs.entries.sumOf { (p, n) -> (6 - p.dof) * n }

    /** Classic spatial examples: links and pairs. */
    val EXAMPLES: Map<String, Pair<Int, Map<SpatialPair, Int>>> = linkedMapOf(
        "Robot 6R" to (7 to mapOf(SpatialPair.R to 6)),
        "SCARA" to (5 to mapOf(SpatialPair.R to 3, SpatialPair.P to 1)),
        "Stewart" to (14 to mapOf(SpatialPair.U to 6, SpatialPair.P to 6, SpatialPair.S to 6)),
        "Delta" to (11 to mapOf(SpatialPair.R to 3, SpatialPair.S to 12)),
        "RSSR" to (4 to mapOf(SpatialPair.R to 2, SpatialPair.S to 2)),
        "4 barras 3D" to (4 to mapOf(SpatialPair.R to 4))
    )
}
