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

package org.isoron.uhabits.ancla.consciousness

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.PlaceHeader
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class View { HOME, PAUSA, MAPAS, EXAMEN }

private val QUADRANT_COLORS = mapOf(
    Quadrant.HIGH_UNPLEASANT to (Color(0xFF3A1830) to Color(0xFFFF7AA8)),
    Quadrant.HIGH_PLEASANT to (Color(0xFF3A2A12) to Color(0xFFFFB627)),
    Quadrant.LOW_UNPLEASANT to (Color(0xFF1C2340) to Color(0xFF5AB8FF)),
    Quadrant.LOW_PLEASANT to (Color(0xFF14322A) to Color(0xFF3FBF95))
)

/**
 * Consciencia, the north star of the app: the user's wheel of six dimensions as they see
 * themselves this week, the day's question, patterns their own records show, and three practices:
 * the conscious pause, the maps of how people grow in awareness, and the night examen. A mirror,
 * never a score.
 */
@Composable
fun ConscienciaScreen(store: ConsciousnessStore, openMenu: () -> Unit, onDexter: () -> Unit, startAt: String? = null) {
    var view by rememberSaveable { mutableStateOf(startAt?.let { runCatching { View.valueOf(it) }.getOrNull() } ?: View.HOME) }
    var tick by remember { mutableIntStateOf(0) }
    BackHandler(view != View.HOME) { view = View.HOME }
    AnimatedContent(view, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "consciencia") { v ->
        when (v) {
            View.HOME -> Home(store, tick, openMenu, onDexter) { view = it }
            View.PAUSA -> Pausa(store, onBack = { view = View.HOME }) { tick++; view = View.HOME }
            View.MAPAS -> Mapas { view = View.HOME }
            View.EXAMEN -> ExamenView(store, onBack = { view = View.HOME }, onDexter = onDexter) { tick++; view = View.HOME }
        }
    }
}

@Composable
private fun Home(store: ConsciousnessStore, tick: Int, openMenu: () -> Unit, onDexter: () -> Unit, go: (View) -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var wheel by remember { mutableStateOf(emptyMap<Dimension, Int>()) }
    var answer by remember { mutableStateOf<String?>(null) }
    var patterns by remember { mutableStateOf(emptyList<String>()) }
    var streak by remember { mutableIntStateOf(0) }
    var rating by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val question = remember { Questions.of() }
    suspend fun reload() = withContext(Dispatchers.IO) {
        runCatching {
            wheel = store.wheel()
            answer = store.reflection()?.answer
            patterns = store.patterns()
            streak = store.pauseStreak()
        }
    }
    LaunchedEffect(tick) {
        while (true) {
            reload()
            delay(4000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PlaceHeader(R.string.cons_caption, R.string.nav_consciencia, openMenu)

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).combinedClickableCompat(Role.Button, { rating = true }).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cons_wheel_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(stringResource(R.string.cons_wheel_mirror), style = MaterialTheme.typography.labelMedium, color = c.muted)
            }
            Wheel(wheel, Modifier.fillMaxWidth().height(250.dp))
            Text(
                stringResource(if (wheel.isEmpty()) R.string.cons_wheel_empty else R.string.cons_wheel_edit),
                style = MaterialTheme.typography.labelLarge, color = c.lilacInk
            )
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.lilacSoft).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cons_question_label), style = MaterialTheme.typography.labelSmall, color = c.amber)
            Text(question, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            answer?.let { Text("«$it»", style = MaterialTheme.typography.bodyMedium, color = c.inkSoft) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.cons_answer_voice), c.amber, c.onInk, onDexter)
                Pill(stringResource(if (answer == null) R.string.cons_answer_write else R.string.cons_answer_edit), c.card, c.ink) { writing = true }
            }
        }

        if (patterns.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.cons_patterns), style = MaterialTheme.typography.labelLarge, color = c.lilacInk)
                patterns.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = c.inkSoft) }
            }
        }

        val context = androidx.compose.ui.platform.LocalContext.current
        Practice(stringResource(R.string.cons_meditate), stringResource(R.string.cons_meditate_sub), Modifier.fillMaxWidth()) {
            context.startActivity(MeditationActivity.intent(context))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Practice(stringResource(R.string.cons_pause), stringResource(R.string.cons_pause_sub), Modifier.weight(1f)) { go(View.PAUSA) }
            Practice(stringResource(R.string.cons_examen), stringResource(R.string.cons_examen_sub), Modifier.weight(1f)) { go(View.EXAMEN) }
            Practice(stringResource(R.string.cons_maps), stringResource(R.string.cons_maps_sub), Modifier.weight(1f)) { go(View.MAPAS) }
        }
        Text(stringResource(R.string.cons_footer, streak), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }

    if (rating) {
        WheelSheet(wheel, onDismiss = { rating = false }) { r ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.setWheel(r) } }
                reload()
            }
            rating = false
        }
    }
    if (writing) {
        TextSheet(question, answer.orEmpty(), onDismiss = { writing = false }) { text ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.saveReflection(text) } }
                reload()
            }
            writing = false
        }
    }
}

