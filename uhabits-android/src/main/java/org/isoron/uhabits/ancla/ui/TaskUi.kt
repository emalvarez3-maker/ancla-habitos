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

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.core.ui.screens.today.TodayTaskItem
import java.util.Locale

/** What the task editor edits; [id] is null for a new task. */
data class TaskDraft(
    val id: Long? = null,
    val title: String = "",
    val notes: String = "",
    val minuteOfDay: Int? = null,
    val remind: Boolean = true
)

private fun hhmm(minute: Int) = String.format(Locale.getDefault(), "%02d:%02d", minute / 60, minute % 60)

@Composable
fun TaskRow(item: TodayTaskItem, modifier: Modifier, onToggle: (TodayTaskItem) -> Unit, onEdit: (TodayTaskItem) -> Unit) {
    val c = Ancla.colors
    val checkBg by animateColorAsState(if (item.isDone) c.lilac else Color.Transparent, label = "taskCheckBg")
    val checkBorder by animateColorAsState(if (item.isDone) c.lilac else c.track, label = "taskCheckBorder")
    val rowBg by animateColorAsState(if (item.isDone) c.cardMuted else c.card, label = "taskRowBg")
    val pop by animateFloatAsState(
        if (item.isDone) 1f else 0.92f,
        spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow),
        label = "taskPop"
    )
    val a11y = stringResource(if (item.isDone) R.string.row_done else R.string.row_pending, item.title)
    val minute = item.minuteOfDay
    val subtitle = when {
        item.notes.isNotBlank() -> item.notes
        item.remind && minute != null -> stringResource(R.string.task_reminder_at, hhmm(minute))
        else -> ""
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(rowBg)
            .combinedClickableCompat(role = Role.Checkbox, onClick = { onToggle(item) }, onLongClick = { onEdit(item) })
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (minute != null) {
            Text(hhmm(minute), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.width(42.dp))
        }
        Box(
            Modifier
                .size(28.dp)
                .scale(pop)
                .clip(RoundedCornerShape(9.dp))
                .background(checkBg)
                .border(2.5.dp, checkBorder, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (item.isDone) {
                Canvas(Modifier.size(16.dp)) {
                    val k = size.width / 16f
                    val p = Path().apply { moveTo(3.5f * k, 8.5f * k); lineTo(6.8f * k, 11.5f * k); lineTo(12.5f * k, 4.8f * k) }
                    drawPath(p, Color.White, style = Stroke(2.6f * k, cap = StrokeCap.Round))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (item.isDone) c.muted else c.ink,
                textDecoration = if (item.isDone) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val (chip, fg, bg) = if (item.isOverdue) {
            Triple(stringResource(R.string.task_overdue), c.amberInk, c.amberSoft)
        } else {
            Triple(stringResource(R.string.task_badge), c.lilacInk, c.lilacSoft)
        }
        Text(
            chip,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = fg,
            modifier = Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMenuSheet(onDismiss: () -> Unit, onHabit: () -> Unit, onTask: () -> Unit) {
    val c = Ancla.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.add_what), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            AddOption(stringResource(R.string.add_habit_title), stringResource(R.string.add_habit_sub), c.mint, c.mintSoft, false, onHabit)
            AddOption(stringResource(R.string.add_task_title), stringResource(R.string.add_task_sub), c.lilac, c.lilacSoft, true, onTask)
        }
    }
}

@Composable
private fun AddOption(title: String, subtitle: String, accent: Color, soft: Color, square: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(soft)
            .combinedClickableCompat(role = Role.Button, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(30.dp).clip(if (square) RoundedCornerShape(10.dp) else CircleShape).border(3.dp, accent, if (square) RoundedCornerShape(10.dp) else CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(initial: TaskDraft, onDismiss: () -> Unit, onSave: (TaskDraft) -> Unit, onDelete: (Long) -> Unit) {
    val c = Ancla.colors
    var title by remember { mutableStateOf(initial.title) }
    var notes by remember { mutableStateOf(initial.notes) }
    var minute by remember { mutableStateOf(initial.minuteOfDay) }
    var remind by remember { mutableStateOf(initial.remind) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (pickingTime) {
        val now = java.time.LocalTime.now()
        val state = rememberTimePickerState(
            initialHour = (minute ?: (now.hour * 60 + now.minute + 60)).div(60) % 24,
            initialMinute = (minute ?: 0) % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = c.card,
            confirmButton = {
                TextButton(onClick = { minute = state.hour * 60 + state.minute; pickingTime = false }) {
                    Text(stringResource(R.string.task_time_ok), color = c.mintInk, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { minute = null; pickingTime = false }) {
                    Text(stringResource(R.string.task_time_clear), color = c.muted, style = MaterialTheme.typography.labelLarge)
                }
            },
            text = {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = c.ground,
                        selectorColor = c.mint,
                        timeSelectorSelectedContainerColor = c.mintSoft,
                        timeSelectorSelectedContentColor = c.mintInk,
                        timeSelectorUnselectedContainerColor = c.ground,
                        timeSelectorUnselectedContentColor = c.ink
                    )
                )
            }
        )
    }
    if (confirmDelete && initial.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.card,
            title = { Text(stringResource(R.string.task_delete_title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
            confirmButton = {
                TextButton(onClick = { onDelete(initial.id) }) {
                    Text(stringResource(R.string.task_delete), color = c.amberInk, style = MaterialTheme.typography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.task_cancel), color = c.muted, style = MaterialTheme.typography.labelLarge)
                }
            }
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac,
        unfocusedBorderColor = c.track,
        focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac,
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                stringResource(if (initial.id == null) R.string.task_new else R.string.task_edit),
                style = MaterialTheme.typography.headlineSmall,
                color = c.ink
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.task_title_label)) },
                placeholder = { Text(stringResource(R.string.task_title_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.task_notes_label)) },
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.ground)
                    .combinedClickableCompat(role = Role.Button, onClick = { pickingTime = true })
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.task_time_label), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    minute?.let { hhmm(it) } ?: stringResource(R.string.task_any_time),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (minute != null) c.lilacInk else c.muted
                )
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.ground).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.task_remind_label), style = MaterialTheme.typography.titleMedium, color = c.ink)
                    Text(
                        stringResource(if (minute == null) R.string.task_remind_needs_time else R.string.task_remind_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.muted
                    )
                }
                Switch(
                    checked = remind && minute != null,
                    enabled = minute != null,
                    onCheckedChange = { remind = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.lilac, checkedThumbColor = Color.White)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (initial.id != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.task_delete), color = c.amberInk, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.weight(1f))
                val enabled = title.isNotBlank()
                Box(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (enabled) c.ink else c.track)
                        .combinedClickableCompat(
                            role = Role.Button,
                            onClick = { if (enabled) onSave(TaskDraft(initial.id, title, notes, minute, remind)) }
                        )
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Text(stringResource(R.string.task_save), style = MaterialTheme.typography.labelLarge, color = if (enabled) c.onInk else c.muted)
                }
            }
        }
    }
}
