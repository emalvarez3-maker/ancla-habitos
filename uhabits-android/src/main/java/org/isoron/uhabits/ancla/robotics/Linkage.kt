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

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A planar mechanism the user draws: points (joints) and rigid bodies (links) that join two or
 * more of them. Free of Android so the counting and the solver can be unit tested.
 */
enum class NodeKind {
    /** A pin between the bodies that meet there (or just a point on one body). */
    FREE,

    /** A pin fixed to the ground. */
    GROUND,

    /** A pin on a block that slides along a fixed line: the block is one more link, with a prismatic pair to the ground. */
    SLIDER
}

data class LNode(
    val id: Int,
    val x: Double,
    val y: Double,
    val kind: NodeKind,
    /** For sliders: a point of the guide line and its direction. */
    val lineX: Double = x,
    val lineY: Double = y,
    val lineAngle: Double = 0.0
)

/** A rigid link through [nodes]: two make a bar, three or more a plate (ternary, quaternary...). */
data class LBody(val id: Int, val nodes: List<Int>, val lengths: Map<Pair<Int, Int>, Double>)

/** A motor turning [body] about the ground pin [pivot]. */
data class LMotor(val body: Int, val pivot: Int)

/** One kinematic pair found while counting: where, what kind and which links it joins. */
data class PairInfo(val node: Int, val prismatic: Boolean, val joins: Pair<Int, Int>)

/** Joint letters and link numbers are what the screen shows: ground is link 1, as in most textbooks. */
data class GrueblerCount(
    /** Moving bodies in the order they were drawn, each with its link number (2, 3, ...). */
    val bodyNumbers: Map<Int, Int>,
    /** Slider blocks: node id to link number. */
    val sliderNumbers: Map<Int, Int>,
    /** Bodies fixed to the ground (two or more ground pins): part of link 1. */
    val groundBodies: Set<Int>,
    val links: Int,
    val pairs: List<PairInfo>,
    /** Nodes where 3 or more links meet: k links, k − 1 pairs. */
    val multiple: Map<Int, Int>,
    val hasGround: Boolean
) {
    val j1 get() = pairs.size
    val dof get() = 3 * (links - 1) - 2 * j1

    /** The same chain read as spatial with every pair of 1 DOF (Grübler–Kutzbach), to show why planar ones need the planar formula. */
    val spatialDof get() = 6 * (links - 1) - 5 * j1
}

data class LinkageModel(val nodes: List<LNode> = emptyList(), val bodies: List<LBody> = emptyList(), val motor: LMotor? = null, val nextId: Int = 1) {
    fun node(id: Int) = nodes.first { it.id == id }
    fun nodeOrNull(id: Int) = nodes.firstOrNull { it.id == id }

    /** Letter shown for a node: A, B, ... Z, A2, B2... */
    fun letter(id: Int): String {
        val i = nodes.indexOfFirst { it.id == id }
        return ('A' + i % 26).toString() + if (i >= 26) (i / 26 + 1).toString() else ""
    }

    /**
     * Points that can't move: ground pins, plus every point of a body that already has two fixed
     * points (a rigid body pinned at two places is part of the ground), repeated until nothing changes.
     */
    private val grounded: Set<Int> by lazy {
        val fixed = nodes.filter { it.kind == NodeKind.GROUND }.map { it.id }.toMutableSet()
        do {
            val before = fixed.size
            bodies.forEach { b -> if (b.nodes.count { it in fixed } >= 2) fixed.addAll(b.nodes) }
        } while (fixed.size != before)
        fixed
    }

    fun isFixed(id: Int) = id in grounded

    fun isGroundBody(b: LBody) = b.nodes.count { it in grounded } >= 2

    // --- Editing ---

    fun addNode(x: Double, y: Double, kind: NodeKind): Pair<LinkageModel, Int> {
        val n = LNode(nextId, x, y, kind)
        return copy(nodes = nodes + n, nextId = nextId + 1) to n.id
    }

