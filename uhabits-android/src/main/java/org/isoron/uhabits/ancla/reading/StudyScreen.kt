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

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import kotlin.math.ceil
import kotlin.math.max

/** Categorical palette, validated for CVD separation on Ancla's light and dark cards (fixed order). */
private val TOPIC_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF6250D6).map { Color(it) }
private val TOPIC_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF9085E9).map { Color(it) }

/** Color of a topic: by the order it was created, so it never changes when others come and go. */
@Composable
fun topicColors(topics: List<StudyTopic>): (String) -> Color {
    val dark = Ancla.colors.ground.luminance() < 0.5f
    val palette = if (dark) TOPIC_DARK else TOPIC_LIGHT
    val other = Ancla.colors.muted
    val order = topics.sortedBy { it.created }.map { it.name }
    return { name -> order.indexOf(name).let { i -> if (i in palette.indices) palette[i] else other } }
}

fun minutesLabel(m: Int) = if (m < 60) "$m min" else "${m / 60} h" + (if (m % 60 > 0) " ${m % 60} min" else "")

@Composable
fun StudyScreen(
    topics: List<StudyTopic>,
    sessions: List<StudySession>,
    active: Pair<String, Long>?,
    stats: StudyStats,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onAddTopic: (String, Int) -> Unit,
    onRemoveTopic: (String) -> Unit,
    onOpenSession: (StudySession) -> Unit
) {
    val c = Ancla.colors
    val color = topicColors(topics)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Timer or start
        if (active != null) {
            val secs = ((now - active.second) / 1000).coerceAtLeast(0)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color(active.first)))
                    Text(active.first, style = MaterialTheme.typography.titleMedium, color = c.ink)
                }
                Text(
                    "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60),
                    style = MaterialTheme.typography.displayMedium, color = c.ink, modifier = Modifier.padding(vertical = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(stringResource(R.string.study_discard), false, onCancel)
                    Box(
                        Modifier.clip(CircleShape).background(c.ink).combinedClickableCompat(Role.Button, onStop).padding(horizontal = 22.dp, vertical = 11.dp)
                    ) { Text(stringResource(R.string.study_finish), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
                }
                Text(stringResource(R.string.study_finish_hint), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(top = 10.dp))
            }
        } else {
            StartStudy(topics, color, onStart, onAddTopic, onRemoveTopic)
        }

        // Headline numbers
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(minutesLabel(stats.weekTotal), stringResource(R.string.study_week), Modifier.weight(1f))
            StatTile(stats.streakDays.toString(), stringResource(R.string.study_streak), Modifier.weight(1f))
            StatTile(stats.totalSessions.toString(), stringResource(R.string.study_sessions), Modifier.weight(1f))
        }

        WeekChart(stats, topics, color)
        TopicProgress(topics, stats, color)

        // Recent sessions
        val recent = sessions.sortedByDescending { it.start }.take(8)
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.study_recent), style = MaterialTheme.typography.titleMedium, color = c.ink)
            recent.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)
                        .combinedClickableCompat(Role.Button, { onOpenSession(s) }).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color(s.topic)))
                    Text(s.topic, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(minutesLabel(s.minutes) + " · " + DateUtils.getRelativeTimeSpanString(s.start), style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StartStudy(
    topics: List<StudyTopic>,
    color: (String) -> Color,
    onStart: (String) -> Unit,
    onAddTopic: (String, Int) -> Unit,
    onRemoveTopic: (String) -> Unit
) {
    val c = Ancla.colors
    var adding by remember { mutableStateOf(topics.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.study_what), style = MaterialTheme.typography.titleMedium, color = c.ink)
        if (topics.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                topics.sortedBy { it.created }.forEach { t ->
                    Row(
                        Modifier.clip(CircleShape).background(c.ground)
                            .combinedClickableCompat(Role.Button, { if (removing) onRemoveTopic(t.name) else onStart(t.name) }, onLongClick = { removing = !removing })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(color(t.name)))
                        Text((if (removing) "✕ " else "▶ ") + t.name, style = MaterialTheme.typography.labelLarge, color = c.ink)
                    }
                }
            }
            Text(stringResource(if (removing) R.string.study_remove_hint else R.string.study_start_hint), style = MaterialTheme.typography.labelMedium, color = c.muted)
        }
        if (adding) {
            Field(name, { name = it }, stringResource(R.string.study_topic_hint))
            Field(goal, { goal = it.filter(Char::isDigit).take(4) }, stringResource(R.string.study_goal_hint), number = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.clip(CircleShape).background(c.ink).combinedClickableCompat(Role.Button, {
                        if (name.isNotBlank()) {
                            onAddTopic(name.trim(), goal.toIntOrNull() ?: 0)
                            name = ""
                            goal = ""
                            adding = false
                        }
                    }).padding(horizontal = 18.dp, vertical = 10.dp)
                ) { Text(stringResource(R.string.study_add_topic), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
                if (topics.isNotEmpty()) Chip(stringResource(R.string.study_cancel), false) { adding = false }
            }
        } else {
            Chip(stringResource(R.string.study_new_topic), false) { adding = true }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, number: Boolean = false) {
    val c = Ancla.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.ground).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.muted)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            cursorBrush = SolidColor(c.lilac), modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    val c = Ancla.colors
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

/**
 * Minutes per day for the last 7 days, stacked by topic. Tap a day for its breakdown in text
 * (the legend and the breakdown keep identity readable without relying on color alone).
 */
@Composable
private fun WeekChart(stats: StudyStats, topics: List<StudyTopic>, color: (String) -> Color) {
    val c = Ancla.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = c.muted)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = c.ink)
    var picked by remember(stats) { mutableIntStateOf(stats.week.lastIndex) }
    val totals = stats.week.map { it.second.values.sum() }
    val niceMax = max(30, (ceil(max(totals.maxOrNull() ?: 0, 1) / 30.0) * 30).toInt())
    val order = topics.sortedBy { it.created }.map { it.name }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.study_chart_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Canvas(
            Modifier.fillMaxWidth().height(170.dp)
                .semantics { contentDescription = stats.week.joinToString { "${it.first}: ${it.second.values.sum()} min" } }
                .pointerInput(stats) {
                    detectTapGestures { p -> picked = (p.x / (size.width / 7f)).toInt().coerceIn(0, 6) }
                }
        ) {
            val labelH = 22.dp.toPx()
            val topPad = 18.dp.toPx()
            val chartH = size.height - labelH - topPad
            val slot = size.width / 7f
            val barW = slot * 0.46f
            val gap = 2.dp.toPx()
            val radius = 4.dp.toPx()
            // Recessive guide at the scale's top, labelled.
            drawLine(c.track, Offset(0f, topPad), Offset(size.width, topPad), strokeWidth = 1.dp.toPx())
            drawText(measurer.measure(minutesLabel(niceMax), labelStyle), topLeft = Offset(0f, 0f))
            drawLine(c.track, Offset(0f, topPad + chartH), Offset(size.width, topPad + chartH), strokeWidth = 1.dp.toPx())
            stats.week.forEachIndexed { i, (label, byTopic) ->
                val x = slot * i + (slot - barW) / 2f
                var y = topPad + chartH
                val segments = byTopic.entries.sortedBy { e -> order.indexOf(e.key).let { if (it < 0) Int.MAX_VALUE else it } }
                segments.forEachIndexed { s, (topic, minutes) ->
                    val h = chartH * minutes / niceMax
                    val top = y - h
                    val rect = Rect(x, top, x + barW, y)
                    val path = Path().apply {
                        if (s == segments.lastIndex) {
                            addRoundRect(RoundRect(rect, topLeft = CornerRadius(radius), topRight = CornerRadius(radius)))
                        } else {
                            addRect(rect)
                        }
                    }
                    drawPath(path, color(topic).copy(alpha = if (i == picked) 1f else 0.85f))
                    y = top - gap
                }
                if (i == picked) {
                    val total = totals[i]
                    if (total > 0) {
                        val t = measurer.measure(minutesLabel(total), valueStyle)
                        drawText(t, topLeft = Offset(slot * i + (slot - t.size.width) / 2f, (y - t.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
                    }
                    drawRoundRect(c.lilac, Offset(slot * i + 4.dp.toPx(), topPad + chartH + 2.dp.toPx()), Size(slot - 8.dp.toPx(), labelH - 4.dp.toPx()), CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
                val l = measurer.measure(label, if (i == picked) valueStyle else labelStyle)
                drawText(l, topLeft = Offset(slot * i + (slot - l.size.width) / 2f, topPad + chartH + (labelH - l.size.height) / 2f + 1.dp.toPx()))
            }
        }
        // Breakdown of the picked day, as text.
        val day = stats.week.getOrNull(picked)
        if (day != null) {
            val entries = day.second.entries.sortedByDescending { it.value }
            Text(
                if (entries.isEmpty()) stringResource(R.string.study_day_empty, day.first)
                else day.first + ": " + entries.joinToString(" · ") { "${it.key} ${minutesLabel(it.value)}" },
                style = MaterialTheme.typography.bodyMedium, color = c.inkSoft
            )
        }
        // Legend (always, when there are two or more topics).
        val shown = stats.week.flatMap { it.second.keys }.distinct()
        if (shown.size >= 2) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.sortedBy { order.indexOf(it) }.forEach { name ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color(name)))
                        Text(name, style = MaterialTheme.typography.labelMedium, color = c.inkSoft)
                    }
                }
            }
        }
    }
}

/** This week's minutes per topic against its weekly goal. */
@Composable
private fun TopicProgress(topics: List<StudyTopic>, stats: StudyStats, color: (String) -> Color) {
    if (topics.isEmpty()) return
    val c = Ancla.colors
    val byTopic = stats.byTopic.toMap()
    val scaleMax = max(1, topics.maxOf { max(it.weeklyGoalMinutes, byTopic[it.name] ?: 0) })
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.study_topics_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        topics.sortedBy { it.created }.forEach { t ->
            val done = byTopic[t.name] ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(color(t.name)))
                    Text(t.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (t.weeklyGoalMinutes > 0) "${minutesLabel(done)} / ${minutesLabel(t.weeklyGoalMinutes)}" else minutesLabel(done),
                        style = MaterialTheme.typography.labelMedium, color = c.inkSoft
                    )
                }
                // Goal mode: fill against the goal; otherwise against the busiest topic.
                val fraction = if (t.weeklyGoalMinutes > 0) done.toFloat() / t.weeklyGoalMinutes else done.toFloat() / scaleMax
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.track)) {
                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.03f, 1f)).height(8.dp).clip(CircleShape).background(color(t.name)))
                }
            }
        }
    }
}