/** The six dimensions as a hexagon: the user's own 1–5 for each, drawn in violet with gold points. */
@Composable
private fun Wheel(ratings: Map<Dimension, Int>, modifier: Modifier) {
    val c = Ancla.colors
    val measurer = rememberTextMeasurer()
    val grow by animateFloatAsState(if (ratings.isEmpty()) 0f else 1f, tween(800, easing = FastOutSlowInEasing), label = "wheel")
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = c.inkSoft, fontSize = 12.sp)
    val description = Dimension.entries.joinToString { "${it.label} ${ratings[it] ?: "-"}" }
    Canvas(modifier.semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        val r = size.height * 0.36f
        fun at(i: Int, k: Float): Offset {
            val a = Math.toRadians(-90.0 + 60.0 * i)
            return Offset(center.x + (r * k * cos(a)).toFloat(), center.y + (r * k * sin(a)).toFloat())
        }
        for (ring in listOf(1f / 3, 2f / 3, 1f)) {
            val p = Path().apply { (0..6).forEach { i -> val o = at(i % 6, ring); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(p, c.track, style = Stroke(1.5.dp.toPx()))
        }
        for (i in 0 until 6) drawLine(c.track, center, at(i, 1f), 1.dp.toPx())
        if (ratings.isNotEmpty()) {
            val value = { i: Int -> (ratings[Dimension.entries[i]] ?: 0) / 5f * grow }
            val p = Path().apply { (0..6).forEach { i -> val o = at(i % 6, value(i % 6)); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
            drawPath(p, c.lilac.copy(alpha = 0.28f))
            drawPath(p, c.lilac, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
            for (i in 0 until 6) if (Dimension.entries[i] in ratings) drawCircle(c.amber, 4.dp.toPx(), at(i, value(i)))
        }
        Dimension.entries.forEachIndexed { i, d ->
            val layout = measurer.measure(d.label, labelStyle)
            val o = at(i, 1.18f)
            drawText(layout, topLeft = Offset(o.x - layout.size.width / 2f, o.y - layout.size.height / 2f))
        }
    }
}

@Composable
private fun Pill(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = fg,
        modifier = Modifier.clip(CircleShape).background(bg).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun Practice(title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Ancla.colors
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.card).combinedClickableCompat(Role.Button, onClick).padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(sub, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
private fun SubHeader(caption: Int, title: Int, onBack: () -> Unit) {
    val c = Ancla.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("‹", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onBack).padding(horizontal = 14.dp, vertical = 2.dp))
        Column {
            Text(stringResource(caption), style = MaterialTheme.typography.labelSmall, color = c.muted)
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, color = c.ink)
        }
    }
}

/** The conscious pause: name the feeling precisely, notice it in the body, then a minute of breathing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pausa(store: ConsciousnessStore, onBack: () -> Unit, onDone: () -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(1) }
    var emotion by rememberSaveable { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf(emptyList<String>()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SubHeader(R.string.cons_pause_caption, R.string.cons_pause_title, onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..3).forEach { Box(Modifier.size(width = 34.dp, height = 6.dp).clip(CircleShape).background(if (it <= step) c.amber else c.track)) }
        }
        when (step) {
            1 -> {
                Text(stringResource(R.string.cons_pause_q1), style = MaterialTheme.typography.headlineSmall, color = c.ink)
                Text(stringResource(R.string.cons_pause_q1_sub), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                Quadrant.entries.forEach { q ->
                    val (bg, fg) = QUADRANT_COLORS.getValue(q)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(q.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = fg)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            q.emotions.forEach { e ->
                                val on = e == emotion
                                Text(
                                    e, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else Color.White.copy(alpha = 0.88f),
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) fg else fg.copy(alpha = 0.18f))
                                        .combinedClickableCompat(Role.RadioButton, { emotion = e }).padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
                Pill(stringResource(R.string.cons_next), if (emotion == null) c.track else c.lilac, c.onInk) { if (emotion != null) step = 2 }
            }
            2 -> {
                Text(stringResource(R.string.cons_pause_q2, emotion.orEmpty().lowercase()), style = MaterialTheme.typography.headlineSmall, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BODY_PLACES.forEach { place ->
                        val on = place in body
                        Text(
                            place, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.lilacInk,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) c.lilac else c.lilacSoft)
                                .combinedClickableCompat(Role.Checkbox, { body = if (on) body - place else body + place }).padding(horizontal = 14.dp, vertical = 9.dp)
                        )
                    }
                }
                Pill(stringResource(R.string.cons_next), c.lilac, c.onInk) { step = 3 }
            }
            else -> {
                Text(stringResource(R.string.cons_pause_q3), style = MaterialTheme.typography.headlineSmall, color = c.ink)
                Breathing()
                Pill(stringResource(R.string.cons_pause_done), c.amber, c.onInk) {
                    val e = emotion ?: return@Pill
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { store.addCheckIn(e, body) } }
                        onDone()
                    }
                }
            }
        }
    }
}

/** 4 s in, 4 s holding, 6 s out: the circle grows and shrinks with the breath, with the word to follow. */
@Composable
private fun Breathing() {
    val c = Ancla.colors
    val t = rememberInfiniteTransition(label = "breath")
    val scale by t.animateFloat(
        0.62f, 0.62f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 14000
                0.62f at 0 using FastOutSlowInEasing
                1f at 4000
                1f at 8000 using FastOutSlowInEasing
                0.62f at 14000
            }
        ),
        label = "scale"
    )
    val phase by t.animateFloat(0f, 14f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val word = stringResource(
        when {
            phase < 4f -> R.string.cons_breathe_in
            phase < 8f -> R.string.cons_breathe_hold
            else -> R.string.cons_breathe_out
        }
    )
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(220.dp).scale(scale).clip(CircleShape).background(c.lilac.copy(alpha = 0.10f)))
        Box(Modifier.size(170.dp).scale(scale).clip(CircleShape).background(c.lilacSoft), contentAlignment = Alignment.Center) {
            Text(word, style = MaterialTheme.typography.headlineSmall, color = c.ink)
        }
    }
    Text(stringResource(R.string.cons_breathe_rhythm), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.fillMaxWidth())
}

