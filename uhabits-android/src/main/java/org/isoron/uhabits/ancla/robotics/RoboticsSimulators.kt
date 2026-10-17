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
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.reading.MathFormula
import org.isoron.uhabits.ancla.reading.chartPalette
import org.isoron.uhabits.ancla.robotics.RobotMath.Point
import org.isoron.uhabits.ancla.robotics.RobotMath.deg
import org.isoron.uhabits.ancla.robotics.RobotMath.fmt
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Which interactive figure a lesson shows. */
enum class Simulator { ARM_FORWARD, MECHANISM, ROBOTS_3D, TRANSFORM, ORIENTATION_3D, ARM_DH, ARM_INVERSE, JACOBIAN, PENDULUM, TRAJECTORY, DIFF_DRIVE, PID, SENSORS }

@Composable
fun SimulatorFor(sim: Simulator) = when (sim) {
    Simulator.ARM_FORWARD -> ArmSimulator(ArmFormula.FORWARD)
    Simulator.ARM_DH -> ArmSimulator(ArmFormula.DH)
    Simulator.ARM_INVERSE -> ArmSimulator(ArmFormula.INVERSE)
    Simulator.JACOBIAN -> ArmSimulator(ArmFormula.JACOBIAN)
    Simulator.TRANSFORM -> TransformSimulator()
    Simulator.DIFF_DRIVE -> DiffDriveSimulator()
    Simulator.PID -> PidSimulator()
    Simulator.SENSORS -> SensorSimulator()
    Simulator.MECHANISM -> MechanismWorkshop()
    Simulator.ROBOTS_3D -> Robots3DSimulator()
    Simulator.ORIENTATION_3D -> Orientation3DSimulator()
    Simulator.PENDULUM -> PendulumSimulator()
    Simulator.TRAJECTORY -> TrajectorySimulator()
}

// --- Shared pieces ---

@Composable
internal fun SimCard(content: @Composable ColumnScope.() -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

@Composable
internal fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: String, onChange: (Float) -> Unit) {
    val c = Ancla.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.inkSoft, modifier = Modifier.weight(1f))
            Text(shown, style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.lilac, inactiveTrackColor = c.track),
            modifier = Modifier.height(36.dp)
        )
    }
}

@Composable
internal fun Metric(value: String, label: String, modifier: Modifier) {
    val c = Ancla.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.ground).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/** A chip for use on a card: on the card's own color the regular chip would disappear. */
@Composable
internal fun CardChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.ground)
            .combinedClickableCompat(Role.Button, onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.labelMedium, color = Ancla.colors.muted)

/** World ↔ screen for the planar figures: origin at the center, y up, [half] meters to each side. */
private class Viewport(val size: Size, half: Double) {
    val scale = (min(size.width, size.height) / 2f) / half.toFloat()
    val center = Offset(size.width / 2f, size.height / 2f)
    fun toScreen(p: Point) = Offset(center.x + p.x.toFloat() * scale, center.y - p.y.toFloat() * scale)
    fun toWorld(o: Offset) = Point(((o.x - center.x) / scale).toDouble(), ((center.y - o.y) / scale).toDouble())
}

private fun DrawScope.grid(v: Viewport, half: Int, line: Color, axis: Color) {
    for (i in -half..half) {
        val col = if (i == 0) axis else line
        drawLine(col, v.toScreen(Point(i.toDouble(), -half.toDouble())), v.toScreen(Point(i.toDouble(), half.toDouble())), strokeWidth = 1.dp.toPx())
        drawLine(col, v.toScreen(Point(-half.toDouble(), i.toDouble())), v.toScreen(Point(half.toDouble(), i.toDouble())), strokeWidth = 1.dp.toPx())
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    val t = measurer.measure(text, style)
    drawText(t, topLeft = Offset(at.x - t.size.width / 2f, at.y - t.size.height / 2f))
}

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, width: Float) {
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len
    val uy = dy / len
    val head = 9.dp.toPx()
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - ux * head - uy * head * 0.5f, to.y - uy * head + ux * head * 0.5f)
        lineTo(to.x - ux * head + uy * head * 0.5f, to.y - uy * head - ux * head * 0.5f)
        close()
    }
    drawPath(path, color)
}

data class ChartSeries(val name: String, val xs: List<Double>, val ys: List<Double>, val color: Color, val dashed: Boolean = false, val width: Dp = 2.5.dp)

