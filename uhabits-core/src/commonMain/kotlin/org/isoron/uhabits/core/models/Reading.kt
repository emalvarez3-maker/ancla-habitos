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
package org.isoron.uhabits.core.models

import org.isoron.platform.time.LocalDate

enum class BookStatus(val value: Int) {
    WANT(0), READING(1), FINISHED(2);

    companion object {
        fun fromInt(v: Int) = entries.firstOrNull { it.value == v } ?: WANT
    }
}

data class Book(
    val id: Long? = null,
    val title: String,
    val author: String = "",
    val pages: Int = 0,
    val status: BookStatus = BookStatus.WANT,
    val currentPage: Int = 0,
    /** Topic tags (see [ReadingTags]) used by the recommender. */
    val tags: Set<String> = emptySet(),
    /** 1–5 stars once finished, or 0 when not rated. */
    val rating: Int = 0,
    val addedDay: LocalDate,
    val finishedDay: LocalDate? = null,
    /** Path of the imported EPUB inside the app's storage, or empty for a paper book. */
    val file: String = "",
    /** Reader position for EPUBs, as "chapterIndex:scrollFraction". */
    val location: String = ""
) {
    val isEbook: Boolean get() = file.isNotEmpty()

    val progress: Double get() = if (pages > 0) (currentPage.toDouble() / pages).coerceIn(0.0, 1.0) else 0.0
}

data class ReadingSession(
    val id: Long? = null,
    val bookId: Long,
    val day: LocalDate,
    val minutes: Int,
    val pages: Int = 0
)

/** The topic vocabulary shared by the catalog, the goal chips and Open Library subjects. */
object ReadingTags {
    const val HABITS = "habitos"
    const val PRODUCTIVITY = "productividad"
    const val FINANCE = "finanzas"
    const val ENGLISH = "ingles"
    const val FICTION = "novela"
    const val LATAM = "latam"
    const val PSYCHOLOGY = "psicologia"
    const val SCIENCE = "ciencia"
    const val PHILOSOPHY = "filosofia"
    const val HEALTH = "salud"
    const val TECH = "tecnologia"
    const val HISTORY = "historia"
    const val BUSINESS = "negocios"
    const val CLASSIC = "clasico"

    /** Goal chips shown in the reading screen, in order. */
    val GOALS = listOf(HABITS, FINANCE, ENGLISH, FICTION, PSYCHOLOGY, SCIENCE, TECH, PRODUCTIVITY)

    private val subjectKeywords = mapOf(
        HABITS to listOf("habit", "hábito", "habito", "self-help", "autoayuda", "self-improvement"),
        PRODUCTIVITY to listOf("productiv", "time management", "success", "éxito"),
        FINANCE to listOf("finance", "finanzas", "money", "dinero", "invest", "econom", "wealth"),
        FICTION to listOf("fiction", "novel", "novela", "ficción", "literature", "literatura", "stories", "cuentos"),
        LATAM to listOf("latin america", "latinoamer", "ecuador", "colombia", "mexic", "argentin"),
        PSYCHOLOGY to listOf("psycholog", "psicolog", "mind", "behavior", "conducta", "emotion"),
        SCIENCE to listOf("science", "ciencia", "physics", "biology", "astronom", "cosmolog"),
        PHILOSOPHY to listOf("philosoph", "filosof", "stoic", "ethics"),
        HEALTH to listOf("health", "salud", "sleep", "nutrition", "fitness", "exercise"),
        TECH to listOf("computer", "programming", "software", "programación", "technology", "tecnolog"),
        HISTORY to listOf("history", "historia"),
        BUSINESS to listOf("business", "negocio", "entrepreneur", "management", "startup")
    )

    /** Maps free-form subjects (e.g. from Open Library) to known tags. */
    fun fromSubjects(subjects: List<String>): Set<String> {
        val text = subjects.joinToString(" ").lowercase()
        return subjectKeywords.filterValues { keys -> keys.any { text.contains(it) } }.keys
    }

    fun encode(tags: Set<String>) = tags.sorted().joinToString(",")
    fun decode(text: String) = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}