    /** A bar between two existing nodes (none if they're already in one body together). */
    fun addBar(a: Int, b: Int): LinkageModel {
        if (a == b || bodies.any { a in it.nodes && b in it.nodes }) return this
        val body = LBody(nextId, listOf(a, b), mapOf(key(a, b) to dist(a, b)))
        return copy(bodies = bodies + body, nextId = nextId + 1)
    }

    /** Welds two bodies that share a node into one rigid body (a ternary link from two bars). */
    fun weld(first: Int, second: Int): LinkageModel {
        val a = bodies.firstOrNull { it.id == first } ?: return this
        val b = bodies.firstOrNull { it.id == second } ?: return this
        if (a.id == b.id || a.nodes.none { it in b.nodes }) return this
        val all = (a.nodes + b.nodes).distinct()
        val merged = LBody(a.id, all, restLengths(all))
        val motor = motor?.let { m -> if (m.body == b.id) m.copy(body = a.id) else m }
        return copy(bodies = bodies.filter { it.id != b.id }.map { if (it.id == a.id) merged else it }, motor = motor)
    }

    /** Puts the motor on [bodyId] about one of its ground pins; null if it has none. */
    fun withMotor(bodyId: Int): LinkageModel? {
        val body = bodies.firstOrNull { it.id == bodyId } ?: return null
        if (isGroundBody(body)) return null
        val pivot = body.nodes.firstOrNull { node(it).kind == NodeKind.GROUND } ?: return null
        return copy(motor = LMotor(bodyId, pivot))
    }

    fun setKind(id: Int, kind: NodeKind): LinkageModel = copy(
        nodes = nodes.map { if (it.id == id) it.copy(kind = kind, lineX = it.x, lineY = it.y, lineAngle = if (kind == NodeKind.SLIDER) it.lineAngle else 0.0) else it }
    ).dropInvalidMotor()

    /** Turns a slider's guide 45° (horizontal, diagonal, vertical, other diagonal). */
    fun rotateGuide(id: Int): LinkageModel = copy(
        nodes = nodes.map { if (it.id == id) it.copy(lineX = it.x, lineY = it.y, lineAngle = (it.lineAngle + Math.PI / 4) % Math.PI) else it }
    )

    fun deleteNode(id: Int): LinkageModel {
        val kept = bodies.mapNotNull { b ->
            val rest = b.nodes - id
            when {
                rest.size == b.nodes.size -> b
                rest.size >= 2 -> LBody(b.id, rest, b.lengths.filterKeys { it.first != id && it.second != id })
                else -> null
            }
        }
        return copy(nodes = nodes.filter { it.id != id }, bodies = kept).dropInvalidMotor()
    }

    fun deleteBody(id: Int) = copy(bodies = bodies.filter { it.id != id }).dropInvalidMotor()

    /** Geometry editing: moves a node and re-measures the bodies through it. */
    fun moveFreely(id: Int, x: Double, y: Double): LinkageModel {
        val moved = copy(nodes = nodes.map { if (it.id == id) it.copy(x = x, y = y, lineX = x, lineY = y) else it })
        return moved.copy(bodies = moved.bodies.map { b -> if (id in b.nodes) b.copy(lengths = moved.restLengths(b.nodes)) else b })
    }

    private fun dropInvalidMotor(): LinkageModel {
        val m = motor ?: return this
        val body = bodies.firstOrNull { it.id == m.body }
        val ok = body != null && m.pivot in body.nodes && nodeOrNull(m.pivot)?.kind == NodeKind.GROUND && !isGroundBody(body)
        return if (ok) this else copy(motor = null)
    }

    fun dist(a: Int, b: Int): Double {
        val p = node(a)
        val q = node(b)
        return hypot(p.x - q.x, p.y - q.y)
    }

    fun restLengths(ids: List<Int>): Map<Pair<Int, Int>, Double> {
        val out = HashMap<Pair<Int, Int>, Double>()
        for (i in ids.indices) for (j in i + 1 until ids.size) out[key(ids[i], ids[j])] = dist(ids[i], ids[j])
        return out
    }

    // --- Counting (Grübler) ---

