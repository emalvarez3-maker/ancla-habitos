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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.reading.Chip
import org.isoron.uhabits.ancla.reading.MathFormula
import org.isoron.uhabits.ancla.reading.chartPalette
import org.isoron.uhabits.ancla.robotics.RobotMath.Point
import org.isoron.uhabits.ancla.ui.Ancla
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class Tool(val label: Int, val hint: Int) {
    MOVE(R.string.robotics_tool_move, R.string.robotics_tool_move_hint),
    BAR(R.string.robotics_tool_bar, R.string.robotics_tool_bar_hint),
    GROUND(R.string.robotics_tool_ground, R.string.robotics_tool_ground_hint),
    SLIDER(R.string.robotics_tool_slider, R.string.robotics_tool_slider_hint),
    WELD(R.string.robotics_tool_weld, R.string.robotics_tool_weld_hint),
    MOTOR(R.string.robotics_tool_motor, R.string.robotics_tool_motor_hint),
    SHAPE(R.string.robotics_tool_shape, R.string.robotics_tool_shape_hint),
    ERASE(R.string.robotics_tool_erase, R.string.robotics_tool_erase_hint)
}

private val PRESET_LABELS = mapOf(
    LinkagePreset.FOUR_BAR to R.string.robotics_preset_four_bar,
    LinkagePreset.COUPLER_CURVE to R.string.robotics_preset_coupler,
    LinkagePreset.SLIDER_CRANK to R.string.robotics_preset_slider_crank,
    LinkagePreset.FIVE_BAR to R.string.robotics_preset_five_bar,
    LinkagePreset.ARM_2R to R.string.robotics_preset_arm,
    LinkagePreset.TRUSS to R.string.robotics_preset_truss,
    LinkagePreset.OVERCONSTRAINED to R.string.robotics_preset_overconstrained,
    LinkagePreset.STEPHENSON to R.string.robotics_preset_stephenson
)

private const val WORLD_HALF = 4.6
private const val MOTOR_SPEED = 1.3 // rad/s
private const val TRACE_POINTS = 700

/**
 * The mechanism workshop: draw links and joints (fixed pins, pins, sliders, ternary plates), put a
 * motor on a crank and watch it move; drag any joint to feel its freedom, trace the curves its
 * points draw, and learn to count links and pairs with Grübler step by step, or practise counting.
 */