/** The maps of how people grow in awareness, each with how much backing it has. */
@Composable
private fun Mapas(onBack: () -> Unit) {
    val c = Ancla.colors
    val evidence = c.mint
    val tradition = c.lilac
    val none = c.muted
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SubHeader(R.string.cons_maps_caption, R.string.cons_maps_title, onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(stringResource(R.string.cons_tag_evidence), evidence)
            Tag(stringResource(R.string.cons_tag_tradition), tradition)
            Tag(stringResource(R.string.cons_tag_none), none)
        }
        MapCard(R.string.map_mindfulness, R.string.map_mindfulness_body, evidence)
        MapCard(R.string.map_kegan, R.string.map_kegan_body, evidence)
        MapCard(R.string.map_ego, R.string.map_ego_body, evidence)
        MapCard(R.string.map_maslow, R.string.map_maslow_body, evidence)
        MapCard(R.string.map_sdt, R.string.map_sdt_body, evidence)
        MapCard(R.string.map_stoics, R.string.map_stoics_body, tradition)
        MapCard(R.string.map_buddhism, R.string.map_buddhism_body, tradition)
        MapCard(R.string.map_vedanta, R.string.map_vedanta_body, tradition)
        MapCard(R.string.map_hawkins, R.string.map_hawkins_body, none)
    }
}