    fun count(): GrueblerCount {
        val ground = bodies.filter { isGroundBody(it) }.map { it.id }.toSet()
        val moving = bodies.filter { it.id !in ground }
        val bodyNumbers = moving.mapIndexed { i, b -> b.id to i + 2 }.toMap()
        val sliders = nodes.filter { it.kind == NodeKind.SLIDER }
        val sliderNumbers = sliders.mapIndexed { i, n -> n.id to moving.size + i + 2 }.toMap()
        val pairs = mutableListOf<PairInfo>()
        val multiple = HashMap<Int, Int>()
        for (n in nodes) {
            // The links that meet at this node, in a fixed order: ground or slider block first, then bodies.
            val here = buildList {
                if (isFixed(n.id)) add(1) // a fixed point is joined to the ground link
                if (n.kind == NodeKind.SLIDER) add(sliderNumbers.getValue(n.id))
                moving.filter { n.id in it.nodes }.forEach { add(bodyNumbers.getValue(it.id)) }
            }.distinct()
            for (k in 1 until here.size) pairs.add(PairInfo(n.id, false, here[0] to here[k]))
            if (here.size >= 3) multiple[n.id] = here.size
            if (n.kind == NodeKind.SLIDER) pairs.add(PairInfo(n.id, true, sliderNumbers.getValue(n.id) to 1))
        }
        val hasGround = nodes.any { it.kind != NodeKind.FREE }
        return GrueblerCount(bodyNumbers, sliderNumbers, ground, 1 + moving.size + sliders.size, pairs, multiple, hasGround)
    }

    /**
     * Grashof's condition when the drawing is exactly a four-bar linkage (four links, four pins):
     * shortest + longest ≤ the other two means at least one link can turn all the way round.
     */
    fun grashof(): Pair<List<Double>, Boolean>? {
        val c = count()
        if (c.links != 4 || c.j1 != 4 || c.sliderNumbers.isNotEmpty() || c.bodyNumbers.size != 3) return null
        val moving = bodies.filter { it.id in c.bodyNumbers }
        if (moving.any { it.nodes.size != 2 }) return null
        val grounds = nodes.filter { it.kind == NodeKind.GROUND }
        if (grounds.size != 2) return null
        val lengths = moving.map { it.lengths.values.first() } + hypot(grounds[0].x - grounds[1].x, grounds[0].y - grounds[1].y)
        val sorted = lengths.sorted()
        return lengths to (sorted[0] + sorted[3] <= sorted[1] + sorted[2] + 1e-9)
    }

    companion object {
        fun key(a: Int, b: Int) = if (a < b) a to b else b to a
    }
}

/**
 * Moves a mechanism while keeping every link rigid: position-based constraint projection, which
 * converges from the previous pose and lets the user drag any joint (the rest follows as closely
 * as the links allow). Fixed pins never move; slider pins stay on their guide.
 */
object LinkageSolver {
    data class Result(val model: LinkageModel, val error: Double) {
        /** Largest stretch of any link, relative to its length. */
        val ok get() = error < 1e-3
    }

