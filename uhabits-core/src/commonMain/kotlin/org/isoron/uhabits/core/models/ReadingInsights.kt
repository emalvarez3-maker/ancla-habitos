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

import org.isoron.platform.time.DayOfWeek
import org.isoron.platform.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ReadingStats(
    /** Minutes read on each day of the current week, Monday first. */
    val weekMinutes: List<Int>,
    val yearMinutes: Int,
    val booksFinishedThisYear: Int,
    val averageSessionMinutes: Int,
    /** The reader's own pace, or a typical 40 pages per hour before there is data. */
    val pagesPerHour: Double
) {
    companion object {
        const val DEFAULT_PAGES_PER_HOUR = 40.0

        fun compute(books: List<Book>, sessions: List<ReadingSession>, today: LocalDate): ReadingStats {
            val monday = today.startOfWeek(DayOfWeek.MONDAY)
            val week = MutableList(7) { 0 }
            sessions.forEach { s ->
                val i = monday.daysUntil(s.day)
                if (i in 0..6) week[i] += s.minutes
            }
            val yearStart = today.startOfYear()
            val yearSessions = sessions.filter { !it.day.isOlderThan(yearStart) }
            val minutes = yearSessions.sumOf { it.minutes }
            val withPages = yearSessions.filter { it.pages > 0 && it.minutes > 0 }
            val pace = if (withPages.sumOf { it.minutes } >= 30) {
                withPages.sumOf { it.pages } * 60.0 / withPages.sumOf { it.minutes }
            } else {
                DEFAULT_PAGES_PER_HOUR
            }
            return ReadingStats(
                weekMinutes = week,
                yearMinutes = minutes,
                booksFinishedThisYear = books.count { b -> b.status == BookStatus.FINISHED && b.finishedDay?.let { !it.isOlderThan(yearStart) } == true },
                averageSessionMinutes = if (yearSessions.isEmpty()) 0 else (minutes.toDouble() / yearSessions.size).roundToInt(),
                pagesPerHour = pace
            )
        }

        /** Hours left to finish [book] at [pagesPerHour]. */
        fun hoursLeft(book: Book, pagesPerHour: Double): Double =
            max(0, book.pages - book.currentPage) / max(1.0, pagesPerHour)
    }
}

data class CatalogBook(val title: String, val author: String, val pages: Int, val tags: Set<String>)

enum class RecommendationReason { GOAL, LIKED_BOOK, POPULAR }

data class Recommendation(
    val book: CatalogBook,
    val score: Double,
    val reason: RecommendationReason,
    /** The goal tag or the liked book title that explains the recommendation. */
    val reasonArg: String
)

/**
 * Content-based recommender: builds a taste profile from the chosen learning goals and the
 * books the reader finished (weighted by rating), then ranks catalog books by tag overlap.
 */
object BookRecommender {
    private const val GOAL_WEIGHT = 2.0
    private const val IN_LIBRARY_WEIGHT = 0.5

