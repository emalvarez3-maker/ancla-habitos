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

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** The math behind the simulators, kept free of Android so it can be unit tested. Angles in radians. */
object RobotMath {
    data class Point(val x: Double, val y: Double)

    /** A planar arm with two revolute joints. */
    data class Arm2R(val l1: Double, val l2: Double) {
        fun elbow(t1: Double) = Point(l1 * cos(t1), l1 * sin(t1))

        /** Forward kinematics: where the tip is for joint angles [t1], [t2]. */
        fun tip(t1: Double, t2: Double) = Point(l1 * cos(t1) + l2 * cos(t1 + t2), l1 * sin(t1) + l2 * sin(t1 + t2))

        val minReach get() = abs(l1 - l2)
        val maxReach get() = l1 + l2

        /**
         * Inverse kinematics by the law of cosines. With y up, a positive θ2 puts the elbow below
         * the line from the base to the tip, so "elbow up" is the negative root. A target out of
         * reach is moved to the nearest reachable point first.
         */
        fun solve(target: Point, elbowUp: Boolean): IkResult {
            val r = hypot(target.x, target.y)
            val reachable = r in minReach..maxReach
            val clamped = when {
                r < 1e-9 -> Point(minReach, 0.0)
                r > maxReach -> Point(target.x / r * maxReach, target.y / r * maxReach)
                r < minReach -> Point(target.x / r * minReach, target.y / r * minReach)
                else -> target
            }
            val c2 = ((clamped.x * clamped.x + clamped.y * clamped.y - l1 * l1 - l2 * l2) / (2 * l1 * l2)).coerceIn(-1.0, 1.0)
            val t2 = acos(c2) * (if (elbowUp) -1 else 1)
            val t1 = atan2(clamped.y, clamped.x) - atan2(l2 * sin(t2), l1 + l2 * cos(t2))
            return IkResult(wrap(t1), t2, c2, reachable, clamped)
        }

        /** Jacobian: tip velocity = J · joint velocities. */
        fun jacobian(t1: Double, t2: Double): Mat2 {
            val s1 = sin(t1)
            val c1 = cos(t1)
            val s12 = sin(t1 + t2)
            val c12 = cos(t1 + t2)
            return Mat2(-l1 * s1 - l2 * s12, -l2 * s12, l1 * c1 + l2 * c12, l2 * c12)
        }
    }

    data class IkResult(val t1: Double, val t2: Double, val cosT2: Double, val reachable: Boolean, val target: Point)

    /** [[a, b], [c, d]] */
    data class Mat2(val a: Double, val b: Double, val c: Double, val d: Double) {
        val det get() = a * d - b * c

        /**
         * The velocity (manipulability) ellipse: the tip velocities reachable with unit joint speeds.
         * Returns the semi-axes (major first) and the major axis' angle, from the eigen-decomposition
         * of J·Jᵀ.
         */
        fun ellipse(): Triple<Double, Double, Double> {
            val p = a * a + b * b // J·Jᵀ = [[p, q], [q, r]]
            val q = a * c + b * d
            val r = c * c + d * d
            val mean = (p + r) / 2
            val diff = sqrt(((p - r) / 2) * ((p - r) / 2) + q * q)
            val major = sqrt(max(mean + diff, 0.0))
            val minor = sqrt(max(mean - diff, 0.0))
            return Triple(major, minor, 0.5 * atan2(2 * q, p - r))
        }
    }

    /** 2D rotation matrix applied to [p]. */
    fun rotate(p: Point, theta: Double) = Point(cos(theta) * p.x - sin(theta) * p.y, sin(theta) * p.x + cos(theta) * p.y)

    /** Homogeneous transform: rotate by [theta], then translate by ([tx], [ty]). */
    fun transform(p: Point, theta: Double, tx: Double, ty: Double) = rotate(p, theta).let { Point(it.x + tx, it.y + ty) }