/**
 * A line chart with one shared y scale (never two axes), light guides at the bottom, middle and
 * top of the scale, and a legend whenever there are two or more series.
 */
@Composable
fun LineChart(series: List<ChartSeries>, xMax: Double, yMin: Double, yMax: Double, xUnit: String, description: String, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = c.muted)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = description }) {
            val left = 40.dp.toPx()
            val bottom = 22.dp.toPx()
            val top = 8.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom - top
            fun x(t: Double) = left + (t / xMax).toFloat() * w
            fun y(v: Double) = top + h - ((v - yMin) / (yMax - yMin)).toFloat() * h
            listOf(yMin, (yMin + yMax) / 2, yMax).forEach { g ->
                drawLine(c.track, Offset(left, y(g)), Offset(size.width, y(g)), strokeWidth = 1.dp.toPx())
                val t = measurer.measure(fmt(g, 1), labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(g) - t.size.height / 2f))
            }
            if (yMin < 0 && yMax > 0) drawLine(c.muted.copy(alpha = 0.5f), Offset(left, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
            listOf(0.0, xMax / 2, xMax).forEachIndexed { i, g ->
                val t = measurer.measure(fmt(g, 0) + if (i == 2) " $xUnit" else "", labelStyle)
                val px = (x(g) - if (i == 0) 0f else if (i == 1) t.size.width / 2f else t.size.width.toFloat()).coerceAtLeast(left)
                drawText(t, topLeft = Offset(px, size.height - t.size.height))
            }
            clipRect(left, top - 2.dp.toPx(), size.width, top + h + 2.dp.toPx()) {
                series.forEach { s ->
                    val path = Path()
                    s.xs.indices.forEach { i ->
                        val px = x(s.xs[i])
                        val py = y(s.ys[i].coerceIn(yMin - (yMax - yMin), yMax + (yMax - yMin)))
                        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    drawPath(
                        path, s.color,
                        style = Stroke(
                            width = s.width.toPx(), cap = StrokeCap.Round,
                            pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())) else null
                        )
                    )
                }
            }
        }
        if (series.size >= 2) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                series.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Canvas(Modifier.width(18.dp).height(10.dp)) {
                            drawLine(
                                s.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx(),
                                pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null
                            )
                        }
                        Text(s.name, style = MaterialTheme.typography.labelMedium, color = c.inkSoft)
                    }
                }
            }
        }
    }
}

// --- 2R arm: forward, Denavit–Hartenberg, inverse kinematics and the Jacobian ---

enum class ArmFormula { FORWARD, DH, INVERSE, JACOBIAN }

