package org.isoron.uhabits.core.models

import kotlinx.coroutines.test.runTest
import org.isoron.platform.io.TestDatabaseHelper
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.database.BookRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadingTest {
    // Friday 2026-10-16; the week starts on Monday 2026-10-12.
    private val today = LocalDate(2026, 10, 16)

    @Test
    fun testRepositoryRoundTrip() = runTest {
        val db = TestDatabaseHelper.createEmptyDatabase()
        val repo = BookRepository(db)
        val id = repo.insert(
            Book(title = "Hábitos atómicos", author = "James Clear", pages = 328, status = BookStatus.READING, currentPage = 142, tags = setOf("habitos", "productividad"), addedDay = today, file = "books/1.epub", location = "3:0.5")
        )
        val book = repo.findById(id)!!
        assertEquals(setOf("habitos", "productividad"), book.tags)
        assertEquals(BookStatus.READING, book.status)
        assertNull(book.finishedDay)
        assertEquals("books/1.epub", book.file)
        assertEquals("3:0.5", book.location)
        assertTrue(book.isEbook)

        repo.update(book.copy(status = BookStatus.FINISHED, rating = 5, finishedDay = today, currentPage = 328))
        assertEquals(5, repo.findById(id)!!.rating)

        repo.insertSession(ReadingSession(bookId = id, day = today, minutes = 25, pages = 18))
        repo.insertSession(ReadingSession(bookId = id, day = today.minus(10), minutes = 30, pages = 20))
        assertEquals(1, repo.findSessionsSince(today.minus(3)).size)

        repo.delete(id)
        assertNull(repo.findById(id))
        assertEquals(0, repo.findSessionsSince(today.minus(30)).size)
        db.close()
    }

    @Test
    fun testStats() {
        val sessions = listOf(
            ReadingSession(bookId = 1, day = today.minus(4), minutes = 32, pages = 20), // Monday
            ReadingSession(bookId = 1, day = today, minutes = 18, pages = 12),
            ReadingSession(bookId = 1, day = today, minutes = 10, pages = 8),
            ReadingSession(bookId = 1, day = today.minus(20), minutes = 40, pages = 0)
        )
        val books = listOf(Book(title = "A", status = BookStatus.FINISHED, addedDay = today, finishedDay = today.minus(2)))
        val s = ReadingStats.compute(books, sessions, today)
        assertEquals(listOf(32, 0, 0, 0, 28, 0, 0), s.weekMinutes)
        assertEquals(100, s.yearMinutes)
        assertEquals(1, s.booksFinishedThisYear)
        assertEquals(25, s.averageSessionMinutes)
        assertEquals(40.0 * 60 / 60, s.pagesPerHour) // 40 pages in 60 minutes
    }

    @Test
    fun testRecommendByGoal() {
        val recs = BookRecommender.recommend(setOf(ReadingTags.FINANCE), emptyList())
        assertEquals(3, recs.size)
        assertTrue(recs.all { ReadingTags.FINANCE in it.book.tags })
        assertEquals(RecommendationReason.GOAL, recs.first().reason)
        assertEquals(ReadingTags.FINANCE, recs.first().reasonArg)
    }

    @Test
    fun testRecommendFromLikedBookAndSkipOwned() {
        val liked = Book(
            title = "Cien años de soledad",
            status = BookStatus.FINISHED,
            rating = 5,
            tags = setOf(ReadingTags.FICTION, ReadingTags.LATAM, ReadingTags.CLASSIC),
            addedDay = today
        )
        val recs = BookRecommender.recommend(emptySet(), listOf(liked))
        assertTrue(recs.none { it.book.title == "Cien años de soledad" })
        assertTrue(recs.first().book.tags.contains(ReadingTags.LATAM))
        assertEquals(RecommendationReason.LIKED_BOOK, recs.first().reason)
        assertEquals("Cien años de soledad", recs.first().reasonArg)
    }

    @Test
    fun testTagsFromSubjects() {
        assertEquals(
            setOf(ReadingTags.FINANCE, ReadingTags.PSYCHOLOGY),
            ReadingTags.fromSubjects(listOf("Personal finance", "Psychology of money"))
        )
    }
}
