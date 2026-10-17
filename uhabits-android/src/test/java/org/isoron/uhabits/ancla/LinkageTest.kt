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

import org.isoron.uhabits.ancla.robotics.LinkageModel
import org.isoron.uhabits.ancla.robotics.LinkagePreset
import org.isoron.uhabits.ancla.robotics.LinkagePresets
import org.isoron.uhabits.ancla.robotics.LinkageSolver
import org.isoron.uhabits.ancla.robotics.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class LinkageTest {
    private fun dof(p: LinkagePreset) = LinkagePresets.build(p).count()

    @Test
    fun grueblerOfTheClassicExamples() {
        val expected = mapOf(
            LinkagePreset.FOUR_BAR to Triple(4, 4, 1),
            LinkagePreset.COUPLER_CURVE to Triple(4, 4, 1),
            LinkagePreset.SLIDER_CRANK to Triple(4, 4, 1),
            LinkagePreset.FIVE_BAR to Triple(5, 5, 2),
            LinkagePreset.ARM_2R to Triple(3, 2, 2),
            LinkagePreset.TRUSS to Triple(3, 3, 0),
            LinkagePreset.OVERCONSTRAINED to Triple(6, 8, -1),
            LinkagePreset.STEPHENSON to Triple(6, 7, 1)
        )
        expected.forEach { (preset, want) ->
            val c = dof(preset)
            assertEquals("$preset", want, Triple(c.links, c.j1, c.dof))
        }
        assertEquals(1, dof(LinkagePreset.SLIDER_CRANK).pairs.count { it.prismatic })
        assertEquals(-2, dof(LinkagePreset.FOUR_BAR).spatialDof)
    }

    @Test
    fun threeLinksOnOnePinAreTwoPairs() {
        var m = LinkageModel()
        val ids = (0 until 4).map { i ->
            val (next, id) = m.addNode(i.toDouble(), (i % 2).toDouble(), if (i == 0) NodeKind.GROUND else NodeKind.FREE)
            m = next
            id
        }
        m = m.addBar(ids[0], ids[1]).addBar(ids[1], ids[2]).addBar(ids[1], ids[3])
        val c = m.count()
        assertEquals(3, c.multiple[ids[1]])
        assertEquals(3, c.pairs.size) // one at the ground pin, two at the triple pin
    }

    @Test
    fun aBarBetweenTwoFixedPinsIsGround() {
        var m = LinkagePresets.build(LinkagePreset.TRUSS)
        val grounds = m.nodes.filter { it.kind == NodeKind.GROUND }.map { it.id }
        m = m.addBar(grounds[0], grounds[1])
        assertEquals(0, m.count().dof)
        assertEquals(1, m.count().groundBodies.size)
    }

    @Test
    fun crankRockerTurnsAllTheWayKeepingLengths() {
        for (preset in listOf(LinkagePreset.FOUR_BAR, LinkagePreset.COUPLER_CURVE, LinkagePreset.SLIDER_CRANK, LinkagePreset.STEPHENSON)) {
            var m = LinkagePresets.build(preset)
            assertTrue(m.grashof()?.second ?: true)
            repeat(360) { m = LinkageSolver.turn(m, 2 * PI / 360) ?: error("$preset stuck at step $it") }
            m.bodies.forEach { b -> b.lengths.forEach { (k, len) -> assertEquals(len, hypot(m.node(k.first).x - m.node(k.second).x, m.node(k.first).y - m.node(k.second).y), 1e-3 * len) } }
            m.nodes.filter { it.kind == NodeKind.SLIDER }.forEach { assertEquals(0.0, it.y, 1e-6) }
        }
    }

    @Test
    fun aTrussDoesNotMoveAndADeadPointIsReported() {
        val truss = LinkagePresets.build(LinkagePreset.TRUSS)
        val top = truss.nodes.first { it.kind == NodeKind.FREE }
        val dragged = LinkageSolver.drag(truss, top.id, top.x + 1, top.y + 1)
        assertEquals(top.x, dragged.node(top.id).x, 1e-3)
        assertEquals(top.y, dragged.node(top.id).y, 1e-3)
        // Double rocker: the input can't turn all the way, so the solver refuses at some angle.
        var m = LinkageModel()
        val a = m.addNode(0.0, 0.0, NodeKind.GROUND).also { m = it.first }.second
        val b = m.addNode(0.0, 2.0, NodeKind.FREE).also { m = it.first }.second
        val c = m.addNode(1.0, 2.0, NodeKind.FREE).also { m = it.first }.second
        val d = m.addNode(1.0, 0.0, NodeKind.GROUND).also { m = it.first }.second
        m = m.addBar(a, b).addBar(b, c).addBar(c, d)
        m = m.withMotor(m.bodies.first { a in it.nodes }.id)!!
        var stopped = false
        repeat(360) {
            val next = LinkageSolver.turn(m, PI / 180)
            if (next == null) stopped = true else m = next
        }
        assertTrue(stopped)
        assertNull(LinkagePresets.build(LinkagePreset.TRUSS).withMotor(-1))
    }
}