@Composable
fun ArmSimulator(formula: ArmFormula) {
    val c = Ancla.colors
    val palette = chartPalette()
    val measurer = rememberTextMeasurer()
    var t1 by remember { mutableFloatStateOf(70f) }
    var t2 by remember { mutableFloatStateOf(-60f) } // elbow up, matching the chip selected at start
    var l1 by remember { mutableFloatStateOf(1.0f) }
    var l2 by remember { mutableFloatStateOf(0.7f) }
    var elbowUp by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<Point?>(null) }
    var reachable by remember { mutableStateOf(true) }
    val arm = RobotMath.Arm2R(l1.toDouble(), l2.toDouble())
    val r1 = Math.toRadians(t1.toDouble())
    val r2 = Math.toRadians(t2.toDouble())
    val elbow = arm.elbow(r1)
    val tip = arm.tip(r1, r2)

    fun aim(p: Point) {
        val ik = arm.solve(p, elbowUp)
        target = p
        reachable = ik.reachable
        t1 = deg(ik.t1).toFloat()
        t2 = deg(ik.t2).toFloat()
    }

    SimCard {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .semantics { contentDescription = "Brazo 2R: θ1 ${t1.toInt()}°, θ2 ${t2.toInt()}°, punta en (${fmt(tip.x)}, ${fmt(tip.y)}) m" }
                .pointerInput(l1, l2, elbowUp) {
                    detectTapGestures { o -> aim(Viewport(Size(size.width.toFloat(), size.height.toFloat()), HALF).toWorld(o)) }
                }
                .pointerInput(l1, l2, elbowUp) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        aim(Viewport(Size(size.width.toFloat(), size.height.toFloat()), HALF).toWorld(change.position))
                    }
                }
        ) {
            val v = Viewport(size, HALF)
            grid(v, 2, c.track.copy(alpha = 0.6f), c.track)
            // Workspace: the ring the tip can reach.
            drawCircle(c.lilac.copy(alpha = 0.10f), radius = (arm.maxReach * v.scale).toFloat(), center = v.center)
            drawCircle(c.lilac.copy(alpha = 0.45f), radius = (arm.maxReach * v.scale).toFloat(), center = v.center, style = Stroke(1.dp.toPx()))
            if (arm.minReach > 0.01) drawCircle(c.lilac.copy(alpha = 0.45f), radius = (arm.minReach * v.scale).toFloat(), center = v.center, style = Stroke(1.dp.toPx()))

            if (formula == ArmFormula.JACOBIAN) {
                // Velocity ellipse at the tip: the directions it can move fast or barely move.
                val (major, minor, angle) = arm.jacobian(r1, r2).ellipse()
                val k = 0.35f * v.scale
                val center = v.toScreen(tip)
                rotate(-deg(angle).toFloat(), center) {
                    val sz = Size((2 * major * k).toFloat(), max((2 * minor * k).toFloat(), 1.5.dp.toPx()))
                    drawOval(palette[2].copy(alpha = 0.22f), Offset(center.x - sz.width / 2, center.y - sz.height / 2), sz)
                    drawOval(palette[2], Offset(center.x - sz.width / 2, center.y - sz.height / 2), sz, style = Stroke(2.dp.toPx()))
                }
            }
            val base = v.toScreen(Point(0.0, 0.0))
            val e = v.toScreen(elbow)
            val p = v.toScreen(tip)
            drawLine(palette[0], base, e, strokeWidth = 10.dp.toPx(), cap = StrokeCap.Round)
            drawLine(palette[1], e, p, strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(c.ink, 7.dp.toPx(), base)
            drawCircle(c.card, 3.dp.toPx(), base)
            drawCircle(c.ink, 6.dp.toPx(), e)
            drawCircle(c.card, 2.5.dp.toPx(), e)
            drawCircle(c.ink, 5.dp.toPx(), p)
            target?.let { t ->
                val o = v.toScreen(t)
                val s = 8.dp.toPx()
                val col = if (reachable) c.ink else palette[1]
                drawLine(col, Offset(o.x - s, o.y), Offset(o.x + s, o.y), strokeWidth = 2.dp.toPx())
                drawLine(col, Offset(o.x, o.y - s), Offset(o.x, o.y + s), strokeWidth = 2.dp.toPx())
            }
            val style = TextStyle(color = c.inkSoft, fontSize = LABEL_SIZE)
            label(measurer, "L₁", Offset((base.x + e.x) / 2, (base.y + e.y) / 2) + Offset(0f, -14.dp.toPx()), style)
            label(measurer, "L₂", Offset((e.x + p.x) / 2, (e.y + p.y) / 2) + Offset(0f, -14.dp.toPx()), style)
        }
        Hint(stringResource(if (target != null && !reachable) R.string.robotics_unreachable else R.string.robotics_tap_target))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Switching elbows keeps the tip where it is and flips the arm to the other solution.
            CardChip(stringResource(R.string.robotics_elbow_up), elbowUp) {
                elbowUp = true
                aim(target ?: tip)
            }
            CardChip(stringResource(R.string.robotics_elbow_down), !elbowUp) {
                elbowUp = false
                aim(target ?: tip)
            }
        }
        LabeledSlider("θ₁", t1, -180f..180f, "${t1.toInt()}°") { t1 = it; target = null }
        LabeledSlider("θ₂", t2, -180f..180f, "${t2.toInt()}°") { t2 = it; target = null }
        LabeledSlider("L₁", l1, 0.3f..1.0f, "${fmt(l1.toDouble())} m") { l1 = it; target = null }
        LabeledSlider("L₂", l2, 0.3f..1.0f, "${fmt(l2.toDouble())} m") { l2 = it; target = null }

        val a1 = "${t1.toInt()}^\\circ"
        val a2 = "${t2.toInt()}^\\circ"
        val a12 = "${(t1 + t2).toInt()}^\\circ"
        val L1 = fmt(l1.toDouble())
        val L2 = fmt(l2.toDouble())
        val tex = when (formula) {
            ArmFormula.FORWARD ->
                "\\begin{aligned} x &= L_1\\cos\\theta_1 + L_2\\cos(\\theta_1+\\theta_2) = $L1\\cos $a1 + $L2\\cos $a12 = ${fmt(tip.x)}\\,\\text{m} \\\\ " +
                    "y &= L_1\\sin\\theta_1 + L_2\\sin(\\theta_1+\\theta_2) = $L1\\sin $a1 + $L2\\sin $a12 = ${fmt(tip.y)}\\,\\text{m} \\end{aligned}"
            ArmFormula.DH -> {
                val c12 = fmt(cos(r1 + r2))
                val s12 = fmt(sin(r1 + r2))
                "{}^0T_2 = A_1(\\theta_1)\\,A_2(\\theta_2) = \\begin{bmatrix} $c12 & ${fmt(-sin(r1 + r2))} & ${fmt(tip.x)} \\\\ $s12 & $c12 & ${fmt(tip.y)} \\\\ 0 & 0 & 1 \\end{bmatrix}"
            }
            ArmFormula.INVERSE -> {
                val ik = arm.solve(target ?: tip, elbowUp)
                val goal = ik.target
                "\\begin{aligned} \\cos\\theta_2 &= \\frac{x^2+y^2-L_1^2-L_2^2}{2L_1L_2} = \\frac{${signed(goal.x)}^2+${signed(goal.y)}^2-$L1^2-$L2^2}{2\\cdot $L1\\cdot $L2} = ${fmt(ik.cosT2)} \\\\ " +
                    "\\theta_2 &= ${if (elbowUp) "-" else "+"}\\arccos(${fmt(ik.cosT2)}) = ${deg(ik.t2).toInt()}^\\circ \\\\ " +
                    "\\theta_1 &= \\operatorname{atan2}(y,x) - \\operatorname{atan2}(L_2\\sin\\theta_2,\\ L_1+L_2\\cos\\theta_2) = ${deg(ik.t1).toInt()}^\\circ \\end{aligned}"
            }
            ArmFormula.JACOBIAN -> {
                val j = arm.jacobian(r1, r2)
                "J = \\begin{bmatrix} -L_1 s_1 - L_2 s_{12} & -L_2 s_{12} \\\\ L_1 c_1 + L_2 c_{12} & L_2 c_{12} \\end{bmatrix} = " +
                    "\\begin{bmatrix} ${fmt(j.a)} & ${fmt(j.b)} \\\\ ${fmt(j.c)} & ${fmt(j.d)} \\end{bmatrix},\\quad \\det J = L_1L_2\\sin\\theta_2 = ${fmt(j.det, 3)}"
            }
        }
        MathFormula(tex)
        if (formula == ArmFormula.JACOBIAN) Hint(stringResource(R.string.robotics_ellipse_hint))
    }
}