@Composable
private fun Tag(label: String, color: Color) {
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = color,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
private fun MapCard(title: Int, body: Int, color: Color) {
    val c = Ancla.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)) {
        Box(Modifier.size(width = 4.dp, height = 74.dp).background(color))
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        }
    }
}

/** The night examen, after Seneca's and Ignatius': four questions, answered by voice or in writing. */
@Composable
private fun ExamenView(store: ConsciousnessStore, onBack: () -> Unit, onDexter: () -> Unit, onDone: () -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf(listOf("", "", "", "")) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { store.examen() } }.getOrNull()?.let {
            answers = listOf(it.gratitude, it.wentWell, it.reacted, it.tomorrow)
        }
    }
    val questions = listOf(R.string.examen_q1, R.string.examen_q2, R.string.examen_q3, R.string.examen_q4)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 150.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SubHeader(R.string.examen_caption, R.string.examen_title, onBack)
        Text(stringResource(R.string.examen_intro), style = MaterialTheme.typography.bodyLarge, color = c.inkSoft)
        questions.forEachIndexed { i, q ->
            OutlinedTextField(
                value = answers[i], onValueChange = { v -> answers = answers.toMutableList().also { it[i] = v } }, minLines = 2,
                label = { Text("${i + 1}. " + stringResource(q)) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.camino_save), c.amber, c.onInk) {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { store.saveExamen(answers[0], answers[1], answers[2], answers[3]) } }
                    onDone()
                }
            }
            Pill(stringResource(R.string.examen_with_dexter), c.lilacSoft, c.lilacInk, onDexter)
        }
        Text(stringResource(R.string.examen_footer), style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WheelSheet(initial: Map<Dimension, Int>, onDismiss: () -> Unit, onSave: (Map<Dimension, Int>) -> Unit) {
    val c = Ancla.colors
    var values by remember { mutableStateOf(Dimension.entries.associateWith { (initial[it] ?: 3).toFloat() }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.cons_wheel_sheet), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Text(stringResource(R.string.cons_wheel_sheet_sub), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            Dimension.entries.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.label, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                    Text(values.getValue(d).roundToInt().toString(), style = MaterialTheme.typography.titleMedium, color = c.amber)
                }
                Slider(
                    value = values.getValue(d), onValueChange = { v -> values = values + (d to v) }, valueRange = 1f..5f, steps = 3,
                    colors = SliderDefaults.colors(thumbColor = c.amber, activeTrackColor = c.lilac, inactiveTrackColor = c.track)
                )
            }
            Pill(stringResource(R.string.camino_save), c.lilac, c.onInk) { onSave(values.mapValues { it.value.roundToInt() }) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Ancla.colors
    var text by remember { mutableStateOf(initial) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            OutlinedTextField(value = text, onValueChange = { text = it }, minLines = 3, shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth())
            Pill(stringResource(R.string.camino_save), if (text.isBlank()) c.track else c.lilac, c.onInk) { if (text.isNotBlank()) onSave(text.trim()) }
        }
    }
}
