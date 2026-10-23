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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.Goal
import org.isoron.uhabits.ancla.GoalStore
import org.isoron.uhabits.ancla.Milestone

private enum class WoopPart { OUTCOME, OBSTACLE, PLAN }

/**
 * One important goal on its own: its WOOP (Wish, Outcome, Obstacle, Plan) as four cards to fill or
 * change, and its own roadmap of milestones as a path that lights up gold step by step. Dexter can
 * walk the user through the WOOP and add the milestones by voice.
 */
@Composable
fun GoalDetail(goal: Goal, store: GoalStore, onBack: () -> Unit, onEdit: () -> Unit, onDexter: (String) -> Unit, changed: () -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var poster by remember { mutableStateOf(false) }
    var woopEditing by remember { mutableStateOf<WoopPart?>(null) }
    var newMilestone by remember { mutableStateOf("") }
    var newMonth by remember { mutableStateOf("") }
    fun io(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { runCatching(block) }
        changed()
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    val progress by animateFloatAsState(goal.effectiveProgress / 100f, tween(700), label = "goal")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onBack).padding(horizontal = 14.dp, vertical = 2.dp)
            )
            Text(
                goal.year.toString(), style = MaterialTheme.typography.labelLarge, color = c.lilacInk,
                modifier = Modifier.clip(CircleShape).background(c.lilacSoft).padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Box(Modifier.weight(1f))
            Text(
                stringResource(R.string.goal_edit), style = MaterialTheme.typography.labelLarge, color = c.inkSoft,
                modifier = Modifier.clip(CircleShape).background(c.card).combinedClickableCompat(Role.Button, onEdit).padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
        Text(goal.title, style = MaterialTheme.typography.displaySmall, color = if (goal.done) c.amber else c.ink)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(c.track)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(10.dp).clip(CircleShape).background(if (goal.done) c.amber else c.mint))
            }
            Text(if (goal.done) stringResource(R.string.camino_done) else stringResource(R.string.camino_progress, goal.effectiveProgress), style = MaterialTheme.typography.titleMedium, color = c.ink)
        }

        // WOOP
        Text(stringResource(R.string.goal_woop_title), style = MaterialTheme.typography.headlineSmall, color = c.ink)
        WoopCard("W", stringResource(R.string.woop_wish), goal.title, null) {}
        WoopCard("O", stringResource(R.string.woop_outcome), goal.outcome, stringResource(R.string.woop_outcome_hint)) { woopEditing = WoopPart.OUTCOME }
        WoopCard("O", stringResource(R.string.woop_obstacle), goal.obstacle, stringResource(R.string.woop_obstacle_hint)) { woopEditing = WoopPart.OBSTACLE }
        WoopCard("P", stringResource(R.string.woop_plan), goal.plan, stringResource(R.string.woop_plan_hint)) { woopEditing = WoopPart.PLAN }
        val woopPrompt = stringResource(R.string.goal_woop_prompt, goal.title)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Dexter opens already asking about this goal's WOOP, one question at a time.
            Text(
                stringResource(R.string.goal_woop_with_dexter), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clip(CircleShape).background(c.lilac).combinedClickableCompat(Role.Button, { onDexter(woopPrompt) }).padding(horizontal = 18.dp, vertical = 12.dp)
            )
            Text(
                stringResource(R.string.goal_poster), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clip(CircleShape).background(c.amber).combinedClickableCompat(Role.Button, { poster = true }).padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        // The goal's own roadmap
        Text(stringResource(R.string.goal_roadmap), style = MaterialTheme.typography.headlineSmall, color = c.ink)
        if (goal.milestones.isEmpty()) {
            Text(stringResource(R.string.goal_roadmap_empty), style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        goal.milestones.forEachIndexed { i, m ->
            MilestoneRow(m, first = i == 0, last = i == goal.milestones.lastIndex,
                onToggle = { io { store.setMilestoneDone(goal.title, m.id, !m.done) } },
                onRemove = { io { store.removeMilestone(goal.title, m.id) } })
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = newMilestone, onValueChange = { newMilestone = it }, singleLine = true,
                label = { Text(stringResource(R.string.goal_add_milestone)) }, placeholder = { Text(stringResource(R.string.goal_milestone_hint)) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newMonth, onValueChange = { newMonth = it.take(7) }, singleLine = true,
                    label = { Text(stringResource(R.string.goal_month)) }, placeholder = { Text("${goal.year}-06") },
                    shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(R.string.camino_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(if (newMilestone.isBlank()) c.track else c.lilac)
                        .combinedClickableCompat(Role.Button, {
                            val title = newMilestone.trim()
                            val month = newMonth.trim().takeIf { it.isNotEmpty() }
                            if (title.isNotEmpty()) {
                                io { store.addMilestone(goal.title, title, month) }
                                newMilestone = ""
                                newMonth = ""
                            }
                        }).padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
        Text(stringResource(R.string.goal_dexter_hint, goal.title), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
    }

    if (poster) PosterDialog(goal) { poster = false }

    woopEditing?.let { part ->
        WoopSheet(
            label = stringResource(
                when (part) {
                    WoopPart.OUTCOME -> R.string.woop_outcome
                    WoopPart.OBSTACLE -> R.string.woop_obstacle
                    WoopPart.PLAN -> R.string.woop_plan
                }
            ),
            hint = stringResource(
                when (part) {
                    WoopPart.OUTCOME -> R.string.woop_outcome_hint
                    WoopPart.OBSTACLE -> R.string.woop_obstacle_hint
                    WoopPart.PLAN -> R.string.woop_plan_hint
                }
            ),
            initial = when (part) {
                WoopPart.OUTCOME -> goal.outcome
                WoopPart.OBSTACLE -> goal.obstacle
                WoopPart.PLAN -> goal.plan
            },
            onDismiss = { woopEditing = null },
            onSave = { text ->
                io {
                    when (part) {
                        WoopPart.OUTCOME -> store.setWoop(goal.title, text, null, null)
                        WoopPart.OBSTACLE -> store.setWoop(goal.title, null, text, null)
                        WoopPart.PLAN -> store.setWoop(goal.title, null, null, text)
                    }
                }
                woopEditing = null
            }
        )
    }
}

@Composable
private fun WoopCard(letter: String, label: String, text: String, hint: String?, onClick: () -> Unit) {
    val c = Ancla.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).combinedClickableCompat(Role.Button, onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.lilacSoft), contentAlignment = Alignment.Center) {
            Text(letter, style = MaterialTheme.typography.titleLarge, color = c.amber)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.lilacInk)
            Text(
                text.ifBlank { hint.orEmpty() }, style = MaterialTheme.typography.bodyLarge,
                color = if (text.isBlank()) c.muted else c.ink
            )
        }
    }
}

@Composable
private fun MilestoneRow(m: Milestone, first: Boolean, last: Boolean, onToggle: () -> Unit, onRemove: () -> Unit) {
    val c = Ancla.colors
    val glow by animateFloatAsState(if (m.done) 1f else 0f, tween(500), label = "milestone")
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The path: a line through the steps, each a node that glows gold once done.
        Canvas(Modifier.width(34.dp).fillMaxHeight()) {
            val x = size.width / 2
            val y = size.height / 2
            if (!first) drawLine(c.track, Offset(x, 0f), Offset(x, y), 5.dp.toPx(), StrokeCap.Round)
            if (!last) drawLine(c.track, Offset(x, y), Offset(x, size.height), 5.dp.toPx(), StrokeCap.Round)
            drawCircle(c.amber.copy(alpha = 0.25f * glow), 15.dp.toPx(), Offset(x, y))
            drawCircle(if (m.done) c.amber else c.card, 10.dp.toPx(), Offset(x, y))
            drawCircle(if (m.done) c.amber else c.lilac, 10.dp.toPx(), Offset(x, y), style = Stroke(2.5.dp.toPx()))
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).combinedClickableCompat(Role.Checkbox, onToggle).padding(vertical = 6.dp, horizontal = 4.dp)
        ) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, color = if (m.done) c.amber else c.ink)
            if (m.due.isNotEmpty()) Text(m.due, style = MaterialTheme.typography.labelMedium, color = c.muted)
        }
        Text(
            "✕", style = MaterialTheme.typography.labelLarge, color = c.muted,
            modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onRemove).padding(10.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WoopSheet(label: String, hint: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Ancla.colors
    var text by remember { mutableStateOf(initial) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(label, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            OutlinedTextField(
                value = text, onValueChange = { text = it }, minLines = 3,
                placeholder = { Text(hint) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.camino_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clip(CircleShape).background(c.lilac).combinedClickableCompat(Role.Button, { onSave(text.trim()) }).padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}

/** The goal's roadmap picture, to save, share or set as the home screen's wallpaper. */
@Composable
private fun PosterDialog(goal: Goal, onDismiss: () -> Unit) {
    val c = Ancla.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val bitmap = remember(goal) { RoadmapPoster.draw(goal) }
    var note by remember { mutableStateOf<String?>(null) }
    val saved = stringResource(R.string.goal_poster_saved)
    val wallpaper = stringResource(R.string.goal_poster_wallpaper_set)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(c.ground).statusBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onDismiss).padding(horizontal = 14.dp, vertical = 2.dp))
                Text(stringResource(R.string.goal_poster_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
            }
            androidx.compose.foundation.Image(
                bitmap.asImageBitmap(), contentDescription = goal.title,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.amber) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterButton(stringResource(R.string.goal_poster_save), c.card, c.ink) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { RoadmapPoster.save(context, goal, bitmap) }.getOrNull() }
                        if (ok != null) note = saved
                    }
                }
                PosterButton(stringResource(R.string.goal_poster_share), c.card, c.ink) {
                    scope.launch {
                        val uri = withContext(Dispatchers.IO) { runCatching { RoadmapPoster.save(context, goal, bitmap) }.getOrNull() }
                        uri?.let { RoadmapPoster.share(context, it) }
                    }
                }
            }
            PosterButton(stringResource(R.string.goal_poster_wallpaper), c.amber, c.onInk) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { RoadmapPoster.setWallpaper(context, bitmap) }.isSuccess }
                    if (ok) note = wallpaper
                }
            }
        }
    }
}

@Composable
private fun PosterButton(label: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 18.dp, vertical = 12.dp)
    )
}
