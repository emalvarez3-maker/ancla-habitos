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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.reading.MathFormula
import org.isoron.uhabits.ancla.reading.chartPalette
import org.isoron.uhabits.ancla.robotics.RobotMath.Profile
import org.isoron.uhabits.ancla.robotics.RobotMath.fmt
import org.isoron.uhabits.ancla.ui.Ancla
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// --- Dynamics: an actuated link under gravity ---

@Composable
fun PendulumSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    val p = remember { RobotMath.Pendulum() }
    var torque by remember { mutableFloatStateOf(0f) }
    var damping by remember { mutableFloatStateOf(0.3f) }
    var theta by remember { mutableDoubleStateOf(Math.toRadians(70.0)) }
    var omega by remember { mutableDoubleStateOf(0.0) }
    var running by remember { mutableStateOf(true) }
    var history by remember { mutableStateOf(listOf<Pair<Double, Double>>()) } // time, angle in degrees
    var clock by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(running) {
        var last = 0L
        while (running) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9).coerceAtMost(0.05)
                    var th = theta
                    var w = omega
                    val steps = 20
                    repeat(steps) { RobotMath.pendulumStep(th, w, torque.toDouble(), damping.toDouble(), p, dt / steps).let { th = it.first; w = it.second } }
                    theta = th
                    omega = w
                    clock += dt
                    history = (history + (clock to Math.toDegrees(RobotMath.wrap(th)))).dropWhile { it.first < clock - WINDOW }
                }
                last = now
            }
        }
    }
    val gravity = p.maxGravityTorque * sin(theta)
    SimCard {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f).semantics { contentDescription = "Eslabón a ${Math.toDegrees(theta).toInt()}° de la vertical" }) {
            val pivot = Offset(size.width / 2, size.height * 0.42f)
            val r = minOf(size.width, size.height) * 0.36f
            drawCircle(c.track, r, pivot, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
            drawLine(c.muted, pivot, Offset(pivot.x, pivot.y + r * 1.1f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val bob = Offset(pivot.x + r * sin(theta).toFloat(), pivot.y + r * cos(theta).toFloat())
            drawLine(palette[0], pivot, bob, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(palette[0], 16.dp.toPx(), bob)
            // Gravity on the mass, and the motor torque as an arc whose size follows τ.
            drawLine(palette[1], bob, Offset(bob.x, bob.y + 40.dp.toPx()), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(c.ink, 7.dp.toPx(), pivot)
            if (abs(torque) > 0.05f) {
                val sweep = (torque / 8f) * 160f
                val ar = 30.dp.toPx()
                drawArc(c.lilac, 90f - sweep / 2, sweep, false, Offset(pivot.x - ar, pivot.y - ar), Size(2 * ar, 2 * ar), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        LineChart(
            listOf(ChartSeries(stringResource(R.string.robotics_series_angle), history.map { it.first - maxOf(0.0, clock - WINDOW) }, history.map { it.second }, palette[0])),
            xMax = WINDOW, yMin = -180.0, yMax = 180.0, xUnit = "s",
            description = "Ángulo en los últimos ${WINDOW.toInt()} s"
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardChip(stringResource(if (running) R.string.robotics_pause else R.string.robotics_play), running) { running = !running }
            CardChip(stringResource(R.string.robotics_release), false) {
                theta = Math.toRadians(70.0)
                omega = 0.0
                history = emptyList()
            }
            CardChip(stringResource(R.string.robotics_hold_horizontal), false) { torque = p.maxGravityTorque.toFloat() }
        }
        LabeledSlider("τ", torque, -8f..8f, "${fmt(torque.toDouble())} N·m") { torque = it }
        LabeledSlider("b", damping, 0f..2f, "${fmt(damping.toDouble())} N·m·s") { damping = it }
        val holds = abs(torque) <= p.maxGravityTorque
        val equilibrium = if (holds) "\\theta_{eq} = \\arcsin\\frac{\\tau}{mgl} = ${fmt(Math.toDegrees(asin(torque / p.maxGravityTorque)), 1)}^\\circ" else "|\\tau| > mgl:\\ \\text{gira sin parar}"
        MathFormula(
            "\\begin{aligned} ml^2\\ddot\\theta + b\\dot\\theta + mgl\\sin\\theta &= \\tau \\\\ " +
                "${fmt(p.mass * p.length * p.length)}\\,\\ddot\\theta + ${fmt(damping.toDouble())}\\,\\dot\\theta + ${fmt(gravity)} &= ${fmt(torque.toDouble())} \\\\ " +
                "$equilibrium \\end{aligned}"
        )
        Hint(stringResource(R.string.robotics_pendulum_hint, fmt(p.maxGravityTorque)))
    }
}

private const val WINDOW = 10.0

// --- Trajectories ---

@Composable
fun TrajectorySimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    var q0 by remember { mutableFloatStateOf(0f) }
    var qf by remember { mutableFloatStateOf(90f) }
    var duration by remember { mutableFloatStateOf(2f) }
    var blend by remember { mutableFloatStateOf(0.25f) } // fraction of T
    var playing by remember { mutableStateOf(false) }
    var t by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(playing) {
        var last = 0L
        while (playing) {
            withFrameNanos { now ->
                if (last != 0L) {
                    t += (now - last) / 1e9
                    if (t > duration + 0.8) t = 0.0 // a short pause at the end, then again
                }
                last = now
            }
        }
    }
    val T = duration.toDouble()
    val ta = blend * T
    val profiles = Profile.entries
    val names = listOf(stringResource(R.string.robotics_profile_cubic), stringResource(R.string.robotics_profile_quintic), stringResource(R.string.robotics_profile_trapezoid))
    val times = (0..200).map { it * T / 200 }
    val curves = profiles.map { pr -> times.map { RobotMath.trajectory(pr, q0.toDouble(), qf.toDouble(), T, it, ta) } }
    val lo = minOf(q0, qf).toDouble()
    val hi = maxOf(q0, qf).toDouble()
    val vMax = profiles.maxOf { abs(RobotMath.peakSpeed(it, (qf - q0).toDouble(), T, ta)) }
    SimCard {
        // Three arms moving side by side, one per profile.
        Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = "Tres eslabones moviéndose con perfiles cúbico, quíntico y trapezoidal" }) {
            val slot = size.width / 3
            profiles.forEachIndexed { i, pr ->
                val (q, _) = RobotMath.trajectory(pr, q0.toDouble(), qf.toDouble(), T, t.coerceAtMost(T), ta)
                val pivot = Offset(slot * i + slot / 2, size.height * 0.85f)
                val len = minOf(slot * 0.42f, size.height * 0.72f)
                val a = Math.toRadians(q)
                drawArc(c.track, 180f, 180f, false, Offset(pivot.x - len, pivot.y - len), Size(2 * len, 2 * len), style = Stroke(1.dp.toPx()))
                drawLine(palette[i], pivot, Offset(pivot.x + len * cos(a).toFloat(), pivot.y - len * sin(a).toFloat()), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(c.ink, 5.dp.toPx(), pivot)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CardChip(stringResource(if (playing) R.string.robotics_pause else R.string.robotics_play), playing) { playing = !playing }
            Text("t = ${fmt(t.coerceAtMost(T), 1)} s", style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        }
        Text(stringResource(R.string.robotics_position), style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        LineChart(
            profiles.indices.map { ChartSeries(names[it], times, curves[it].map { p -> p.first }, palette[it]) },
            xMax = T, yMin = lo - 5, yMax = max(hi + 5, lo + 10), xUnit = "s",
            description = "Posición de los tres perfiles"
        )
        Text(stringResource(R.string.robotics_velocity), style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        LineChart(
            profiles.indices.map { ChartSeries(names[it], times, curves[it].map { p -> p.second }, palette[it]) },
            xMax = T, yMin = if (qf < q0) -vMax * 1.1 else 0.0, yMax = if (qf < q0) 0.0 else max(vMax * 1.1, 1.0), xUnit = "s",
            description = "Velocidad de los tres perfiles"
        )
        LabeledSlider("q₀", q0, -90f..90f, "${q0.toInt()}°") { q0 = it }
        LabeledSlider("q final", qf, -90f..180f, "${qf.toInt()}°") { qf = it }
        LabeledSlider("T", duration, 0.5f..5f, "${fmt(duration.toDouble(), 1)} s") { duration = it; t = 0.0 }
        LabeledSlider("tₐ / T", blend, 0.05f..0.5f, fmt(blend.toDouble())) { blend = it }
        val d = (qf - q0).toDouble()
        MathFormula(
            "\\dot q_{max}:\\quad \\text{cúbica } \\tfrac{3\\Delta q}{2T} = ${fmt(RobotMath.peakSpeed(Profile.CUBIC, d, T), 1)},\\quad " +
                "\\text{quíntica } \\tfrac{15\\Delta q}{8T} = ${fmt(RobotMath.peakSpeed(Profile.QUINTIC, d, T), 1)},\\quad " +
                "\\text{trapecio } \\tfrac{\\Delta q}{T - t_a} = ${fmt(RobotMath.peakSpeed(Profile.TRAPEZOID, d, T, ta), 1)}\\ ^\\circ/\\text{s}"
        )
    }
}
