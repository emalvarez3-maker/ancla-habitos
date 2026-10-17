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

/** Which interactive figure a lesson shows (see SimulatorFor). */
enum class Simulator { ARM_FORWARD, MECHANISM, ROBOTS_3D, TRANSFORM, ORIENTATION_3D, ARM_DH, ARM_INVERSE, JACOBIAN, PENDULUM, TRAJECTORY, DIFF_DRIVE, PID, SENSORS }

data class Exercise(val question: String, val solution: String)

data class Lesson(
    val number: Int,
    val title: String,
    /** The course part it belongs to, in the order of a robotics textbook (morphology, math, kinematics, ...). */
    val module: String,
    val summary: String,
    val simulator: Simulator?,
    /** Markdown with $formulas$. */
    val body: String,
    val exercises: List<Exercise>
)

/**
 * The robotics course: one Markdown file per lesson in assets/robotics (01.md, 02.md, ...), so a
 * lesson is added by dropping a file in. Each file is a small header, the lesson, and its exercises:
 *
 * ```
 * title: ...
 * module: ...
 * summary: ...
 * simulator: ARM_FORWARD
 * ---
 * Markdown...
 * === Ejercicios
 * Pregunta: ...
 * Solución: ...
 * ```
 */
object RoboticsCourse {
    /** The study topic and the note every lesson note links to. */
    const val TOPIC = "Robótica"

    @Volatile private var cached: List<Lesson>? = null

    fun lessons(context: Context): List<Lesson> = cached ?: synchronized(this) {
        cached ?: context.assets.list("robotics").orEmpty().filter { it.matches(Regex("\\d+\\.md")) }.sorted()
            .map { name -> parse(name.removeSuffix(".md").toInt(), context.assets.open("robotics/$name").bufferedReader().use { it.readText() }) }
            .also { cached = it }
    }

    fun parse(number: Int, text: String): Lesson {
        val lines = text.replace("\r", "").lines()
        val split = lines.indexOfFirst { it.trim() == "---" }
        require(split > 0) { "Lesson $number has no header" }
        val header = lines.take(split).filter { ':' in it }.associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        val rest = lines.drop(split + 1)
        val exStart = rest.indexOfFirst { it.trim().startsWith("===") }
        val body = (if (exStart < 0) rest else rest.take(exStart)).joinToString("\n").trim()
        val exercises = mutableListOf<Exercise>()
        if (exStart >= 0) {
            var question: StringBuilder? = null
            var solution: StringBuilder? = null
            fun flush() {
                val q = question
                if (q != null) exercises.add(Exercise(q.toString().trim(), solution?.toString()?.trim().orEmpty()))
                question = null
                solution = null
            }
            for (line in rest.drop(exStart + 1)) {
                when {
                    line.startsWith("Pregunta:") -> {
                        flush()
                        question = StringBuilder(line.removePrefix("Pregunta:").trim())
                    }
                    line.startsWith("Solución:") -> solution = StringBuilder(line.removePrefix("Solución:").trim())
                    else -> (solution ?: question)?.append('\n')?.append(line)
                }
            }
            flush()
        }
        return Lesson(
            number = number,
            title = header["title"].orEmpty(),
            module = header["module"].orEmpty(),
            summary = header["summary"].orEmpty(),
            simulator = Simulator.entries.firstOrNull { it.name == header["simulator"] },
            body = body,
            exercises = exercises
        )
    }

    fun noteTitle(lesson: Lesson) = "$TOPIC · ${lesson.number}. ${lesson.title}"

    /** The lesson as a note: linked to [[Robótica]], with the exercises and their solutions. */
    fun noteBody(lesson: Lesson, total: Int): String = buildString {
        appendLine("Curso: [[$TOPIC]] · ${lesson.module} · lección ${lesson.number} de $total")
        appendLine()
        appendLine(lesson.body)
        if (lesson.exercises.isNotEmpty()) {
            appendLine()
            appendLine("## Ejercicios")
            lesson.exercises.forEachIndexed { i, e ->
                appendLine()
                appendLine("**${i + 1}.** ${e.question}")
                appendLine()
                appendLine("> Solución: ${e.solution.replace("\n", " ")}")
            }
        }
    }.trim()

    /** The outline, for Dexter's instructions. */
    fun outline(context: Context) = lessons(context).joinToString("; ") { "${it.number}. ${it.title}" }
}