    fun solve(model: LinkageModel, pinned: Map<Int, Pair<Double, Double>> = emptyMap(), iterations: Int = 120): Result {
        val ids = model.nodes.map { it.id }
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val xs = DoubleArray(ids.size) { model.nodes[it].x }
        val ys = DoubleArray(ids.size) { model.nodes[it].y }
        val fixed = BooleanArray(ids.size) { i -> model.isFixed(model.nodes[i].id) }
        pinned.forEach { (id, p) ->
            index[id]?.let { i ->
                xs[i] = p.first
                ys[i] = p.second
                fixed[i] = true
            }
        }
        val constraints = model.bodies.filterNot { model.isGroundBody(it) }.flatMap { b -> b.lengths.map { (k, len) -> Triple(index.getValue(k.first), index.getValue(k.second), len) } }
        val sliders = model.nodes.withIndex().filter { it.value.kind == NodeKind.SLIDER && !fixed[it.index] }
        fun project() = sliders.forEach { (i, n) ->
            val ux = cos(n.lineAngle)
            val uy = sin(n.lineAngle)
            val t = (xs[i] - n.lineX) * ux + (ys[i] - n.lineY) * uy
            xs[i] = n.lineX + t * ux
            ys[i] = n.lineY + t * uy
        }
        project()
        repeat(iterations) {
            for ((a, b, len) in constraints) {
                val wa = if (fixed[a]) 0.0 else 1.0
                val wb = if (fixed[b]) 0.0 else 1.0
                if (wa + wb == 0.0) continue
                val dx = xs[b] - xs[a]
                val dy = ys[b] - ys[a]
                val d = max(hypot(dx, dy), 1e-9)
                val diff = (d - len) / d / (wa + wb)
                xs[a] += dx * diff * wa
                ys[a] += dy * diff * wa
                xs[b] -= dx * diff * wb
                ys[b] -= dy * diff * wb
            }
            project()
        }
        var error = 0.0
        for ((a, b, len) in constraints) error = max(error, abs(hypot(xs[b] - xs[a], ys[b] - ys[a]) - len) / max(len, 1e-9))
        val nodes = model.nodes.mapIndexed { i, n -> n.copy(x = xs[i], y = ys[i]) }
        return Result(model.copy(nodes = nodes), error)
    }

    /**
     * Drags [id] toward ([x], [y]): first with the node pinned to the finger, then with it free so
     * every link gets back its exact length (a rigid structure snaps back; a mechanism follows).
     */
    fun drag(model: LinkageModel, id: Int, x: Double, y: Double): LinkageModel {
        val n = model.nodeOrNull(id) ?: return model
        if (model.isFixed(id)) return model
        val pulled = solve(model, mapOf(id to (x to y))).model
        val settled = solve(pulled)
        return if (settled.ok) settled.model else model
    }

    /** Turns the motor by [delta] radians; null when the links can't follow (a dead point: reverse). */
    fun turn(model: LinkageModel, delta: Double): LinkageModel? {
        val m = model.motor ?: return null
        val body = model.bodies.firstOrNull { it.id == m.body } ?: return null
        if (model.isGroundBody(body)) return null // welded to the ground since: it can't turn
        val pivot = model.node(m.pivot)
        val c = cos(delta)
        val s = sin(delta)
        val pins = body.nodes.filter { it != m.pivot }.associateWith { id ->
            val p = model.node(id)
            val dx = p.x - pivot.x
            val dy = p.y - pivot.y
            (pivot.x + c * dx - s * dy) to (pivot.y + s * dx + c * dy)
        }
        val r = solve(model, pins)
        return if (r.ok) r.model else null
    }

    /** Current motor angle (of the first other node of its body about the pivot), for the formula panel. */
    fun motorAngle(model: LinkageModel): Double? {
        val m = model.motor ?: return null
        val body = model.bodies.firstOrNull { it.id == m.body } ?: return null
        val other = model.node(body.nodes.first { it != m.pivot })
        val pivot = model.node(m.pivot)
        return atan2(other.y - pivot.y, other.x - pivot.x)
    }
}

/** Ready-made mechanisms to start from, each a classic example for counting degrees of freedom. */
enum class LinkagePreset { FOUR_BAR, COUPLER_CURVE, SLIDER_CRANK, FIVE_BAR, ARM_2R, TRUSS, OVERCONSTRAINED, STEPHENSON }

object LinkagePresets {
    private class Builder {
        var model = LinkageModel()
        val ids = HashMap<String, Int>()
        fun node(name: String, x: Double, y: Double, kind: NodeKind = NodeKind.FREE, guide: Double = 0.0) {
            val (m, id) = model.addNode(x, y, kind)
            model = if (kind == NodeKind.SLIDER) m.copy(nodes = m.nodes.map { if (it.id == id) it.copy(lineAngle = guide) else it }) else m
            ids[name] = id
        }
        fun bar(a: String, b: String) {
            model = model.addBar(ids.getValue(a), ids.getValue(b))
        }
        fun plate(vararg names: String) {
            for (i in 1 until names.size) bar(names[0], names[i])
            val first = model.bodies.first { ids.getValue(names[0]) in it.nodes && ids.getValue(names[1]) in it.nodes }.id
            for (i in 2 until names.size) {
                val other = model.bodies.first { ids.getValue(names[0]) in it.nodes && ids.getValue(names[i]) in it.nodes && it.id != first }.id
                model = model.weld(first, other)
            }
        }
        fun motor(a: String, b: String) {
            val body = model.bodies.first { ids.getValue(a) in it.nodes && ids.getValue(b) in it.nodes }
            model = model.withMotor(body.id) ?: model
        }
    }