private const val HALF = 2.1

/** A number to square in a formula: negative ones in parentheses, so (−0.5)² isn't read as −0.5². */
private fun signed(x: Double) = fmt(x).let { if (it.startsWith("-")) "($it)" else it }
private val LABEL_SIZE = 13.sp

// --- Rotation and homogeneous transform ---

@Composable
fun TransformSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    val measurer = rememberTextMeasurer()
    var angle by remember { mutableFloatStateOf(30f) }
    var tx by remember { mutableFloatStateOf(0.6f) }
    var ty by remember { mutableFloatStateOf(0.3f) }
    val th = Math.toRadians(angle.toDouble())
    val local = Point(1.0, 0.5)
    val moved = RobotMath.transform(local, th, tx.toDouble(), ty.toDouble())
    SimCard {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .semantics { contentDescription = "Marco {1} girado ${angle.toInt()}° y trasladado (${fmt(tx.toDouble())}, ${fmt(ty.toDouble())}); P pasa a (${fmt(moved.x)}, ${fmt(moved.y)})" }
        ) {
            val v = Viewport(size, 2.2)
            grid(v, 2, c.track.copy(alpha = 0.6f), c.track)
            val o0 = v.toScreen(Point(0.0, 0.0))
            val w = 2.dp.toPx()
            arrow(o0, v.toScreen(Point(1.0, 0.0)), c.muted, w)
            arrow(o0, v.toScreen(Point(0.0, 1.0)), c.muted, w)
            val style = TextStyle(color = c.muted, fontSize = LABEL_SIZE)
            label(measurer, "{0}", o0 + Offset(-14.dp.toPx(), 14.dp.toPx()), style)
            // Frame {1}: rotated, then translated.
            val o1 = v.toScreen(Point(tx.toDouble(), ty.toDouble()))
            val x1 = v.toScreen(RobotMath.transform(Point(1.0, 0.0), th, tx.toDouble(), ty.toDouble()))
            val y1 = v.toScreen(RobotMath.transform(Point(0.0, 1.0), th, tx.toDouble(), ty.toDouble()))
            // Same axis colors as the 3D views: x orange, y green.
            arrow(o1, x1, palette[1], 3.dp.toPx())
            arrow(o1, y1, palette[2], 3.dp.toPx())
            label(measurer, "x₁", x1 + Offset(10.dp.toPx(), 0f), TextStyle(color = palette[1], fontSize = LABEL_SIZE))
            label(measurer, "y₁", y1 + Offset(10.dp.toPx(), 0f), TextStyle(color = palette[2], fontSize = LABEL_SIZE))
            label(measurer, "{1}", o1 + Offset(-14.dp.toPx(), 14.dp.toPx()), TextStyle(color = c.ink, fontSize = LABEL_SIZE))
            // P drawn in frame {0} at its local coordinates (faint) and where the transform puts it.
            drawCircle(c.muted, 5.dp.toPx(), v.toScreen(local), style = Stroke(1.5.dp.toPx()))
            drawLine(c.muted.copy(alpha = 0.5f), v.toScreen(local), v.toScreen(moved), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(c.ink, 6.dp.toPx(), v.toScreen(moved))
            label(measurer, "P", v.toScreen(moved) + Offset(12.dp.toPx(), -10.dp.toPx()), TextStyle(color = c.ink, fontSize = LABEL_SIZE))
        }
        Hint(stringResource(R.string.robotics_transform_hint))
        LabeledSlider("θ", angle, -180f..180f, "${angle.toInt()}°") { angle = it }
        LabeledSlider("tx", tx, -1.5f..1.5f, "${fmt(tx.toDouble())} m") { tx = it }
        LabeledSlider("ty", ty, -1.5f..1.5f, "${fmt(ty.toDouble())} m") { ty = it }
        val cs = fmt(cos(th))
        val sn = fmt(sin(th))
        MathFormula(
            "\\begin{bmatrix} x' \\\\ y' \\\\ 1 \\end{bmatrix} = " +
                "\\begin{bmatrix} \\cos\\theta & -\\sin\\theta & t_x \\\\ \\sin\\theta & \\cos\\theta & t_y \\\\ 0 & 0 & 1 \\end{bmatrix}" +
                "\\begin{bmatrix} 1 \\\\ 0.5 \\\\ 1 \\end{bmatrix} = " +
                "\\begin{bmatrix} $cs & ${fmt(-sin(th))} & ${fmt(tx.toDouble())} \\\\ $sn & $cs & ${fmt(ty.toDouble())} \\\\ 0 & 0 & 1 \\end{bmatrix}" +
                "\\begin{bmatrix} 1 \\\\ 0.5 \\\\ 1 \\end{bmatrix} = \\begin{bmatrix} ${fmt(moved.x)} \\\\ ${fmt(moved.y)} \\\\ 1 \\end{bmatrix}"
        )
    }
}

