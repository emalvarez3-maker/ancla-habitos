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

package org.isoron.uhabits.ancla.reading

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The notes graph: every note is a node, [[links]] and labeled connections are edges. It lays
 * itself out (nodes repel, links pull), zooms and pans; tapping a node shows it and its
 * neighbours, and the card opens the note.
 */
@Composable
fun NotesGraph(ideas: List<Idea>, links: List<IdeaLink>, onOpen: (Idea) -> Unit, onAskDexter: () -> Unit) {
    val c = Ancla.colors
    val positions = remember { mutableStateMapOf<String, Offset>() }
    var selected by remember { mutableStateOf<String?>(null) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // Until the user pans or zooms, the view frames every node; [view] holds the zoom and pan last drawn.
    var userMoved by remember { mutableStateOf(false) }
    val view = remember { floatArrayOf(1f, 0f, 0f) }
    val degree = remember(links) { links.flatMap { listOf(it.from, it.to) }.groupingBy { it }.eachCount() }
    val colors = ideas.associate { it.id to kindColor(it.kind) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = c.ink, textAlign = TextAlign.Center)
    val relationStyle = TextStyle(fontSize = 10.sp, color = c.muted)

    LaunchedEffect(ideas.map { it.id }, links) {
        ideas.forEach { i -> positions.getOrPut(i.id) { Offset(Random.nextFloat() * 400 - 200, Random.nextFloat() * 400 - 200) } }
        positions.keys.retainAll(ideas.map { it.id }.toSet())
        var temperature = 40f
        val k = 140f
        while (temperature > 0.4f) {
            withFrameNanos { }
            val ids = positions.keys.toList()
            val force = ids.associateWith { Offset.Zero }.toMutableMap()
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val d = positions[ids[i]]!! - positions[ids[j]]!!
                val dist = max(d.getDistance(), 1f)
                if (dist > k * 3) continue // far-apart groups stop pushing, so the view stays compact
                val push = d / dist * (k * k / dist)
                force[ids[i]] = force[ids[i]]!! + push
                force[ids[j]] = force[ids[j]]!! - push
            }
            links.forEach { l ->
                val a = positions[l.from] ?: return@forEach
                val b = positions[l.to] ?: return@forEach
                val d = b - a
                val dist = max(d.getDistance(), 1f)
                val pull = d / dist * (dist * dist / k)
                force[l.from] = force[l.from]!! + pull
                force[l.to] = force[l.to]!! - pull
            }
            ids.forEach { id ->
                val p = positions[id]!!
                val f = force[id]!! - p * 0.12f
                val len = max(f.getDistance(), 0.001f)
                positions[id] = p + f / len * min(len, temperature)
            }
            temperature *= 0.985f
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, move, scale, _ ->
                        if (!userMoved) {
                            zoom = view[0]
                            pan = Offset(view[1], view[2])
                            userMoved = true
                        }
                        pan += move
                        zoom = (zoom * scale).coerceIn(0.2f, 3f)
                    }
                }
                .pointerInput(ideas) {
                    detectTapGestures { tap ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val world = (tap - center - Offset(view[1], view[2])) / view[0]
                        selected = positions.entries.minByOrNull { (it.value - world).getDistance() }
                            ?.takeIf { (it.value - world).getDistance() < 48.dp.toPx() / view[0] }?.key
                    }
                }
        ) {
            val (z, shift) = if (userMoved || positions.isEmpty()) zoom to pan else {
                val xs = positions.values.map { it.x }
                val ys = positions.values.map { it.y }
                val fit = minOf(1.2f, size.width * 0.8f / (xs.max() - xs.min() + 160f), size.height * 0.6f / (ys.max() - ys.min() + 160f))
                fit to -Offset((xs.max() + xs.min()) / 2f, (ys.max() + ys.min()) / 2f) * fit
            }
            view[0] = z
            view[1] = shift.x
            view[2] = shift.y
            val center = Offset(size.width / 2f, size.height / 2f) + shift
            fun screen(p: Offset) = center + p * z
            val focus = selected?.let { s -> links.filter { it.from == s || it.to == s }.flatMap { listOf(it.from, it.to) }.toSet() + s }
            links.forEach { l ->
                val a = positions[l.from] ?: return@forEach
                val b = positions[l.to] ?: return@forEach
                val hot = focus != null && (l.from == selected || l.to == selected)
                drawLine(
                    color = if (hot) c.lilac else c.track,
                    start = screen(a),
                    end = screen(b),
                    strokeWidth = (if (hot) 3.dp else 1.5.dp).toPx()
                )
                if (hot && l.relation.isNotBlank()) {
                    val layout = measurer.measure(l.relation, relationStyle)
                    drawText(layout, topLeft = (screen(a) + screen(b)) / 2f - Offset(layout.size.width / 2f, layout.size.height / 2f))
                }
            }
            ideas.forEach { idea ->
                val p = positions[idea.id] ?: return@forEach
                val r = (12 + 3 * min(degree[idea.id] ?: 0, 6)).dp.toPx() * z.coerceIn(0.6f, 1.4f)
                val dim = focus != null && idea.id !in focus
                val color = colors[idea.id] ?: c.lilac
                drawCircle(color.copy(alpha = if (dim) 0.25f else 0.9f), r, screen(p))
                if (idea.id == selected) drawCircle(c.ink, r + 5.dp.toPx(), screen(p), style = Stroke(2.dp.toPx()))
                if (z > 0.35f) {
                    val layout = measurer.measure(
                        idea.title, labelStyle.copy(color = if (dim) c.muted.copy(alpha = 0.5f) else c.ink),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        constraints = Constraints(maxWidth = 130.dp.roundToPx())
                    )
                    drawText(layout, topLeft = screen(p) + Offset(-layout.size.width / 2f, r + 4.dp.toPx()))
                }
            }
        }

        if (ideas.isEmpty()) {
            Text(
                stringResource(R.string.ideas_empty), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft,
                textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ideas.firstOrNull { it.id == selected }?.let { idea ->
                Row(
                    Modifier.fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(c.card)
                        .combinedClickableCompat(Role.Button, { onOpen(idea) }).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(kindColor(idea.kind)))
                    Column(Modifier.weight(1f)) {
                        Text(idea.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(idea.note.replace("[[", "").replace("]]", ""), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.notes_open), style = MaterialTheme.typography.labelLarge, color = c.lilacInk)
                }
            }
            Box(
                Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(c.ink)
                    .combinedClickableCompat(Role.Button, onAskDexter).padding(14.dp),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.ideas_ask_dexter), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
        }
    }
}
