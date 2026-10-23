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

package org.isoron.uhabits.ancla.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.Goal
import org.isoron.uhabits.ancla.GoalStore
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Camino: the user's three-year roadmap as a path of light that winds up from today to the
 * third year, drawing itself in when opened. Each goal sits on its year; Dexter stands as far up the
 * path as the goals' progress has come. Goals are added here or by telling Dexter, and the screen
 * picks up what Dexter adds while it is open.
 */
@Composable
fun CaminoScreen(store: GoalStore, openMenu: () -> Unit, onDexter: () -> Unit = {}, onAskDexter: (String) -> Unit = {}) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var goals by remember { mutableStateOf(emptyList<Goal>()) }
    suspend fun reload() { goals = withContext(Dispatchers.IO) { runCatching { store.all() }.getOrDefault(emptyList()) } }
    LaunchedEffect(Unit) {
        // Dexter may change the goals while this is open: reload only when the store says they changed.
        var seen = Long.MIN_VALUE
        while (true) {
            val version = withContext(Dispatchers.IO) { runCatching { store.version() }.getOrDefault(seen - 1) }
            if (version != seen) {
                reload()
                seen = version
            }
            delay(2500)
        }
    }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Several roadmaps, one per area of life: the path shows the one chosen (or all of them).
    var roadmap by rememberSaveable { mutableStateOf<String?>(null) }
    val roadmaps = goals.map { it.roadmap.ifBlank { GoalStore.GENERAL } }.distinct()
        .sortedBy { GoalStore.ROADMAPS.indexOf(it).let { i -> if (i < 0) 99 else i } }
    val shown = goals.filter { roadmap == null || it.roadmap.ifBlank { GoalStore.GENERAL } == roadmap }

    val thisYear = remember { LocalDate.now().year }
    val years = (thisYear..thisYear + 3).toList()
    fun yearOf(g: Goal) = g.year.coerceIn(thisYear, thisYear + 3)
    val byYear = years.associateWith { y -> shown.filter { yearOf(it) == y } }
    val reached = if (shown.isEmpty()) 0.04f else (shown.sumOf { it.effectiveProgress } / (shown.size * 100f)).coerceIn(0.04f, 1f)

    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }

    val open = goals.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        GoalDetail(open, store, onBack = { openId = null }, onEdit = { editing = open }, onDexter = onAskDexter, changed = { scope.launch { reload() } })
    } else
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PlaceHeader(R.string.camino_caption, R.string.nav_camino, openMenu) }
            Text(
                stringResource(R.string.camino_add), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clip(CircleShape).background(c.lilac).combinedClickableCompat(Role.Button, { adding = true }).padding(horizontal = 16.dp, vertical = 11.dp)
            )
        }
        Text(
            stringResource(R.string.camino_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)
        )
        if (roadmaps.size > 1 || roadmap != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                RoadmapChip(stringResource(R.string.camino_all), roadmap == null) { roadmap = null }
                roadmaps.forEach { r -> RoadmapChip(r, roadmap == r) { roadmap = r } }
            }
        }

        val maxCards = byYear.values.maxOf { it.size }
        val sectionDp = max(170, 64 + maxCards * 76).dp
        BoxWithConstraints(Modifier.fillMaxWidth().height(sectionDp * years.size)) {
            val density = LocalDensity.current
            val wPx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { maxHeight.toPx() }
            val path = remember(wPx, hPx) { caminoPath(wPx, hPx, years.size) }
            val measure = remember(path) { PathMeasure().apply { setPath(path, false) } }
            Canvas(Modifier.fillMaxSize()) {
                val total = measure.length
                drawPath(path, c.track, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round))
                val lit = Path()
                measure.getSegment(0f, total * reached * draw.value, lit, true)
                drawPath(lit, c.lilac, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round))
                drawPath(lit, c.amber.copy(alpha = 0.55f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                drawPath(
                    path, c.inkSoft.copy(alpha = 0.35f),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 18f)))
                )
            }
            // Years from the top (the farthest) down to this year at the bottom.
            Column(Modifier.fillMaxSize()) {
                years.reversed().forEachIndexed { i, y ->
                    val rightSide = i % 2 == 0
                    Column(
                        Modifier.fillMaxWidth().height(sectionDp).padding(top = 8.dp),
                        horizontalAlignment = if (rightSide) Alignment.End else Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            yearLabel(y, thisYear), style = MaterialTheme.typography.labelLarge,
                            color = if (y == thisYear + 3) c.amber else c.lilacInk,
                            modifier = Modifier.clip(CircleShape).background(c.ground).padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        byYear.getValue(y).forEach { g -> GoalCard(g) { openId = g.id } }
                        if (byYear.getValue(y).isEmpty() && y == thisYear + 3) {
                            Text(
                                stringResource(R.string.camino_big_goal_hint), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                                modifier = Modifier.width(220.dp).clip(RoundedCornerShape(18.dp)).background(c.card).padding(12.dp)
                            )
                        }
                    }
                }
            }
            // Dexter stands where the goals' progress has brought the user, on top of everything.
            val at = measure.getPosition(measure.length * reached * draw.value)
            val owl = 58.dp
            val owlPx = with(density) { owl.toPx() }
            DexterOwl(
                OwlState.IDLE,
                Modifier.size(owl).offset { IntOffset((at.x - owlPx / 2).roundToInt(), (at.y - owlPx * 0.85f).roundToInt()) },
                interactive = true
            )
        }
    }

    if (adding || editing != null) {
        GoalSheet(
            initial = editing,
            years = years,
            roadmaps = roadmaps,
            startRoadmap = roadmap,
            onDismiss = { adding = false; editing = null },
            onSave = { title, year, progress, done, road ->
                val old = editing
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (old == null) {
                                store.add(title, year, roadmap = road)
                            } else {
                                store.update(old.title, progress = progress, done = done, year = year, newTitle = title, roadmap = road)
                            }
                        }
                    }
                    reload()
                }
                adding = false
                editing = null
            },
            onDelete = { g ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { store.remove(g.title) } }
                    reload()
                }
                editing = null
            }
        )
    }
}