// --- PID on a mass-spring-damper ---

@Composable
fun PidSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    var kp by remember { mutableFloatStateOf(20f) }
    var ki by remember { mutableFloatStateOf(8f) }
    var kd by remember { mutableFloatStateOf(3f) }
    val plant = RobotMath.Plant()
    val r = remember(kp, ki, kd) { RobotMath.pidStep(kp.toDouble(), ki.toDouble(), kd.toDouble(), plant, duration = DURATION) }
    val peak = r.positions.maxOrNull() ?: 1.0
    val low = r.positions.minOrNull() ?: 0.0
    val yMax = min(3.0, max(1.2, ceil(peak * 1.1 * 5) / 5))
    val yMin = max(-1.0, min(0.0, floor(low * 5) / 5))
    val position = stringResource(R.string.robotics_series_position)
    val reference = stringResource(R.string.robotics_series_reference)
    SimCard {
        LineChart(
            listOf(
                ChartSeries(reference, listOf(0.0, DURATION), listOf(1.0, 1.0), palette[1], dashed = true, width = 2.dp),
                ChartSeries(position, r.times, r.positions, palette[0])
            ),
            xMax = DURATION, yMin = yMin, yMax = yMax, xUnit = "s",
            description = "Respuesta a escalón: sobrepaso ${fmt(r.overshootPercent, 0)} %, valor final ${fmt(r.finalValue)}"
        )
        if (r.unstable) {
            Text(stringResource(R.string.robotics_unstable), style = MaterialTheme.typography.bodyMedium, color = palette[1])
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("${fmt(r.overshootPercent, 0)} %", stringResource(R.string.robotics_overshoot), Modifier.weight(1f))
                Metric(r.settlingTime?.let { "${fmt(it, 1)} s" } ?: "—", stringResource(R.string.robotics_settling), Modifier.weight(1f))
                Metric(fmt(r.steadyStateError), stringResource(R.string.robotics_steady_error), Modifier.weight(1f))
            }
            if (r.settlingTime == null) Hint(stringResource(R.string.robotics_not_settled))
        }
        LabeledSlider("Kp", kp, 0f..60f, fmt(kp.toDouble(), 1)) { kp = it }
        LabeledSlider("Ki", ki, 0f..40f, fmt(ki.toDouble(), 1)) { ki = it }
        LabeledSlider("Kd", kd, 0f..15f, fmt(kd.toDouble(), 1)) { kd = it }
        val lhs = (plant.damping + kd) * (plant.stiffness + kp)
        val stable = lhs > ki
        MathFormula(
            "\\begin{aligned} u(t) &= K_p\\,e + K_i\\!\\int e\\,dt + K_d\\,\\dot e = ${fmt(kp.toDouble(), 1)}\\,e + ${fmt(ki.toDouble(), 1)}\\!\\int e\\,dt + ${fmt(kd.toDouble(), 1)}\\,\\dot e \\\\ " +
                "m\\ddot x + b\\dot x + kx &= u,\\quad m = ${fmt(plant.mass, 0)},\\ b = ${fmt(plant.damping, 0)},\\ k = ${fmt(plant.stiffness, 0)} \\\\ " +
                "(b+K_d)(k+K_p) &= ${fmt(lhs, 1)} ${if (stable) ">" else "\\le"} K_i = ${fmt(ki.toDouble(), 1)} \\;\\Rightarrow\\; \\text{${if (stable) "estable" else "inestable"}} \\end{aligned}"
        )
    }
}