    fun build(preset: LinkagePreset): LinkageModel = Builder().apply {
        when (preset) {
            LinkagePreset.FOUR_BAR -> {
                node("A", -2.0, -1.0, NodeKind.GROUND)
                node("B", -2.0 + cos(1.05), -1.0 + sin(1.05))
                node("C", 1.2, 1.3)
                node("D", 2.0, -1.0, NodeKind.GROUND)
                bar("A", "B"); bar("B", "C"); bar("C", "D")
                motor("A", "B")
            }
            LinkagePreset.COUPLER_CURVE -> {
                node("A", -2.0, -1.5, NodeKind.GROUND)
                node("B", -2.0 + cos(1.05), -1.5 + sin(1.05))
                node("C", 1.2, 0.8)
                node("D", 2.0, -1.5, NodeKind.GROUND)
                node("P", -0.6, 2.4)
                bar("A", "B"); plate("B", "C", "P"); bar("C", "D")
                motor("A", "B")
            }
            LinkagePreset.SLIDER_CRANK -> {
                node("A", -2.0, 0.0, NodeKind.GROUND)
                node("B", -2.0 + 1.2 * cos(0.785), 1.2 * sin(0.785))
                node("C", -2.0 + 1.2 * cos(0.785) + sqrt(9.0 - (1.2 * sin(0.785)).let { it * it }), 0.0, NodeKind.SLIDER, 0.0)
                bar("A", "B"); bar("B", "C")
                motor("A", "B")
            }
            LinkagePreset.FIVE_BAR -> {
                node("A", -2.0, -1.0, NodeKind.GROUND)
                node("B", -2.2, 0.6)
                node("C", 0.0, 2.0)
                node("D", 2.2, 0.6)
                node("E", 2.0, -1.0, NodeKind.GROUND)
                bar("A", "B"); bar("B", "C"); bar("C", "D"); bar("D", "E")
                motor("A", "B")
            }
            LinkagePreset.ARM_2R -> {
                node("A", -1.0, -1.5, NodeKind.GROUND)
                node("B", 0.2, 0.2)
                node("C", 2.0, 0.8)
                bar("A", "B"); bar("B", "C")
                motor("A", "B")
            }
            LinkagePreset.TRUSS -> {
                node("A", -1.8, -1.0, NodeKind.GROUND)
                node("B", 1.8, -1.0, NodeKind.GROUND)
                node("C", 0.0, 1.6)
                bar("A", "C"); bar("B", "C")
            }
            LinkagePreset.OVERCONSTRAINED -> {
                node("A", -1.8, -1.2, NodeKind.GROUND)
                node("B", 1.8, -1.2, NodeKind.GROUND)
                node("C", -1.0, 1.2)
                node("D", 1.0, 1.2)
                bar("A", "C"); bar("B", "D"); bar("C", "D"); bar("A", "D"); bar("B", "C")
            }
            LinkagePreset.STEPHENSON -> {
                node("A", -3.0, -1.5, NodeKind.GROUND)
                node("B", -3.0 + cos(1.22), -1.5 + sin(1.22))
                node("C", -0.3, 0.6)
                node("D", 0.0, -1.5, NodeKind.GROUND)
                node("E", 0.5, 1.8)
                node("F", 3.8, 1.703)
                node("G", 3.0, -1.5, NodeKind.GROUND)
                bar("A", "B"); plate("B", "C", "E"); bar("D", "C"); bar("E", "F"); bar("F", "G")
                motor("A", "B")
            }
        }
    }.model
}