@Composable
private fun RoadmapChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.amber else c.card).combinedClickableCompat(Role.Tab, onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun yearLabel(year: Int, thisYear: Int) = when (val n = year - thisYear) {
    0 -> stringResource(R.string.camino_this_year, year)
    else -> stringResource(R.string.camino_year_n, year, n)
}

/** A serpentine from the bottom left, crossing the width once per year, up to the top. */
private fun caminoPath(w: Float, h: Float, sections: Int): Path {
    val top = h * 0.04f
    val bottom = h - h / sections * 0.12f
    val points = (0..sections).map { k ->
        val y = bottom - (bottom - top) * k / sections
        val x = if (k % 2 == 0) w * 0.2f else w * 0.8f
        Offset(x, y)
    }
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        for (k in 0 until sections) {
            val a = points[k]
            val b = points[k + 1]
            val d = (a.y - b.y) / 2f
            cubicTo(a.x, a.y - d, b.x, b.y + d, b.x, b.y)
        }
    }
}

@Composable
private fun GoalCard(goal: Goal, onClick: () -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.width(220.dp).clip(RoundedCornerShape(18.dp)).background(c.card).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            goal.title, style = MaterialTheme.typography.titleMedium, color = if (goal.done) c.mint else c.ink,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val progress = goal.effectiveProgress / 100f
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.track)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(if (goal.done) c.amber else c.mint))
        }
        Text(
            if (goal.done) stringResource(R.string.camino_done) else stringResource(R.string.camino_progress, goal.effectiveProgress) +
                (if (goal.milestones.isNotEmpty()) " · " + stringResource(R.string.goal_milestones_count, goal.milestones.count { it.done }, goal.milestones.size) else "") +
                (if (!goal.hasWoop) " · " + stringResource(R.string.goal_no_woop) else ""),
            style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(
    initial: Goal?,
    years: List<Int>,
    roadmaps: List<String>,
    startRoadmap: String?,
    onDismiss: () -> Unit,
    onSave: (title: String, year: Int, progress: Int?, done: Boolean?, roadmap: String) -> Unit,
    onDelete: (Goal) -> Unit
) {
    val c = Ancla.colors
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var roadmap by remember { mutableStateOf(initial?.roadmap?.ifBlank { null } ?: startRoadmap ?: GoalStore.GENERAL) }
    var year by remember { mutableIntStateOf(initial?.year?.coerceIn(years.first(), years.last()) ?: (years.first() + 1)) }
    var progress by remember { mutableFloatStateOf((initial?.progress ?: 0).toFloat()) }
    var done by remember { mutableStateOf(initial?.done ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (initial == null) R.string.camino_new else R.string.camino_edit), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            OutlinedTextField(
                value = title, onValueChange = { title = it }, singleLine = true,
                label = { Text(stringResource(R.string.camino_goal_label)) },
                placeholder = { Text(stringResource(R.string.camino_goal_hint)) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.camino_roadmap), style = MaterialTheme.typography.labelLarge, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (GoalStore.ROADMAPS + roadmaps).distinct().forEach { r ->
                    val on = r == roadmap
                    Text(
                        r, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.amber else c.ground).combinedClickableCompat(Role.RadioButton, { roadmap = r })
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                years.forEach { y ->
                    val on = y == year
                    Text(
                        y.toString(), style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.lilac else c.ground).combinedClickableCompat(Role.RadioButton, { year = y })
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            if (initial != null) {
                Text(stringResource(R.string.camino_progress, progress.roundToInt()), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Slider(
                    value = progress, onValueChange = { progress = it; if (it < 100f) done = false }, valueRange = 0f..100f, steps = 19,
                    colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.lilac, inactiveTrackColor = c.track)
                )
                Text(
                    stringResource(if (done) R.string.camino_done else R.string.camino_mark_done), style = MaterialTheme.typography.labelLarge,
                    color = if (done) c.onInk else c.mintInk,
                    modifier = Modifier.clip(CircleShape).background(if (done) c.mint else c.mintSoft).combinedClickableCompat(Role.Checkbox, { done = !done; if (done) progress = 100f })
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.camino_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(if (title.isBlank()) c.track else c.lilac)
                        .combinedClickableCompat(Role.Button, { if (title.isNotBlank()) onSave(title.trim(), year, if (initial != null) progress.roundToInt() else null, if (initial != null) done else null, roadmap) })
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                )
                if (initial != null) {
                    Text(
                        stringResource(if (confirmDelete) R.string.camino_delete_confirm else R.string.camino_delete), style = MaterialTheme.typography.labelLarge, color = c.amberInk,
                        modifier = Modifier.clip(CircleShape).background(c.ground).combinedClickableCompat(Role.Button, { if (confirmDelete) onDelete(initial) else confirmDelete = true })
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