@Composable
fun MechanismWorkshop() {
    val c = Ancla.colors
    val palette = chartPalette()
    var model by remember { mutableStateOf(LinkagePresets.build(LinkagePreset.FOUR_BAR)) }
    var tool by remember { mutableStateOf(Tool.MOVE) }
    var selected by remember { mutableStateOf<Int?>(null) } // a node (bar tool) or a body (weld tool)
    var running by remember { mutableStateOf(false) }
    var direction by remember { mutableDoubleStateOf(1.0) }
    var tracing by remember { mutableStateOf(true) }
    var traces by remember { mutableStateOf(mapOf<Int, List<Point>>()) }
    var message by remember { mutableStateOf<Int?>(null) }
    var guide by remember { mutableStateOf<Int?>(null) }
    var practice by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var frame by remember { mutableStateOf(Frame.fit(model)) }
    val current by rememberUpdatedState(model)
    val view by rememberUpdatedState(frame)

    fun load(m: LinkageModel) {
        model = m
        traces = emptyMap()
        selected = null
        message = null
        running = false
    }

    fun edit(m: LinkageModel) {
        load(m)
    }

    fun record(m: LinkageModel) {
        if (!tracing) return
        traces = m.nodes.filter { it.kind != NodeKind.GROUND }.associate { n ->
            n.id to ((traces[n.id].orEmpty() + Point(n.x, n.y)).takeLast(TRACE_POINTS))
        }
    }

    LaunchedEffect(running) {
        var last = 0L
        var stuck = 0
        while (running) {
            withFrameNanos { now ->
                if (last != 0L && dragging == null) {
                    val dt = ((now - last) / 1e9).coerceAtMost(0.05)
                    val next = LinkageSolver.turn(current, MOTOR_SPEED * dt * direction)
                    if (next == null) {
                        direction = -direction
                        message = R.string.robotics_dead_point
                        if (++stuck > 3) running = false // can't move either way
                    } else {
                        stuck = 0
                        model = next
                        record(next)
                    }
                }
                last = now
            }
        }
    }

    val count = model.count()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Examples
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESET_LABELS.forEach { (preset, label) ->
                Chip(stringResource(label), false) {
                    val m = LinkagePresets.build(preset)
                    frame = Frame.fit(m)
                    load(m)
                    guide = null
                }
            }
            Chip(stringResource(R.string.robotics_preset_empty), false) {
                frame = Frame(0.0, 0.0, WORLD_HALF)
                load(LinkageModel())
                tool = Tool.GROUND
                guide = null
            }
        }

        // The drawing
        val measurer = rememberTextMeasurer()
        val highlightGround = guide == 0
        val highlightLinks = guide == 1
        val highlightPairs = guide == 2
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card)) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(1f)
                    .semantics { contentDescription = "Mecanismo: N = ${count.links}, j1 = ${count.j1}, M = ${count.dof}" }
                    .pointerInput(tool, selected) {
                        detectTapGestures { o ->
                            val v = WorkshopView(Size(size.width.toFloat(), size.height.toFloat()), view)
                            val p = v.toWorld(o)
                            val tol = 26.dp.toPx() / v.scale
                            val node = current.nodes.minByOrNull { hypot(it.x - p.x, it.y - p.y) }?.takeIf { hypot(it.x - p.x, it.y - p.y) < tol }
                            val body = if (node == null) hitBody(current, p, (16.dp.toPx() / v.scale).toDouble()) else null
                            message = null
                            when (tool) {
                                Tool.MOVE, Tool.SHAPE -> Unit
                                Tool.BAR -> {
                                    val target = node?.id ?: current.addNode(p.x, p.y, NodeKind.FREE).let { (m, id) -> model = m; id }
                                    val first = selected
                                    if (first == null) {
                                        selected = target
                                    } else {
                                        edit(model.addBar(first, target))
                                        selected = target // keep drawing a chain
                                    }
                                }
                                Tool.GROUND -> when {
                                    node == null -> edit(current.addNode(p.x, p.y, NodeKind.GROUND).first)
                                    else -> edit(current.setKind(node.id, if (node.kind == NodeKind.GROUND) NodeKind.FREE else NodeKind.GROUND))
                                }
                                Tool.SLIDER -> when {
                                    node == null -> edit(current.addNode(p.x, p.y, NodeKind.SLIDER).first)
                                    node.kind == NodeKind.SLIDER -> edit(current.rotateGuide(node.id))
                                    else -> edit(current.setKind(node.id, NodeKind.SLIDER))
                                }
                                Tool.WELD -> if (body != null) {
                                    val first = selected
                                    if (first == null || first == body) {
                                        selected = body
                                    } else {
                                        val welded = current.weld(first, body)
                                        if (welded == current) message = R.string.robotics_weld_needs_shared else edit(welded)
                                        selected = null
                                    }
                                }
                                Tool.MOTOR -> if (body != null) {
                                    val m = current.withMotor(body)
                                    if (m == null) message = R.string.robotics_motor_needs_ground else edit(m)
                                }
                                Tool.ERASE -> when {
                                    node != null -> edit(current.deleteNode(node.id))
                                    body != null -> edit(current.deleteBody(body))
                                }
                            }
                        }
                    }
                    .pointerInput(tool) {
                        if (tool != Tool.MOVE && tool != Tool.SHAPE) return@pointerInput
                        detectDragGestures(
                            onDragStart = { o ->
                                val v = WorkshopView(Size(size.width.toFloat(), size.height.toFloat()), view)
                                val p = v.toWorld(o)
                                dragging = current.nodes.minByOrNull { hypot(it.x - p.x, it.y - p.y) }
                                    ?.takeIf { hypot(it.x - p.x, it.y - p.y) < 30.dp.toPx() / v.scale }?.id
                            },
                            onDragEnd = { dragging = null },
                            onDragCancel = { dragging = null }
                        ) { change, _ ->
                            val id = dragging ?: return@detectDragGestures
                            change.consume()
                            val v = WorkshopView(Size(size.width.toFloat(), size.height.toFloat()), view)
                            val p = v.toWorld(change.position)
                            if (tool == Tool.SHAPE) {
                                model = current.moveFreely(id, p.x, p.y)
                                traces = emptyMap()
                            } else {
                                val moved = LinkageSolver.drag(current, id, p.x, p.y)
                                model = moved
                                record(moved)
                            }
                        }
                    }
            ) {
                val v = WorkshopView(size, frame)
                drawMechanism(v, model, count, traces, palette, c, measurer, selected, tool, highlightGround, highlightLinks, highlightPairs)
            }
        }

        Text(stringResource(message ?: tool.hint), style = MaterialTheme.typography.labelMedium, color = if (message != null) palette[1] else c.muted)

        // Tools
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool.entries.forEach { t ->
                Chip(stringResource(t.label), tool == t) {
                    tool = t
                    selected = null
                    message = null
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(if (running) R.string.robotics_pause else R.string.robotics_animate), running) {
                if (model.motor == null) message = R.string.robotics_need_motor else running = !running
            }
            Chip(stringResource(R.string.robotics_trace), tracing) {
                tracing = !tracing
                traces = emptyMap()
            }
            Chip(stringResource(R.string.robotics_guide), guide != null) {
                guide = if (guide == null) 0 else null
                practice = false
            }
            Chip(stringResource(R.string.robotics_practice), practice) {
                practice = !practice
                guide = null
                if (practice) LinkagePresets.build(LinkagePreset.entries.random()).let { frame = Frame.fit(it); load(it) }
            }
        }

        when {
            practice -> PracticeCard(count) { LinkagePresets.build(LinkagePreset.entries.random()).let { frame = Frame.fit(it); load(it) } }
            guide != null -> GuideCard(model, count, guide ?: 0) { guide = it }
            else -> AnalysisCard(model, count)
        }
    }
}

