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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.reading.MathFormula
import org.isoron.uhabits.ancla.reading.chartPalette
import org.isoron.uhabits.ancla.robotics.RobotMath.fmt
import org.isoron.uhabits.ancla.ui.Ancla
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** An orthographic camera you turn by dragging: [yaw] about the vertical, [pitch] looking down. */
private class Camera(size: Size, yaw: Float, pitch: Float, halfWorld: Double, private val lift: Double = 0.0) {
    private val cy = cos(yaw.toDouble())
    private val sy = sin(yaw.toDouble())
    private val cp = cos(pitch.toDouble())
    private val sp = sin(pitch.toDouble())
    private val scale = minOf(size.width, size.height) / 2f / halfWorld.toFloat()
    private val center = Offset(size.width / 2f, size.height * 0.58f)

    fun project(p: Vec3): Offset {
        val x = p.x * cy - p.y * sy
        val y = p.x * sy + p.y * cy
        val up = (p.z - lift) * cp + y * sp
        return Offset(center.x + (x * scale).toFloat(), center.y - (up * scale).toFloat())
    }
}

private val ROBOT_LABELS = mapOf(
    RobotConfig.CARTESIAN to R.string.robotics_cfg_cartesian,
    RobotConfig.CYLINDRICAL to R.string.robotics_cfg_cylindrical,
    RobotConfig.POLAR to R.string.robotics_cfg_polar,
    RobotConfig.ANGULAR to R.string.robotics_cfg_angular,
    RobotConfig.SCARA to R.string.robotics_cfg_scara
)

/**
 * The classic configurations of industrial robots in 3D, built from their Denavit–Hartenberg
 * tables: move each joint, animate them all, trace the tool to see the shape of the workspace,
 * show the DH frames, and count degrees of freedom with Grübler–Kutzbach.
 */
