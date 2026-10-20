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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.AgentResultCard
import org.isoron.uhabits.ancla.assistant.DictationPill
import org.isoron.uhabits.ancla.assistant.MicButton
import org.isoron.uhabits.ancla.assistant.VoiceAgentController
import org.isoron.uhabits.core.models.AutomaticityPhase
import org.isoron.uhabits.core.ui.screens.today.TodayHabitItem
import org.isoron.uhabits.core.ui.screens.today.TodayMood
import org.isoron.uhabits.core.ui.screens.today.TodaySummary
import org.isoron.uhabits.core.ui.screens.today.TodayTaskItem
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class TodayFilter { ALL, PENDING, DONE }

private sealed class TodayRow(val key: String, val sortMinute: Int, val sortName: String) {
    class HabitRowData(val item: TodayHabitItem) :
        TodayRow("h${item.habitId}", item.minuteOfDay ?: Int.MAX_VALUE, item.name.lowercase())
    class TaskRowData(val item: TodayTaskItem) :
        TodayRow("t${item.taskId}", if (item.isOverdue) -1 else item.minuteOfDay ?: Int.MAX_VALUE, item.title.lowercase())
}

private fun buildRows(summary: TodaySummary?, filter: TodayFilter): List<TodayRow> {
    if (summary == null) return emptyList()
    fun keep(done: Boolean) = when (filter) {
        TodayFilter.ALL -> true
        TodayFilter.PENDING -> !done
        TodayFilter.DONE -> done
    }
    val habits = summary.items.filter { keep(it.isDone) }.map { TodayRow.HabitRowData(it) }
    val tasks = summary.tasks.filter { keep(it.isDone) }.map { TodayRow.TaskRowData(it) }
    return (habits + tasks).sortedWith(compareBy<TodayRow>({ it.sortMinute }, { it.sortName }))
}

fun TodayMood.toBrote() = when (this) {
    TodayMood.SLEEPY -> BroteMood.SLEEPY
    TodayMood.CALM -> BroteMood.CALM
    TodayMood.HAPPY -> BroteMood.HAPPY
    TodayMood.JOY -> BroteMood.JOY
}

fun AutomaticityPhase.toStage() = when (this) {
    AutomaticityPhase.INITIATION -> BroteStage.SEED
    AutomaticityPhase.LEARNING -> BroteStage.SPROUT
    AutomaticityPhase.STABILITY -> BroteStage.PLANT
    AutomaticityPhase.AUTOMATIC -> BroteStage.TREE
}

@Composable
fun stageName(stage: BroteStage) = stringResource(
    when (stage) {
        BroteStage.SEED -> R.string.stage_seed
        BroteStage.SPROUT -> R.string.stage_sprout
        BroteStage.PLANT -> R.string.stage_plant
        BroteStage.TREE -> R.string.stage_tree
    }
)

@Composable
fun TodayScreen(
    summary: TodaySummary?,
    hopKey: Int,
    onToggle: (TodayHabitItem) -> Unit,
    onOpen: (TodayHabitItem) -> Unit,
    onToggleTask: (TodayTaskItem) -> Unit,
    onSaveTask: (TaskDraft) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onAddHabit: () -> Unit,
    onMenu: () -> Unit,
    onConsciencia: () -> Unit = {},
    voice: VoiceAgentController? = null,
    onAssistant: () -> Unit = {}
) {
    val c = Ancla.colors
    var filter by rememberSaveable { mutableStateOf(TodayFilter.ALL) }
    var showAddMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskDraft?>(null) }
    val onAdd = { showAddMenu = true }
    if (showAddMenu) {
        AddMenuSheet(
            onDismiss = { showAddMenu = false },
            onHabit = { showAddMenu = false; onAddHabit() },
            onTask = { showAddMenu = false; editing = TaskDraft() }
        )
    }
    editing?.let { draft ->
        TaskEditorSheet(
            initial = draft,
            onDismiss = { editing = null },
            onSave = { onSaveTask(it); editing = null },
            onDelete = { id -> onDeleteTask(id); editing = null }
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Header(summary, onMenu)
                WisdomCard(onConsciencia)
                MascotCard(summary, hopKey, onAssistant)
                if (summary != null && summary.totalCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(stringResource(R.string.filter_all), filter == TodayFilter.ALL) { filter = TodayFilter.ALL }
                        FilterChip(stringResource(R.string.filter_pending), filter == TodayFilter.PENDING) { filter = TodayFilter.PENDING }
                        FilterChip(stringResource(R.string.filter_done), filter == TodayFilter.DONE) { filter = TodayFilter.DONE }
                    }
                }
            }
            val rows = remember(summary, filter) { buildRows(summary, filter) }
            if (summary != null && summary.totalCount == 0) {
                EmptyState(onAdd)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 132.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is TodayRow.HabitRowData -> HabitRow(row.item, Modifier.animateItem(), onToggle, onOpen)
                            is TodayRow.TaskRowData -> TaskRow(
                                row.item,
                                Modifier.animateItem(),
                                onToggleTask,
                                onEdit = {
                                    editing = TaskDraft(it.taskId, it.title, it.notes, it.minuteOfDay, it.remind)
                                }
                            )
                        }
                    }
                }
            }
        }
        AddButton(onAdd, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 22.dp, bottom = 96.dp))
        if (voice != null) {
            MicButton(voice, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 22.dp, bottom = 168.dp))
            DictationPill(voice, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp))
            AgentResultCard(voice, onAssistant, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 92.dp))
        }
    }
}