    val CATALOG = listOf(
        CatalogBook("Hábitos atómicos", "James Clear", 328, setOf(ReadingTags.HABITS, ReadingTags.PRODUCTIVITY)),
        CatalogBook("El poder de los hábitos", "Charles Duhigg", 408, setOf(ReadingTags.HABITS, ReadingTags.PSYCHOLOGY)),
        CatalogBook("Tiny Habits", "BJ Fogg", 320, setOf(ReadingTags.HABITS, ReadingTags.PSYCHOLOGY)),
        CatalogBook("Mindset", "Carol Dweck", 320, setOf(ReadingTags.PSYCHOLOGY, ReadingTags.HABITS)),
        CatalogBook("Enfócate (Deep Work)", "Cal Newport", 304, setOf(ReadingTags.PRODUCTIVITY)),
        CatalogBook("Esencialismo", "Greg McKeown", 272, setOf(ReadingTags.PRODUCTIVITY)),
        CatalogBook("La semana laboral de 4 horas", "Tim Ferriss", 416, setOf(ReadingTags.PRODUCTIVITY, ReadingTags.BUSINESS)),
        CatalogBook("El hombre más rico de Babilonia", "George S. Clason", 144, setOf(ReadingTags.FINANCE, ReadingTags.CLASSIC)),
        CatalogBook("La psicología del dinero", "Morgan Housel", 256, setOf(ReadingTags.FINANCE, ReadingTags.PSYCHOLOGY)),
        CatalogBook("Padre rico, padre pobre", "Robert Kiyosaki", 336, setOf(ReadingTags.FINANCE)),
        CatalogBook("El inversor inteligente", "Benjamin Graham", 640, setOf(ReadingTags.FINANCE)),
        CatalogBook("El método Lean Startup", "Eric Ries", 336, setOf(ReadingTags.BUSINESS, ReadingTags.TECH)),
        CatalogBook("Harry Potter and the Philosopher's Stone", "J. K. Rowling", 223, setOf(ReadingTags.ENGLISH, ReadingTags.FICTION)),
        CatalogBook("The Alchemist", "Paulo Coelho", 208, setOf(ReadingTags.ENGLISH, ReadingTags.FICTION)),
        CatalogBook("Of Mice and Men", "John Steinbeck", 107, setOf(ReadingTags.ENGLISH, ReadingTags.FICTION, ReadingTags.CLASSIC)),
        CatalogBook("Animal Farm", "George Orwell", 112, setOf(ReadingTags.ENGLISH, ReadingTags.FICTION, ReadingTags.CLASSIC)),
        CatalogBook("Cien años de soledad", "Gabriel García Márquez", 471, setOf(ReadingTags.FICTION, ReadingTags.LATAM, ReadingTags.CLASSIC)),
        CatalogBook("Pedro Páramo", "Juan Rulfo", 124, setOf(ReadingTags.FICTION, ReadingTags.LATAM, ReadingTags.CLASSIC)),
        CatalogBook("El túnel", "Ernesto Sabato", 160, setOf(ReadingTags.FICTION, ReadingTags.LATAM)),
        CatalogBook("Huasipungo", "Jorge Icaza", 240, setOf(ReadingTags.FICTION, ReadingTags.LATAM, ReadingTags.CLASSIC)),
        CatalogBook("Ficciones", "Jorge Luis Borges", 224, setOf(ReadingTags.FICTION, ReadingTags.LATAM, ReadingTags.PHILOSOPHY)),
        CatalogBook("Pensar rápido, pensar despacio", "Daniel Kahneman", 512, setOf(ReadingTags.PSYCHOLOGY, ReadingTags.SCIENCE)),
        CatalogBook("El hombre en busca de sentido", "Viktor Frankl", 160, setOf(ReadingTags.PSYCHOLOGY, ReadingTags.PHILOSOPHY)),
        CatalogBook("Meditaciones", "Marco Aurelio", 256, setOf(ReadingTags.PHILOSOPHY, ReadingTags.CLASSIC)),
        CatalogBook("Por qué dormimos", "Matthew Walker", 368, setOf(ReadingTags.HEALTH, ReadingTags.SCIENCE)),
        CatalogBook("Sapiens", "Yuval Noah Harari", 496, setOf(ReadingTags.HISTORY, ReadingTags.SCIENCE)),
        CatalogBook("Breve historia del tiempo", "Stephen Hawking", 256, setOf(ReadingTags.SCIENCE)),
        CatalogBook("Cosmos", "Carl Sagan", 384, setOf(ReadingTags.SCIENCE)),
        CatalogBook("Código limpio", "Robert C. Martin", 464, setOf(ReadingTags.TECH)),
        CatalogBook("El programador pragmático", "Andrew Hunt y David Thomas", 352, setOf(ReadingTags.TECH, ReadingTags.PRODUCTIVITY))
    )

    private fun normalize(title: String) = title.lowercase().filter { it.isLetterOrDigit() }

    fun recommend(
        goals: Set<String>,
        library: List<Book>,
        limit: Int = 3,
        catalog: List<CatalogBook> = CATALOG
    ): List<Recommendation> {
        val profile = mutableMapOf<String, Double>()
        val goalPart = mutableMapOf<String, Double>()
        val likedBy = mutableMapOf<String, Pair<String, Double>>()
        goals.forEach {
            profile[it] = (profile[it] ?: 0.0) + GOAL_WEIGHT
            goalPart[it] = GOAL_WEIGHT
        }
        library.forEach { book ->
            val w = when {
                book.status == BookStatus.FINISHED && book.rating > 0 -> book.rating - 2.5
                book.status == BookStatus.FINISHED -> 1.0
                else -> IN_LIBRARY_WEIGHT
            }
            book.tags.forEach { tag ->
                profile[tag] = (profile[tag] ?: 0.0) + w
                if (w >= 1.0 && w > (likedBy[tag]?.second ?: 0.0)) likedBy[tag] = book.title to w
            }
        }
        val owned = library.map { normalize(it.title) }.toSet()
        return catalog
            .filter { normalize(it.title) !in owned }
            .map { book ->
                val raw = book.tags.sumOf { profile[it] ?: 0.0 }
                val score = raw / sqrt(book.tags.size.toDouble())
                val bestTag = book.tags.maxByOrNull { profile[it] ?: 0.0 }
                val liked = book.tags.mapNotNull { likedBy[it] }.maxByOrNull { it.second }
                val (reason, arg) = when {
                    profile.isEmpty() || bestTag == null || (profile[bestTag] ?: 0.0) <= 0 -> RecommendationReason.POPULAR to ""
                    liked != null && liked.second >= (goalPart[bestTag] ?: 0.0) -> RecommendationReason.LIKED_BOOK to liked.first
                    else -> RecommendationReason.GOAL to (book.tags.filter { it in goals }.maxByOrNull { profile[it] ?: 0.0 } ?: bestTag)
                }
                Recommendation(book, score, reason, arg)
            }
            .sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.book.pages })
            .take(limit)
    }
}