    fun wrap(a: Double): Double {
        var x = a
        while (x > Math.PI) x -= 2 * Math.PI
        while (x <= -Math.PI) x += 2 * Math.PI
        return x
    }

    // --- PID on a mass-spring-damper ---

    data class Plant(val mass: Double = 1.0, val damping: Double = 1.0, val stiffness: Double = 4.0)

    data class StepResponse(
        val times: List<Double>,
        val positions: List<Double>,
        val reference: Double,
        /** Percent above the final value at the peak, 0 when it never goes above. */
        val overshootPercent: Double,
        /** Time after which it stays within 2 % of its final value, or null if it never settles. */
        val settlingTime: Double?,
        val finalValue: Double,
        val unstable: Boolean
    ) {
        /** What's left between the reference and where it ends up (only a P or PD controller leaves some). */
        val steadyStateError get() = reference - finalValue
    }

    /**
     * Step response of m·x'' + b·x' + k·x = u under u = Kp·e + Ki·∫e + Kd·de/dt, starting at rest
     * at x = 0 with reference [reference]. Semi-implicit Euler with a 1 ms step. Overshoot and
     * settling time are measured against the final value, as in control textbooks.
     */
    fun pidStep(kp: Double, ki: Double, kd: Double, plant: Plant = Plant(), reference: Double = 1.0, duration: Double = 10.0): StepResponse {
        val dt = 0.001
        val every = 20 // sample every 20 ms for the chart
        val steps = (duration / dt).toInt()
        var x = 0.0
        var v = 0.0
        var integral = 0.0
        var previousError = reference - x // no derivative kick at t = 0
        val times = ArrayList<Double>(steps / every + 1)
        val positions = ArrayList<Double>(steps / every + 1)
        times.add(0.0)
        positions.add(x)
        var unstable = false
        for (i in 1..steps) {
            val e = reference - x
            integral += e * dt
            val derivative = (e - previousError) / dt
            previousError = e
            val u = kp * e + ki * integral + kd * derivative
            val accel = (u - plant.damping * v - plant.stiffness * x) / plant.mass
            v += accel * dt
            x += v * dt
            if (!x.isFinite() || abs(x) > 50 * max(1.0, abs(reference))) {
                unstable = true
                break
            }
            if (i % every == 0) {
                times.add(i * dt)
                positions.add(x)
            }
        }
        val final = positions.last()
        val band = 0.02 * max(abs(final), 1e-6)
        val lastOutside = times.indices.lastOrNull { abs(positions[it] - final) > band }?.let { times[it] } ?: 0.0
        val settled = !unstable && abs(final) > 1e-6 && lastOutside < duration - 1.0 // a full second inside the band
        val peak = positions.maxOrNull() ?: 0.0
        return StepResponse(
            times, positions, reference,
            overshootPercent = if (!settled) 0.0 else max(0.0, (peak - final) / final * 100),
            settlingTime = if (settled) lastOutside else null,
            finalValue = final,
            unstable = unstable
        )
    }

    // --- Differential-drive robot ---

    data class Pose(val x: Double, val y: Double, val theta: Double)

    /** Unicycle model: ẋ = v cos θ, ẏ = v sin θ, θ̇ = ω, integrated exactly along the arc. */
    fun drive(p: Pose, v: Double, omega: Double, dt: Double): Pose {
        if (abs(omega) < 1e-9) return Pose(p.x + v * cos(p.theta) * dt, p.y + v * sin(p.theta) * dt, p.theta)
        val theta = p.theta + omega * dt
        val r = v / omega
        return Pose(p.x + r * (sin(theta) - sin(p.theta)), p.y - r * (cos(theta) - cos(p.theta)), wrap(theta))
    }

    /** Right and left wheel speeds for body speeds [v], [omega] and wheel separation [track]. */
    fun wheels(v: Double, omega: Double, track: Double) = (v + omega * track / 2) to (v - omega * track / 2)

    // --- Dynamics: one actuated link (a pendulum) ---