private const val DURATION = 10.0

// --- Differential-drive robot ---

@Composable
fun DiffDriveSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    var v by remember { mutableFloatStateOf(0.5f) }
    var w by remember { mutableFloatStateOf(0.6f) }
    var running by remember { mutableStateOf(false) }
    var pose by remember { mutableStateOf(RobotMath.Pose(0.0, -1.0, 0.0)) }
    val trail = remember { mutableStateListOf<Point?>(Point(0.0, -1.0)) }
    LaunchedEffect(running) {
        var last = 0L
        while (running) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1e9).coerceAtMost(0.05)
                    var next = RobotMath.drive(pose, v.toDouble(), w.toDouble(), dt)
                    // Leaving the arena: come back from the other side, without a line across.
                    if (abs(next.x) > ARENA || abs(next.y) > ARENA) {
                        next = next.copy(x = wrapArena(next.x), y = wrapArena(next.y))
                        trail.add(null)
                    }
                    pose = next
                    trail.add(Point(next.x, next.y))
                    while (trail.size > 900) trail.removeAt(0)
                }
                last = now
            }
        }
    }
    val (right, left) = RobotMath.wheels(v.toDouble(), w.toDouble(), TRACK)
    SimCard {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "Robot en (${fmt(pose.x)}, ${fmt(pose.y)}) m mirando ${deg(pose.theta).toInt()}°" }) {
            val vp = Viewport(size, ARENA)
            grid(vp, ARENA.toInt(), c.track.copy(alpha = 0.6f), c.track)
            var previous: Point? = null
            trail.forEach { p ->
                val a = previous
                if (p != null && a != null) drawLine(palette[0], vp.toScreen(a), vp.toScreen(p), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                previous = p
            }
            val center = vp.toScreen(Point(pose.x, pose.y))
            val radius = (0.3 * vp.scale).toFloat()
            rotate(-deg(pose.theta).toFloat(), center) {
                // Wheels on the sides, body, and a pointer toward the front (+x of the robot).
                val wheel = Size(radius * 0.9f, radius * 0.32f)
                drawRoundRect(c.ink, Offset(center.x - wheel.width / 2, center.y - radius - wheel.height / 2), wheel, CornerRadius(4f))
                drawRoundRect(c.ink, Offset(center.x - wheel.width / 2, center.y + radius - wheel.height / 2), wheel, CornerRadius(4f))
                drawCircle(palette[1], radius, center)
                drawLine(c.card, center, Offset(center.x + radius * 0.9f, center.y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardChip(stringResource(if (running) R.string.robotics_pause else R.string.robotics_play), running) { running = !running }
            CardChip(stringResource(R.string.robotics_reset), false) {
                pose = RobotMath.Pose(0.0, -1.0, 0.0)
                trail.clear()
                trail.add(Point(0.0, -1.0))
            }
        }
        LabeledSlider("v", v, -0.5f..1.0f, "${fmt(v.toDouble())} m/s") { v = it }
        LabeledSlider("ω", w, -2f..2f, "${fmt(w.toDouble())} rad/s") { w = it }
        val radius = if (abs(w) < 0.005f) "\\infty" else fmt(v / w.toDouble()) + "\\,\\text{m}"
        MathFormula(
            "\\begin{aligned} \\dot x &= v\\cos\\theta = ${fmt(v * cos(pose.theta))} & \\dot y &= v\\sin\\theta = ${fmt(v * sin(pose.theta))} \\\\ " +
                "\\dot\\theta &= \\omega = ${fmt(w.toDouble())}\\,\\text{rad/s} & R &= v/\\omega = $radius \\\\ " +
                "v_R &= v + \\tfrac{\\omega L}{2} = ${fmt(right)}\\,\\text{m/s} & v_L &= v - \\tfrac{\\omega L}{2} = ${fmt(left)}\\,\\text{m/s} \\end{aligned}"
        )
        Hint(stringResource(R.string.robotics_track_hint, fmt(TRACK)))
    }
}

private const val ARENA = 3.0
private const val TRACK = 0.3

private fun wrapArena(x: Double) = when {
    x > ARENA -> x - 2 * ARENA
    x < -ARENA -> x + 2 * ARENA
    else -> x
}

// --- Sensors: noise and a low-pass filter ---

@Composable
fun SensorSimulator() {
    val palette = chartPalette()
    var sigma by remember { mutableFloatStateOf(0.15f) }
    var alpha by remember { mutableFloatStateOf(0.2f) }
    val times = remember { (0..400).map { it * 0.025 } }
    // The same noise every time, scaled by σ, so moving a slider only changes what it controls.
    val unitNoise = remember { Random(7).let { rnd -> times.map { rnd.nextGaussian() } } }
    val truth = remember { times.map { t -> (if (t < 2.0) 0.2 else 1.0) + 0.15 * sin(0.9 * t) } }
    val measured = truth.indices.map { truth[it] + sigma * unitNoise[it] }
    val filtered = RobotMath.lowPass(measured, alpha.toDouble())
    fun rms(xs: List<Double>) = sqrt(xs.indices.sumOf { (xs[it] - truth[it]) * (xs[it] - truth[it]) } / xs.size)
    SimCard {
        LineChart(
            listOf(
                ChartSeries(stringResource(R.string.robotics_series_measured), times, measured, palette[1], width = 1.5.dp),
                ChartSeries(stringResource(R.string.robotics_series_true), times, truth, palette[2], dashed = true, width = 2.dp),
                ChartSeries(stringResource(R.string.robotics_series_filtered), times, filtered, palette[0])
            ),
            xMax = times.last(), yMin = -0.5, yMax = 2.0, xUnit = "s",
            description = "Sensor con ruido σ = ${fmt(sigma.toDouble())} y filtro α = ${fmt(alpha.toDouble())}"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric(fmt(rms(measured), 3), stringResource(R.string.robotics_rms_measured), Modifier.weight(1f))
            Metric(fmt(rms(filtered), 3), stringResource(R.string.robotics_rms_filtered), Modifier.weight(1f))
        }
        LabeledSlider(stringResource(R.string.robotics_noise), sigma, 0f..0.5f, fmt(sigma.toDouble())) { sigma = it }
        LabeledSlider(stringResource(R.string.robotics_alpha), alpha, 0.02f..1f, fmt(alpha.toDouble())) { alpha = it }
        MathFormula("y_k = \\alpha\\,z_k + (1-\\alpha)\\,y_{k-1} = ${fmt(alpha.toDouble())}\\,z_k + ${fmt(1 - alpha.toDouble())}\\,y_{k-1}")
        Hint(stringResource(R.string.robotics_filter_hint))
    }
}
