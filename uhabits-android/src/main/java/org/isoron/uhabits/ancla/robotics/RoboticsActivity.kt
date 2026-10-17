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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.DexterService
import org.isoron.uhabits.ancla.reading.Chip
import org.isoron.uhabits.ancla.reading.IdeaGraph
import org.isoron.uhabits.ancla.reading.IdeaKind
import org.isoron.uhabits.ancla.reading.MathView
import org.isoron.uhabits.ancla.reading.NotesActivity
import org.isoron.uhabits.ancla.reading.StudyStore
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

/** Where the robotics screen is: the course index, one lesson, or one of the two labs. */
sealed interface RoboticsPage {
    data object Home : RoboticsPage
    data class LessonPage(val number: Int) : RoboticsPage
    data object Workshop : RoboticsPage
    data object Robots3D : RoboticsPage
}

/**
 * "Aprende robótica": a course in modules that follows the order of an industrial robotics
 * textbook (morphology, math tools, kinematics, dynamics, control), each lesson with formulas, a
 * simulator and solved exercises, plus a mechanism workshop to draw linkages and count their degrees
 * of freedom, and a 3D view of the classic robot configurations.
 */
class RoboticsActivity : AppCompatActivity() {
    private val page = mutableStateOf<RoboticsPage>(RoboticsPage.Home)
    private val version = mutableIntStateOf(0)
    private val prefs by lazy { getSharedPreferences("ancla_robotics", Context.MODE_PRIVATE) }
    private val study by lazy { StudyStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            AnclaTheme {
                version.intValue // re-read progress and the study timer after changes
                val lessons = remember { RoboticsCourse.lessons(this) }
                val current = page.value
                BackHandler(current != RoboticsPage.Home) { page.value = RoboticsPage.Home }
                Box(Modifier.fillMaxSize().background(Ancla.colors.ground).statusBarsPadding()) {
                    when (current) {
                        RoboticsPage.Home -> CourseHome(
                            lessons, learned(), onBack = ::finish, onOpen = { page.value = it }
                        )
                        is RoboticsPage.LessonPage -> {
                            val lesson = lessons.firstOrNull { it.number == current.number } ?: lessons.first()
                            LessonScreen(
                                lesson = lesson,
                                total = lessons.size,
                                learned = lesson.number in learned(),
                                studying = study.active?.first?.let { IdeaGraph.key(it) == IdeaGraph.key(RoboticsCourse.TOPIC) } == true,
                                onBack = { page.value = RoboticsPage.Home },
                                onSave = { save(lesson, lessons.size) },
                                onStudy = ::startStudy,
                                onLearned = { toggleLearned(lesson.number) },
                                onOpen = { page.value = it },
                                previous = lessons.lastOrNull { it.number < lesson.number }?.number,
                                next = lessons.firstOrNull { it.number > lesson.number }?.number
                            )
                        }
                        RoboticsPage.Workshop -> LabScreen(stringResource(R.string.robotics_workshop), { page.value = RoboticsPage.Home }) { MechanismWorkshop() }
                        RoboticsPage.Robots3D -> LabScreen(stringResource(R.string.robotics_robots3d), { page.value = RoboticsPage.Home }) { Robots3DSimulator() }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        DexterService.instance?.pause() // the WebViews and simulators want the CPU, and "Oye Dexter" the mic
        version.intValue++
    }

    override fun onPause() {
        DexterService.instance?.resume()
        super.onPause()
    }

    private fun handle(intent: Intent) {
        when (intent.getStringExtra(EXTRA_SCREEN)) {
            SCREEN_WORKSHOP -> page.value = RoboticsPage.Workshop
            SCREEN_ROBOTS -> page.value = RoboticsPage.Robots3D
        }
        intent.getIntExtra(EXTRA_LESSON, 0).takeIf { it > 0 }?.let { page.value = RoboticsPage.LessonPage(it) }
    }

    private fun learned(): Set<Int> = prefs.getStringSet("learned", null).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    private fun toggleLearned(n: Int) {
        val now = learned().let { if (n in it) it - n else it + n }
        prefs.edit().putStringSet("learned", now.map { it.toString() }.toSet()).apply()
        version.intValue++
    }

    /** "Guardar en mis notas": a note with the lesson and its exercises, linked to [[Robótica]]. */
    private fun save(lesson: Lesson, total: Int) {
        val graph = IdeaGraph(this)
        graph.create(RoboticsCourse.TOPIC)
        val note = graph.save(RoboticsCourse.noteTitle(lesson), RoboticsCourse.noteBody(lesson, total), getString(R.string.robotics_title), IdeaKind.SUMMARY)
        Toast.makeText(this, getString(R.string.robotics_saved, note.title), Toast.LENGTH_SHORT).show()
    }

    /** "Estudiar esta lección": the study timer on the Robótica topic (the Study tab shows and stops it). */
    private fun startStudy() {
        val active = study.active
        if (active != null && IdeaGraph.key(active.first) == IdeaGraph.key(RoboticsCourse.TOPIC)) {
            startActivity(NotesActivity.intent(this, tab = "study"))
            return
        }
        study.start(RoboticsCourse.TOPIC)
        version.intValue++
        Toast.makeText(this, R.string.robotics_study_started, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_LESSON = "lesson"
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_WORKSHOP = "workshop"
        const val SCREEN_ROBOTS = "robots3d"

        fun intent(context: Context, lesson: Int = 0, screen: String? = null) =
            Intent(context, RoboticsActivity::class.java).putExtra(EXTRA_LESSON, lesson).putExtra(EXTRA_SCREEN, screen)
    }
}

@Composable
private fun TopBar(title: String, subtitle: String, onBack: () -> Unit) {
    val c = Ancla.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
            modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onBack).padding(horizontal = 12.dp, vertical = 2.dp)
                .semantics { contentDescription = "Volver" }
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CourseHome(lessons: List<Lesson>, learned: Set<Int>, onBack: () -> Unit, onOpen: (RoboticsPage) -> Unit) {
    val c = Ancla.colors
    val next = lessons.firstOrNull { it.number !in learned }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.robotics_title), stringResource(R.string.robotics_progress, learned.count { n -> lessons.any { it.number == n } }, lessons.size), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (next != null) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.ink)
                        .combinedClickableCompat(Role.Button, { onOpen(RoboticsPage.LessonPage(next.number)) }).padding(18.dp)
                ) {
                    Text(stringResource(R.string.robotics_continue), style = MaterialTheme.typography.labelLarge, color = c.onInk.copy(alpha = 0.75f))
                    Text("${next.number}. ${next.title}", style = MaterialTheme.typography.titleMedium, color = c.onInk)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabCard("⚙", stringResource(R.string.robotics_workshop), stringResource(R.string.robotics_workshop_hint), Modifier.weight(1f)) { onOpen(RoboticsPage.Workshop) }
                LabCard("🦾", stringResource(R.string.robotics_robots3d), stringResource(R.string.robotics_robots3d_hint), Modifier.weight(1f)) { onOpen(RoboticsPage.Robots3D) }
            }
            lessons.groupBy { it.module }.forEach { (module, items) ->
                Text(module, style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = 10.dp))
                items.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)
                            .combinedClickableCompat(Role.Button, { onOpen(RoboticsPage.LessonPage(l.number)) }).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val done = l.number in learned
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(if (done) c.mint else c.lilacSoft),
                            contentAlignment = Alignment.Center
                        ) { Text(if (done) "✓" else l.number.toString(), style = MaterialTheme.typography.labelLarge, color = if (done) c.onInk else c.lilacInk) }
                        Column(Modifier.weight(1f)) {
                            Text(l.title, style = MaterialTheme.typography.titleSmall, color = c.ink)
                            Text(l.summary, style = MaterialTheme.typography.bodySmall, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text(stringResource(R.string.robotics_book_note), style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(vertical = 12.dp))
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabCard(icon: String, title: String, hint: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Ancla.colors
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(c.card).combinedClickableCompat(Role.Button, onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(icon, style = MaterialTheme.typography.headlineSmall)
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
    }
}

@Composable
private fun LabScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, "", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LessonScreen(
    lesson: Lesson,
    total: Int,
    learned: Boolean,
    studying: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onStudy: () -> Unit,
    onLearned: () -> Unit,
    onOpen: (RoboticsPage) -> Unit,
    previous: Int?,
    next: Int?
) {
    val c = Ancla.colors
    val scroll = rememberScrollState()
    LaunchedEffect(lesson.number) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.robotics_lesson_n, lesson.number, total), lesson.module, onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(lesson.title, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(if (studying) R.string.robotics_studying else R.string.robotics_study), studying, onStudy)
                Chip(stringResource(R.string.robotics_save_note), false, onSave)
            }
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.card).padding(16.dp)) {
                MathView(lesson.body)
            }
            lesson.simulator?.let { sim ->
                Text(stringResource(R.string.robotics_simulator), style = MaterialTheme.typography.titleMedium, color = c.ink)
                SimulatorFor(sim)
                if (sim == Simulator.MECHANISM) Chip(stringResource(R.string.robotics_open_workshop), false) { onOpen(RoboticsPage.Workshop) }
                if (sim == Simulator.ROBOTS_3D) Chip(stringResource(R.string.robotics_open_robots3d), false) { onOpen(RoboticsPage.Robots3D) }
            }
            if (lesson.exercises.isNotEmpty()) {
                Text(stringResource(R.string.robotics_exercises), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.robotics_exercises_hint), style = MaterialTheme.typography.labelMedium, color = c.muted)
                lesson.exercises.forEachIndexed { i, e -> ExerciseCard(lesson.number, i, e) }
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (learned) c.mintSoft else c.ink)
                    .combinedClickableCompat(Role.Button, onLearned).padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(if (learned) R.string.robotics_learned else R.string.robotics_mark_learned),
                    style = MaterialTheme.typography.labelLarge, color = if (learned) c.mintInk else c.onInk
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (previous != null) Chip(stringResource(R.string.robotics_previous), false) { onOpen(RoboticsPage.LessonPage(previous)) }
                Spacer(Modifier.weight(1f))
                if (next != null) Chip(stringResource(R.string.robotics_next), true) { onOpen(RoboticsPage.LessonPage(next)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExerciseCard(lesson: Int, index: Int, exercise: Exercise) {
    val c = Ancla.colors
    var open by remember(lesson, index) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.robotics_exercise_n, index + 1), style = MaterialTheme.typography.labelLarge, color = c.lilacInk)
        MathView(exercise.question)
        CardChip(stringResource(if (open) R.string.robotics_hide_solution else R.string.robotics_show_solution), open) { open = !open }
        if (open) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.ground).padding(12.dp)) {
                MathView(exercise.solution)
            }
        }
    }
}