@Composable
private fun Header(summary: TodaySummary?, onMenu: () -> Unit) {
    val c = Ancla.colors
    val date = remember {
        LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault()))
            .replace(".", "").uppercase(Locale.getDefault())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MenuButton(onMenu)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(date, style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(stringResource(R.string.today_title), style = MaterialTheme.typography.displaySmall, color = c.ink)
            }
        }
        if (summary != null) {
            val stage = summary.phase.toStage()
            val progress by animateFloatAsState(
                summary.xpInLevel / TodaySummary.XP_PER_LEVEL.toFloat(),
                spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
                label = "xp"
            )
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(132.dp)) {
                Text(
                    stringResource(R.string.level_label, summary.level, stageName(stage)),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkSoft
                )
                Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.track)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(10.dp).clip(CircleShape).background(c.mint))
                }
                Text(
                    stringResource(R.string.xp_label, summary.xpInLevel, TodaySummary.XP_PER_LEVEL),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = c.muted
                )
            }
        }
    }
}

/** The wisdom of the day, in quotation marks with its author; tap to go to Consciencia. */
@Composable
private fun WisdomCard(onOpen: () -> Unit) {
    val c = Ancla.colors
    val saying = remember { org.isoron.uhabits.ancla.Wisdom.of() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card)
            .combinedClickableCompat(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wisdom_label), style = MaterialTheme.typography.labelSmall, color = c.amber, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.wisdom_open), style = MaterialTheme.typography.labelSmall, color = c.lilacInk)
        }
        Text(
            "«${saying.text}»", style = MaterialTheme.typography.bodyLarge.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), color = c.ink,
            maxLines = 6, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text("— ${saying.author}", style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
private fun MascotCard(summary: TodaySummary?, hopKey: Int, onClick: () -> Unit) {
    val c = Ancla.colors
    val mood = summary?.mood?.toBrote() ?: BroteMood.CALM
    val stage = summary?.phase?.toStage() ?: BroteStage.SEED
    val keyName = summary?.items?.firstOrNull { it.isKeyDay && !it.isDone }?.name
    val message = when {
        summary == null || summary.totalCount == 0 -> stringResource(R.string.mascot_empty)
        mood == BroteMood.SLEEPY && keyName != null -> stringResource(R.string.mascot_sleepy, keyName)
        mood == BroteMood.CALM && keyName != null -> stringResource(R.string.mascot_calm_key, keyName)
        mood == BroteMood.CALM -> stringResource(R.string.mascot_calm)
        mood == BroteMood.HAPPY -> stringResource(R.string.mascot_happy)
        else -> stringResource(R.string.mascot_joy)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(14.dp, RoundedCornerShape(28.dp), ambientColor = Color(0x223C3278), spotColor = Color(0x223C3278))
            .clip(RoundedCornerShape(28.dp))
            .background(c.card)
            .combinedClickableCompat(Role.Button, onClick)
            .padding(start = 6.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DexterOwl(
            state = OwlState.IDLE,
            mood = mood,
            stage = stage,
            hopKey = hopKey,
            modifier = Modifier.size(width = 124.dp, height = 120.dp),
            description = stringResource(R.string.mascot_name),
            interactive = true
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.mascot_name), style = MaterialTheme.typography.titleLarge, color = c.ink)
            AnimatedContent(message, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "says") { text ->
                Text(text, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            }
            if (summary != null && summary.totalCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProgressRing(summary.doneCount / summary.totalCount.toFloat())
                    Text(
                        stringResource(R.string.today_done_count, summary.doneCount, summary.totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.inkSoft
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressRing(fraction: Float) {
    val c = Ancla.colors
    val animated by animateFloatAsState(fraction, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow), label = "ring")
    Canvas(Modifier.size(34.dp)) {
        val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
        val inset = 2.5.dp.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(c.track, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
        drawArc(c.mint, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = stroke)
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    val bg by animateColorAsState(if (selected) c.ink else c.card, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onInk else c.inkSoft, label = "chipFg")
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .combinedClickableCompat(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun HabitRow(item: TodayHabitItem, modifier: Modifier, onToggle: (TodayHabitItem) -> Unit, onOpen: (TodayHabitItem) -> Unit) {
    val c = Ancla.colors
    val checkBg by animateColorAsState(if (item.isDone) c.mint else Color.Transparent, label = "checkBg")
    val checkBorder by animateColorAsState(if (item.isDone) c.mint else c.track, label = "checkBorder")
    val rowBg by animateColorAsState(if (item.isDone) c.cardMuted else c.card, label = "rowBg")
    val pop by animateFloatAsState(
        if (item.isDone) 1f else 0.92f,
        spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow),
        label = "pop"
    )
    val subtitle = when {
        item.anchor.isNotBlank() -> stringResource(R.string.after_anchor, item.anchor)
        item.tinyVersion.isNotBlank() -> stringResource(R.string.tiny_prefix, item.tinyVersion)
        item.isNumerical -> "${formatAmount(item.target)} ${item.unit}"
        else -> ""
    }
    val time = item.minuteOfDay?.let { String.format(Locale.getDefault(), "%02d:%02d", it / 60, it % 60) }
    val a11y = stringResource(if (item.isDone) R.string.row_done else R.string.row_pending, item.name)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(rowBg)
            .then(
                if (item.isKeyDay && !item.isDone) Modifier.border(2.dp, c.amber.copy(alpha = 0.55f), RoundedCornerShape(20.dp)) else Modifier
            )
            .combinedClickableCompat(role = Role.Checkbox, onClick = { onToggle(item) }, onLongClick = { onOpen(item) })
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (time != null) {
            Text(time, style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.width(42.dp))
        }
        Box(
            Modifier
                .size(28.dp)
                .scale(pop)
                .clip(CircleShape)
                .background(checkBg)
                .border(2.5.dp, checkBorder, CircleShape),
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
                item.name,
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
        if (item.isKeyDay && !item.isDone) {
            Text(
                stringResource(R.string.key_day_badge),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                color = c.amberInk,
                modifier = Modifier.clip(CircleShape).background(c.amberSoft).padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    }
}

private fun formatAmount(v: Double): String = if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.getDefault(), "%.1f", v)

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(c.ink)
                .combinedClickableCompat(role = Role.Button, onClick = onAdd)
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Text(stringResource(R.string.today_empty_action), style = MaterialTheme.typography.labelLarge, color = c.onInk)
        }
    }
}

@Composable
private fun AddButton(onAdd: () -> Unit, modifier: Modifier) {
    val c = Ancla.colors
    val desc = stringResource(R.string.add_habit_desc)
    Box(
        modifier
            .size(60.dp)
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(c.ink)
            .combinedClickableCompat(role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val s = size.width
            drawLine(c.onInk, Offset(s / 2, s * 0.2f), Offset(s / 2, s * 0.8f), 2.6.dp.toPx(), StrokeCap.Round)
            drawLine(c.onInk, Offset(s * 0.2f, s / 2), Offset(s * 0.8f, s / 2), 2.6.dp.toPx(), StrokeCap.Round)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.combinedClickableCompat(role: Role, onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    this.combinedClickable(role = role, onClick = onClick, onLongClick = onLongClick)