/**
 * What part of the plane the drawing shows: framed around a mechanism when it's loaded, with room
 * for it to move, and kept still while it moves or while drawing so nothing jumps under the finger.
 */
private data class Frame(val cx: Double, val cy: Double, val half: Double) {
    companion object {
        fun fit(m: LinkageModel): Frame {
            if (m.nodes.isEmpty()) return Frame(0.0, 0.0, WORLD_HALF)
            val xs = m.nodes.map { it.x }
            val ys = m.nodes.map { it.y }
            // Room for the longest link to swing around any pin.
            val reach = m.bodies.flatMap { it.lengths.values }.maxOrNull() ?: 1.0
            val half = maxOf((xs.max() - xs.min()) / 2, (ys.max() - ys.min()) / 2) + reach * 0.45 + 0.6
            return Frame((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2, half.coerceIn(2.2, WORLD_HALF + 1))
        }
    }
}

/** World ↔ screen for the workshop: y up. */
private class WorkshopView(size: Size, val frame: Frame) {
    val scale = (minOf(size.width, size.height) / 2f) / frame.half.toFloat()
    val center = Offset(size.width / 2f, size.height / 2f)
    fun toScreen(x: Double, y: Double) = Offset(center.x + ((x - frame.cx) * scale).toFloat(), center.y - ((y - frame.cy) * scale).toFloat())
    fun toWorld(o: Offset) = Point((o.x - center.x) / scale + frame.cx, (center.y - o.y) / scale + frame.cy)
}

private fun hitBody(m: LinkageModel, p: Point, tol: Double): Int? = m.bodies.minByOrNull { b -> bodyDistance(m, b, p) }?.takeIf { bodyDistance(m, it, p) < tol }?.id

private fun bodyDistance(m: LinkageModel, b: LBody, p: Point): Double {
    var best = Double.MAX_VALUE
    for (i in b.nodes.indices) for (j in i + 1 until b.nodes.size) {
        val a = m.node(b.nodes[i])
        val q = m.node(b.nodes[j])
        val dx = q.x - a.x
        val dy = q.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        best = minOf(best, hypot(a.x + t * dx - p.x, a.y + t * dy - p.y))
    }
    return best
}

private fun DrawScope.drawMechanism(
    v: WorkshopView,
    m: LinkageModel,
    count: GrueblerCount,
    traces: Map<Int, List<Point>>,
    palette: List<Color>,
    c: org.isoron.uhabits.ancla.ui.AnclaColors,
    measurer: androidx.compose.ui.text.TextMeasurer,
    selected: Int?,
    tool: Tool,
    highlightGround: Boolean,
    highlightLinks: Boolean,
    highlightPairs: Boolean
) {
    // Light grid, 1 unit apart.
    val f = v.frame
    val lo = kotlin.math.floor(minOf(f.cx, f.cy) - f.half - 1).toInt()
    val hi = kotlin.math.ceil(maxOf(f.cx, f.cy) + f.half + 1).toInt()
    for (i in lo..hi) {
        drawLine(c.track.copy(alpha = 0.5f), v.toScreen(i.toDouble(), lo.toDouble()), v.toScreen(i.toDouble(), hi.toDouble()), strokeWidth = 1f)
        drawLine(c.track.copy(alpha = 0.5f), v.toScreen(lo.toDouble(), i.toDouble()), v.toScreen(hi.toDouble(), i.toDouble()), strokeWidth = 1f)
    }
    fun colorOf(number: Int) = palette[(number - 2).mod(palette.size)]

    // Traces: the curve each moving point draws.
    traces.forEach { (id, pts) ->
        if (pts.size < 2) return@forEach
        val number = m.bodies.firstOrNull { id in it.nodes && it.id in count.bodyNumbers }?.let { count.bodyNumbers.getValue(it.id) } ?: 2
        val path = Path()
        pts.forEachIndexed { i, p -> v.toScreen(p.x, p.y).let { o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) } }
        drawPath(path, colorOf(number).copy(alpha = 0.55f), style = Stroke(1.5.dp.toPx()))
    }