@Composable
fun Robots3DSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    var config by remember { mutableStateOf(RobotConfig.ANGULAR) }
    val joints = remember(config) { Robots.joints(config) }
    var q by remember(config) { mutableStateOf(joints.map { it.home }) }
    var yaw by remember { mutableFloatStateOf(-0.6f) }
    var pitch by remember { mutableFloatStateOf(0.45f) }
    var animating by remember(config) { mutableStateOf(false) }
    var showFrames by remember { mutableStateOf(false) }
    var tracing by remember { mutableStateOf(true) }
    var trace by remember(config) { mutableStateOf(listOf<Vec3>()) }
    LaunchedEffect(animating, config) {
        var t = 0.0
        var last = 0L
        while (animating) {
            withFrameNanos { now ->
                if (last != 0L) t += ((now - last) / 1e9).coerceAtMost(0.05)
                last = now
                q = joints.mapIndexed { i, j ->
                    val mid = (j.min + j.max) / 2
                    val amp = (j.max - j.min) / 2 * 0.85
                    mid + amp * sin(0.55 * t * (1 + 0.37 * i) + i * 1.3)
                }
            }
        }
    }
    val frames = Robots.frames(joints, q)
    val tip = frames.last().origin
    LaunchedEffect(tip, tracing) {
        if (tracing && (trace.isEmpty() || (trace.last() - tip).length > 0.01)) trace = (trace + tip).takeLast(900)
    }

    SimCard {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ROBOT_LABELS.forEach { (cfg, label) -> CardChip(stringResource(label), config == cfg) { config = cfg } }
        }
        val measurer = rememberTextMeasurer()
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()
                .semantics { contentDescription = "Robot ${config.name}: punta en (${fmt(tip.x)}, ${fmt(tip.y)}, ${fmt(tip.z)}) m" }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        yaw += drag.x / 250f
                        pitch = (pitch + drag.y / 250f).coerceIn(-0.1f, 1.45f)
                    }
                }
        ) {
            val cam = Camera(size, yaw, pitch, 0.95, lift = 0.4)
            floor(cam, c.track)
            if (trace.size > 1) {
                val path = Path()
                trace.forEachIndexed { i, p -> cam.project(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
                drawPath(path, palette[2].copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
            }
            // Pedestal
            drawLine(c.inkSoft, cam.project(Vec3(-0.12, 0.0, 0.0)), cam.project(Vec3(0.12, 0.0, 0.0)), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(c.inkSoft, cam.project(Vec3(0.0, -0.12, 0.0)), cam.project(Vec3(0.0, 0.12, 0.0)), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            // Links: from one frame origin to the next, and the joint on its axis z_{i-1}.
            joints.forEachIndexed { i, j ->
                val from = frames[i]
                val to = frames[i + 1]
                val color = if (j.type == JointType.R) palette[0] else palette[1]
                drawLine(c.ink.copy(alpha = 0.85f), cam.project(from.origin), cam.project(to.origin), strokeWidth = (9 - i).coerceAtLeast(4).dp.toPx(), cap = StrokeCap.Round)
                val axis = from.axis(2)
                val o = from.origin
                drawLine(color, cam.project(o - axis * 0.07), cam.project(o + axis * 0.07), strokeWidth = 7.dp.toPx(), cap = if (j.type == JointType.R) StrokeCap.Round else StrokeCap.Square)
                val label = measurer.measure("${i + 1}", TextStyle(color = color, fontSize = 12.sp))
                val at = cam.project(o)
                drawText(label, topLeft = Offset(at.x + 8.dp.toPx(), at.y - 18.dp.toPx()))
            }
            if (showFrames) frames.forEach { f -> axes(cam, f, 0.13, palette) }
            gripper(cam, frames.last(), c.ink)
        }
        Hint(stringResource(R.string.robotics_robots3d_drag))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardChip(stringResource(if (animating) R.string.robotics_pause else R.string.robotics_animate), animating) { animating = !animating }
            CardChip(stringResource(R.string.robotics_trace), tracing) {
                tracing = !tracing
                trace = emptyList()
            }
            CardChip(stringResource(R.string.robotics_frames), showFrames) { showFrames = !showFrames }
            CardChip(stringResource(R.string.robotics_reset), false) {
                q = joints.map { it.home }
                trace = emptyList()
            }
        }
        joints.forEachIndexed { i, j ->
            val value = q[i]
            val shown = if (j.type == JointType.R) "${Math.toDegrees(value).toInt()}°" else "${fmt(value)} m"
            val name = if (j.type == JointType.R) "θ${sub(i + 1)} (R)" else "d${sub(i + 1)} (P)"
            LabeledSlider(name, value.toFloat(), j.min.toFloat()..j.max.toFloat(), shown) { v ->
                animating = false
                q = q.toMutableList().also { it[i] = v.toDouble() }
            }
        }
        MathFormula(dhTable(joints, q))
        MathFormula("p = (${fmt(tip.x)},\\ ${fmt(tip.y)},\\ ${fmt(tip.z)})\\,\\text{m},\\qquad M = \\sum f_i = ${joints.size}")
        Text(stringResource(R.string.robotics_legend_joints), style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
    KutzbachCard()
}

private fun sub(n: Int) = n.toString().map { "₀₁₂₃₄₅₆₇₈₉"[it - '0'] }.joinToString("")

/** The DH table with today's values; the joint variable of each row in bold. */
private fun dhTable(joints: List<DhJoint>, q: List<Double>): String {
    fun deg(r: Double) = "${Math.round(Math.toDegrees(r))}^\\circ"
    val rows = joints.mapIndexed { i, j ->
        val theta = if (j.type == JointType.R) "\\mathbf{${deg(j.theta + q[i])}}" else deg(j.theta)
        val d = if (j.type == JointType.P) "\\mathbf{${fmt(j.d + q[i])}}" else fmt(j.d)
        "${i + 1} & $theta & $d & ${fmt(j.a)} & ${deg(j.alpha)}"
    }
    return "\\begin{array}{c|cccc} i & \\theta_i & d_i & a_i & \\alpha_i \\\\ \\hline " + rows.joinToString(" \\\\ ") + " \\end{array}"
}

private fun DrawScope.floor(cam: Camera, color: Color) {
    val n = 4
    for (i in -n..n) {
        val k = i * 0.25
        drawLine(color, cam.project(Vec3(k, -1.0, 0.0)), cam.project(Vec3(k, 1.0, 0.0)), strokeWidth = 1f)
        drawLine(color, cam.project(Vec3(-1.0, k, 0.0)), cam.project(Vec3(1.0, k, 0.0)), strokeWidth = 1f)
    }
}

/** x, y, z of a frame in the chart palette's order 1, 2, 0 (orange, green, blue), like the usual red-green-blue. */
private fun DrawScope.axes(cam: Camera, f: Mat4, len: Double, palette: List<Color>) {
    val o = f.origin
    listOf(1, 2, 0).forEachIndexed { axis, color ->
        drawLine(palette[color], cam.project(o), cam.project(o + f.axis(axis) * len), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.gripper(cam: Camera, f: Mat4, color: Color) {
    val o = f.origin
    val z = f.axis(2)
    val x = f.axis(0)
    val w = 0.05
    val l = 0.07
    drawLine(color, cam.project(o - x * w), cam.project(o + x * w), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, cam.project(o - x * w), cam.project(o - x * w + z * l), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, cam.project(o + x * w), cam.project(o + x * w + z * l), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
}

/** Grübler–Kutzbach for spatial mechanisms: pick the pairs, read the degrees of freedom. */
@Composable
private fun KutzbachCard() {
    val c = Ancla.colors
    var links by remember { mutableStateOf(7) }
    var pairs by remember { mutableStateOf(mapOf(SpatialPair.R to 6)) }
    var example by remember { mutableStateOf<String?>("Robot 6R") }
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SimCard {
            Text(stringResource(R.string.robotics_kutzbach_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(stringResource(R.string.robotics_kutzbach_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kutzbach.EXAMPLES.forEach { (name, value) ->
                    CardChip(name, example == name) {
                        links = value.first
                        pairs = value.second
                        example = name
                    }
                }
            }
            CountRow("N", links) { links = it.coerceIn(1, 40); example = null }
            SpatialPair.entries.forEach { p ->
                CountRow("${p.name}  (f = ${p.dof})", pairs[p] ?: 0) { n ->
                    pairs = pairs.toMutableMap().also { if (n <= 0) it.remove(p) else it[p] = n.coerceAtMost(40) }
                    example = null
                }
            }
            val terms = SpatialPair.entries.filter { (pairs[it] ?: 0) > 0 }
            val sum = terms.joinToString(" + ") { "${pairs[it]}\\cdot ${6 - it.dof}" }.ifEmpty { "0" }
            MathFormula("M = 6(N-1) - \\sum (6 - f_i) = 6(${links}-1) - ($sum) = ${Kutzbach.dof(links, pairs)}")
            example?.let { name ->
                val note = when (name) {
                    "Delta" -> R.string.robotics_kutzbach_delta
                    "RSSR" -> R.string.robotics_kutzbach_rssr
                    "4 barras 3D" -> R.string.robotics_kutzbach_planar
                    "Stewart" -> R.string.robotics_kutzbach_stewart
                    else -> R.string.robotics_kutzbach_serial
                }
                Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}

@Composable
private fun CountRow(label: String, value: Int, onChange: (Int) -> Unit) {
    val c = Ancla.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
        CardChip("−", false) { onChange(value - 1) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(horizontal = 6.dp))
        CardChip("+", false) { onChange(value + 1) }
    }
}

// --- Orientation ---

/** Euler ZYZ or roll-pitch-yaw on a frame you can turn around, with its matrix and quaternion. */
@Composable
fun Orientation3DSimulator() {
    val c = Ancla.colors
    val palette = chartPalette()
    var rpy by remember { mutableStateOf(false) }
    var a1 by remember { mutableFloatStateOf(30f) }
    var a2 by remember { mutableFloatStateOf(40f) }
    var a3 by remember { mutableFloatStateOf(20f) }
    var yaw by remember { mutableFloatStateOf(-0.7f) }
    var pitch by remember { mutableFloatStateOf(0.4f) }
    val r1 = Math.toRadians(a1.toDouble())
    val r2 = Math.toRadians(a2.toDouble())
    val r3 = Math.toRadians(a3.toDouble())
    val rot = if (rpy) Robots.rpy(r1, r2, r3) else Robots.eulerZyz(r1, r2, r3)
    val frame = Mat4.rotation(rot)
    val measurer = rememberTextMeasurer()
    SimCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardChip("Euler ZYZ", !rpy) { rpy = false }
            CardChip("Roll-pitch-yaw", rpy) { rpy = true }
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    yaw += drag.x / 250f
                    pitch = (pitch + drag.y / 250f).coerceIn(-1.2f, 1.45f)
                }
            }
        ) {
            val cam = Camera(size, yaw, pitch, 1.5)
            // Fixed frame, faint.
            listOf("x₀", "y₀", "z₀").forEachIndexed { i, name ->
                val e = Vec3(if (i == 0) 1.0 else 0.0, if (i == 1) 1.0 else 0.0, if (i == 2) 1.0 else 0.0)
                drawLine(c.muted, cam.project(Vec3(0.0, 0.0, 0.0)), cam.project(e * 1.15), strokeWidth = 1.5.dp.toPx())
                val t = measurer.measure(name, TextStyle(color = c.muted, fontSize = 12.sp))
                drawText(t, topLeft = cam.project(e * 1.25) - Offset(t.size.width / 2f, t.size.height / 2f))
            }
            // A box (the tool) turned with the frame, so the orientation reads at a glance.
            val corners = listOf(-1.0, 1.0).flatMap { x -> listOf(-1.0, 1.0).flatMap { y -> listOf(-1.0, 1.0).map { z -> Vec3(x * 0.45, y * 0.25, z * 0.12) } } }
            val turned = corners.map { p -> Vec3(rot[0][0] * p.x + rot[0][1] * p.y + rot[0][2] * p.z, rot[1][0] * p.x + rot[1][1] * p.y + rot[1][2] * p.z, rot[2][0] * p.x + rot[2][1] * p.y + rot[2][2] * p.z) }
            for (i in turned.indices) for (j in i + 1 until turned.size) {
                val diff = listOf(corners[i].x != corners[j].x, corners[i].y != corners[j].y, corners[i].z != corners[j].z).count { it }
                if (diff == 1) drawLine(c.inkSoft.copy(alpha = 0.7f), cam.project(turned[i]), cam.project(turned[j]), strokeWidth = 1.5.dp.toPx())
            }
            listOf("x₁" to 1, "y₁" to 2, "z₁" to 0).forEachIndexed { axis, (name, color) ->
                val end = frame.axis(axis)
                drawLine(palette[color], cam.project(Vec3(0.0, 0.0, 0.0)), cam.project(end), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                val t = measurer.measure(name, TextStyle(color = palette[color], fontSize = 13.sp))
                drawText(t, topLeft = cam.project(end * 1.12) - Offset(t.size.width / 2f, t.size.height / 2f))
            }
        }
        Hint(stringResource(R.string.robotics_robots3d_drag))
        val names = if (rpy) listOf("γ (roll, x)", "β (pitch, y)", "α (yaw, z)") else listOf("φ (z)", "θ (y')", "ψ (z'')")
        LabeledSlider(names[0], a1, -180f..180f, "${a1.toInt()}°") { a1 = it }
        LabeledSlider(names[1], a2, if (rpy) -90f..90f else 0f..180f, "${a2.toInt()}°") { a2 = it }
        LabeledSlider(names[2], a3, -180f..180f, "${a3.toInt()}°") { a3 = it }
        val locked = if (rpy) abs(abs(a2) - 90f) < 2f else a2 < 2f || a2 > 178f
        if (locked) Text(stringResource(R.string.robotics_gimbal_lock), style = MaterialTheme.typography.bodyMedium, color = palette[1])
        val q = Robots.quaternion(rot)
        val angle = Math.toDegrees(2 * acos(q[0].coerceIn(-1.0, 1.0)))
        val m = rot.joinToString(" \\\\ ") { row -> row.joinToString(" & ") { fmt(it) } }
        MathFormula((if (rpy) "R = R_z(\\alpha)R_y(\\beta)R_x(\\gamma)" else "R = R_z(\\phi)R_y(\\theta)R_z(\\psi)") + " = \\begin{bmatrix} $m \\end{bmatrix}")
        MathFormula("q = (${fmt(q[0], 3)},\\ ${fmt(q[1], 3)},\\ ${fmt(q[2], 3)},\\ ${fmt(q[3], 3)}),\\quad \\theta = ${fmt(angle, 1)}^\\circ")
    }
}