    data class Pendulum(val mass: Double = 1.0, val length: Double = 0.5, val gravity: Double = 9.81) {
        val maxGravityTorque get() = mass * gravity * length
    }

    /** One RK4 step of m·l²·θ̈ + b·θ̇ + m·g·l·sin θ = τ, with θ measured from hanging straight down. */
    fun pendulumStep(theta: Double, omega: Double, torque: Double, damping: Double, p: Pendulum, dt: Double): Pair<Double, Double> {
        fun accel(th: Double, w: Double) = (torque - damping * w - p.mass * p.gravity * p.length * sin(th)) / (p.mass * p.length * p.length)
        val k1t = omega
        val k1w = accel(theta, omega)
        val k2t = omega + k1w * dt / 2
        val k2w = accel(theta + k1t * dt / 2, k2t)
        val k3t = omega + k2w * dt / 2
        val k3w = accel(theta + k2t * dt / 2, k3t)
        val k4t = omega + k3w * dt
        val k4w = accel(theta + k3t * dt, k4t)
        return (theta + dt / 6 * (k1t + 2 * k2t + 2 * k3t + k4t)) to (omega + dt / 6 * (k1w + 2 * k2w + 2 * k3w + k4w))
    }

    // --- Trajectories: from q0 to qf in T seconds, starting and ending at rest ---

    enum class Profile { CUBIC, QUINTIC, TRAPEZOID }

    /** Position and velocity at time [t] for a [profile]; [blend] is the trapezoid's acceleration time (< T/2). */
    fun trajectory(profile: Profile, q0: Double, qf: Double, duration: Double, t: Double, blend: Double = duration / 4): Pair<Double, Double> {
        val d = qf - q0
        val s = (t / duration).coerceIn(0.0, 1.0)
        return when (profile) {
            Profile.CUBIC -> (q0 + d * (3 * s * s - 2 * s * s * s)) to (d * (6 * s - 6 * s * s) / duration)
            Profile.QUINTIC -> (q0 + d * (10 * s * s * s - 15 * s * s * s * s + 6 * s * s * s * s * s)) to (d * (30 * s * s - 60 * s * s * s + 30 * s * s * s * s) / duration)
            Profile.TRAPEZOID -> {
                val ta = blend.coerceIn(1e-6, duration / 2)
                val v = d / (duration - ta)
                val a = v / ta
                val tt = t.coerceIn(0.0, duration)
                when {
                    tt < ta -> (q0 + a * tt * tt / 2) to (a * tt)
                    tt <= duration - ta -> (q0 + a * ta * ta / 2 + v * (tt - ta)) to v
                    else -> {
                        val r = duration - tt
                        (qf - a * r * r / 2) to (a * r)
                    }
                }
            }
        }
    }

    /** Peak speed of each profile, the numbers worked out in the lesson. */
    fun peakSpeed(profile: Profile, distance: Double, duration: Double, blend: Double = duration / 4) = when (profile) {
        Profile.CUBIC -> 1.5 * distance / duration
        Profile.QUINTIC -> 15.0 / 8.0 * distance / duration
        Profile.TRAPEZOID -> distance / (duration - blend.coerceIn(1e-6, duration / 2))
    }

    // --- Sensors ---

    /** Exponential moving average, the simplest low-pass filter: y ← α·z + (1 − α)·y. */
    fun lowPass(samples: List<Double>, alpha: Double): List<Double> {
        if (samples.isEmpty()) return emptyList()
        var y = samples.first()
        return samples.map { z ->
            y = alpha * z + (1 - alpha) * y
            y
        }
    }

    /** Numbers for formulas, with a decimal point whatever the phone's language (TeX expects it). */
    fun fmt(value: Double, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", if (abs(value) < 0.5 * Math.pow(10.0, -decimals.toDouble())) 0.0 else value)

    fun deg(rad: Double) = Math.toDegrees(rad)
}