    // Slider guides.
    m.nodes.filter { it.kind == NodeKind.SLIDER }.forEach { n ->
        val ux = cos(n.lineAngle) * WORLD_HALF * 2
        val uy = sin(n.lineAngle) * WORLD_HALF * 2
        drawLine(c.muted, v.toScreen(n.lineX - ux, n.lineY - uy), v.toScreen(n.lineX + ux, n.lineY + uy), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    }

    // Links: bars as thick lines, plates as filled polygons. Ground links in grey.
    m.bodies.forEach { b ->
        val number = count.bodyNumbers[b.id]
        val col = if (number == null) c.muted else colorOf(number)
        val pts = b.nodes.map { m.node(it) }.map { v.toScreen(it.x, it.y) }
        val emphasized = highlightLinks || (tool == Tool.WELD && selected == b.id)
        if (pts.size >= 3) {
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            val ordered = pts.sortedBy { kotlin.math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
            val path = Path().apply {
                moveTo(ordered[0].x, ordered[0].y)
                ordered.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, col.copy(alpha = if (emphasized) 0.45f else 0.25f))
            drawPath(path, col, style = Stroke(5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else if (pts.size == 2) {
            drawLine(col, pts[0], pts[1], strokeWidth = (if (emphasized) 11 else 8).dp.toPx(), cap = StrokeCap.Round)
        }
    }

    // Motor: a curved arrow around its pivot.
    m.motor?.let { motor ->
        m.nodeOrNull(motor.pivot)?.let { p ->
            val o = v.toScreen(p.x, p.y)
            val r = 15.dp.toPx()
            drawArc(c.lilac, 200f, 250f, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            val end = Math.toRadians(90.0)
            val tip = Offset(o.x + r * cos(end).toFloat(), o.y + r * sin(end).toFloat())
            drawCircle(c.lilac, 4.dp.toPx(), tip)
        }
    }

    // Joints.
    val labelStyle = TextStyle(color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    m.nodes.forEach { n ->
        val o = v.toScreen(n.x, n.y)
        val bodiesHere = m.bodies.count { n.id in it.nodes }
        when (n.kind) {
            NodeKind.GROUND -> {
                // Classic ground symbol: a triangle on a hatched base.
                val s = 11.dp.toPx()
                val col = if (highlightGround) c.lilacInk else c.inkSoft
                val tri = Path().apply {
                    moveTo(o.x, o.y)
                    lineTo(o.x - s, o.y + s * 1.4f)
                    lineTo(o.x + s, o.y + s * 1.4f)
                    close()
                }
                drawPath(tri, col.copy(alpha = if (highlightGround) 0.35f else 0.15f))
                drawPath(tri, col, style = Stroke(2.dp.toPx()))
                val baseY = o.y + s * 1.4f
                drawLine(col, Offset(o.x - s * 1.5f, baseY), Offset(o.x + s * 1.5f, baseY), strokeWidth = 2.dp.toPx())
                for (k in 0..3) {
                    val x0 = o.x - s * 1.4f + k * s * 0.8f
                    drawLine(col, Offset(x0, baseY + s * 0.6f), Offset(x0 + s * 0.6f, baseY), strokeWidth = 1.5.dp.toPx())
                }
                if (highlightGround) drawCircle(c.lilac, 24.dp.toPx(), o, style = Stroke(2.dp.toPx()))
            }
            NodeKind.SLIDER -> rotate(-Math.toDegrees(n.lineAngle).toFloat(), o) {
                val w = 30.dp.toPx()
                val h = 18.dp.toPx()
                drawRoundRect(c.inkSoft.copy(alpha = 0.2f), Offset(o.x - w / 2, o.y - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(c.inkSoft, Offset(o.x - w / 2, o.y - h / 2), Size(w, h), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
            NodeKind.FREE -> Unit
        }
        val isJoint = bodiesHere >= 2 || n.kind != NodeKind.FREE
        if (isJoint) {
            drawCircle(c.card, 6.dp.toPx(), o)
            drawCircle(c.ink, 6.dp.toPx(), o, style = Stroke(2.dp.toPx()))
        } else {
            drawCircle(c.ink, 4.dp.toPx(), o)
        }
        if (selected == n.id && tool == Tool.BAR) drawCircle(c.lilac, 14.dp.toPx(), o, style = Stroke(3.dp.toPx()))
        if (highlightPairs) {
            val pairs = count.pairs.count { it.node == n.id }
            if (pairs > 0) {
                drawCircle(c.lilac, 15.dp.toPx(), o, style = Stroke(2.5.dp.toPx()))
                val t = measurer.measure(pairs.toString(), TextStyle(color = c.onInk, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                val badge = Offset(o.x + 14.dp.toPx(), o.y + 12.dp.toPx())
                drawCircle(c.lilacInk, 9.dp.toPx(), badge)
                drawText(t, topLeft = Offset(badge.x - t.size.width / 2f, badge.y - t.size.height / 2f))
            }
        }
        val letter = measurer.measure(m.letter(n.id), labelStyle)
        drawText(letter, topLeft = Offset(o.x - letter.size.width - 13.dp.toPx(), o.y - letter.size.height - 6.dp.toPx()))
    }

    // Link numbers: ground is 1 (shown once, under the first fixed pin).
    val numberStyle = TextStyle(color = c.onInk, fontSize = (if (highlightLinks) 13 else 11).sp, fontWeight = FontWeight.Bold)
    fun badge(at: Offset, text: String, color: Color) {
        val t = measurer.measure(text, numberStyle)
        val r = (if (highlightLinks) 12 else 9).dp.toPx()
        drawCircle(color, r, at)
        drawText(t, topLeft = Offset(at.x - t.size.width / 2f, at.y - t.size.height / 2f))
    }
    m.bodies.forEach { b ->
        val number = count.bodyNumbers[b.id] ?: return@forEach
        val pts = b.nodes.map { m.node(it) }
        val mid = v.toScreen(pts.map { it.x }.average(), pts.map { it.y }.average())
        if (pts.size == 2) {
            // On a short bar the number would cover its joints: put it beside the bar instead.
            val p0 = v.toScreen(pts[0].x, pts[0].y)
            val p1 = v.toScreen(pts[1].x, pts[1].y)
            val len = (p1 - p0).getDistance()
            if (len < 70.dp.toPx() && len > 0f) {
                val n = Offset(-(p1.y - p0.y) / len, (p1.x - p0.x) / len) * 18.dp.toPx()
                badge(mid + n, number.toString(), colorOf(number))
                return@forEach
            }
        }
        badge(mid, number.toString(), colorOf(number))
    }
    count.sliderNumbers.forEach { (id, number) -> m.nodeOrNull(id)?.let { badge(v.toScreen(it.x, it.y) + Offset(0f, 22.dp.toPx()), number.toString(), c.inkSoft) } }
    m.nodes.firstOrNull { it.kind == NodeKind.GROUND }?.let { g -> badge(v.toScreen(g.x, g.y) + Offset(0f, 38.dp.toPx()), "1", c.muted) }
}

@Composable
private fun AnalysisCard(m: LinkageModel, count: GrueblerCount) {
    val c = Ancla.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.robotics_gruebler_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Number(count.links.toString(), stringResource(R.string.robotics_links_n), Modifier.weight(1f))
            Number(count.j1.toString(), stringResource(R.string.robotics_pairs_j1), Modifier.weight(1f))
            Number(count.dof.toString(), stringResource(R.string.robotics_dof_m), Modifier.weight(1f))
        }
        MathFormula(grueblerTex(count))
        Text(interpretation(count), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        m.grashof()?.let { (lengths, ok) ->
            val sorted = lengths.sorted()
            MathFormula(
                "s + l = ${RobotMath.fmt(sorted[0])} + ${RobotMath.fmt(sorted[3])} = ${RobotMath.fmt(sorted[0] + sorted[3])} " +
                    (if (ok) "\\le" else ">") + " p + q = ${RobotMath.fmt(sorted[1] + sorted[2])}"
            )
            Text(stringResource(if (ok) R.string.robotics_grashof_yes else R.string.robotics_grashof_no), style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun Number(value: String, label: String, modifier: Modifier) {
    val c = Ancla.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.ground).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

private fun grueblerTex(count: GrueblerCount) =
    "M = 3(N-1) - 2j_1 - j_2 = 3(${count.links}-1) - 2\\cdot ${count.j1} - 0 = ${count.dof}"

@Composable
private fun interpretation(count: GrueblerCount): String = when {
    !count.hasGround -> stringResource(R.string.robotics_m_no_ground)
    count.dof < 0 -> stringResource(R.string.robotics_m_negative, count.dof, -count.dof)
    count.dof == 0 -> stringResource(R.string.robotics_m_zero)
    count.dof == 1 -> stringResource(R.string.robotics_m_one)
    else -> stringResource(R.string.robotics_m_many, count.dof, count.dof)
}

/** "Enséñame a contar": the count in five steps, highlighting on the drawing what each step looks at. */
@Composable
private fun GuideCard(m: LinkageModel, count: GrueblerCount, step: Int, onStep: (Int) -> Unit) {
    val c = Ancla.colors
    val steps = 5
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(steps) { i -> Box(Modifier.size(width = 28.dp, height = 5.dp).clip(CircleShape).background(if (i <= step) c.lilac else c.track)) }
        }
        val grounds = m.nodes.count { it.kind == NodeKind.GROUND }
        when (step) {
            0 -> {
                Text(stringResource(R.string.robotics_guide_1), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(stringResource(R.string.robotics_guide_1_body, grounds), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                if (count.groundBodies.isNotEmpty()) Text(stringResource(R.string.robotics_guide_1_fixed), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
            1 -> {
                Text(stringResource(R.string.robotics_guide_2), style = MaterialTheme.typography.titleMedium, color = c.ink)
                val plates = m.bodies.count { it.id in count.bodyNumbers && it.nodes.size >= 3 }
                Text(
                    stringResource(R.string.robotics_guide_2_body, count.bodyNumbers.size, count.sliderNumbers.size, count.links),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkSoft
                )
                if (plates > 0) Text(stringResource(R.string.robotics_guide_2_plates, plates), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                MathFormula("N = 1 + ${count.bodyNumbers.size} + ${count.sliderNumbers.size} = ${count.links}")
            }
            2 -> {
                Text(stringResource(R.string.robotics_guide_3), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(stringResource(R.string.robotics_guide_3_body), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                count.pairs.groupBy { it.node }.forEach { (node, pairs) ->
                    val rotation = pairs.filter { !it.prismatic }
                    val links = (rotation.flatMap { listOf(it.joins.first, it.joins.second) }).distinct().sorted()
                    val line = when {
                        rotation.size >= 2 -> stringResource(R.string.robotics_pair_multiple, m.letter(node), links.size, links.joinToString(", "), rotation.size)
                        rotation.size == 1 -> stringResource(R.string.robotics_pair_simple, m.letter(node), links.joinToString(", "))
                        else -> ""
                    }
                    if (line.isNotBlank()) Text("• $line", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    pairs.filter { it.prismatic }.forEach { p ->
                        Text("• " + stringResource(R.string.robotics_pair_slider, m.letter(node), p.joins.first), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
                MathFormula("j_1 = ${count.j1},\\quad j_2 = 0")
            }
            3 -> {
                Text(stringResource(R.string.robotics_guide_4), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(stringResource(R.string.robotics_guide_4_body), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                MathFormula(grueblerTex(count))
            }
            else -> {
                Text(stringResource(R.string.robotics_guide_5), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(interpretation(count), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                MathFormula("M_{3D} = 6(N-1) - 5j_1 = 6(${count.links}-1) - 5\\cdot ${count.j1} = ${count.spatialDof}")
                Text(stringResource(R.string.robotics_guide_5_spatial), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) CardChip(stringResource(R.string.robotics_previous), false) { onStep(step - 1) }
            if (step < steps - 1) CardChip(stringResource(R.string.robotics_next), true) { onStep(step + 1) }
        }
    }
}

/** Practice: count before looking, then check. */
@Composable
private fun PracticeCard(count: GrueblerCount, onNew: () -> Unit) {
    val c = Ancla.colors
    var n by remember(count) { mutableIntStateOf(1) }
    var j by remember(count) { mutableIntStateOf(0) }
    var dof by remember(count) { mutableIntStateOf(0) }
    var checked by remember(count) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.robotics_quiz_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(stringResource(R.string.robotics_quiz_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        Stepper("N", n) { n = it.coerceIn(1, 20); checked = false }
        Stepper("j₁", j) { j = it.coerceIn(0, 30); checked = false }
        Stepper("M", dof) { dof = it.coerceIn(-10, 10); checked = false }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CardChip(stringResource(R.string.robotics_quiz_check), true) { checked = true }
            CardChip(stringResource(R.string.robotics_quiz_new), false, onNew)
        }
        if (checked) {
            val wrong = listOfNotNull("N".takeIf { n != count.links }, "j₁".takeIf { j != count.j1 }, "M".takeIf { dof != count.dof })
            Text(
                if (wrong.isEmpty()) stringResource(R.string.robotics_quiz_ok, count.links, count.j1, count.dof)
                else stringResource(R.string.robotics_quiz_wrong, wrong.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = if (wrong.isEmpty()) c.mintInk else c.inkSoft,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (wrong.isEmpty()) c.mintSoft else c.ground).padding(12.dp)
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val c = Ancla.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
        CardChip("−", false) { onChange(value - 1) }
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.padding(horizontal = 6.dp))
        CardChip("+", false) { onChange(value + 1) }
    }
}
