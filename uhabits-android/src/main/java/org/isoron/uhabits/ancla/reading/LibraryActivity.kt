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

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookRecommender
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.ReadingSession
import org.isoron.uhabits.core.models.ReadingStats
import org.isoron.uhabits.core.models.ReadingTags
import org.isoron.uhabits.core.models.Recommendation

data class LibraryState(
    val books: List<Book>,
    val stats: ReadingStats,
    val goals: Set<String>,
    val recommendations: List<Recommendation>,
    val importing: Boolean = false
)

/** The reading tab: books in progress, reading time, recommendations and the to-read list. */
class LibraryActivity : AppCompatActivity() {

    private lateinit var library: Library
    private val prefs by lazy { getSharedPreferences("ancla_reading", Context.MODE_PRIVATE) }
    private val state = mutableStateOf<LibraryState?>(null)
    private var importing = false

    private val pickEpub = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        importing = true
        refresh()
        Thread {
            val result = runCatching { library.importEpub(uri) }
            runOnUiThread {
                importing = false
                result.onSuccess { startActivity(ReaderActivity.intent(this, it.id!!)) }
                    .onFailure { Toast.makeText(this, R.string.library_import_failed, Toast.LENGTH_LONG).show() }
                refresh()
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        library = Library(this)
        setContent {
            AnclaTheme {
                LibraryScreen(
                    state = state.value,
                    onBack = { finish() },
                    onImport = { pickEpub.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                    onOpen = ::open,
                    onToggleGoal = ::toggleGoal,
                    onWant = ::addWanted,
                    onStart = { book -> update(book.copy(status = BookStatus.READING)) },
                    onLog = ::logReading,
                    onAddPaper = ::addPaper,
                    onDelete = { book -> library.delete(book); refresh() },
                    onSearch = { query, callback ->
                        Thread {
                            val results = OpenLibrary.search(query)
                            runOnUiThread { callback(results) }
                        }.start()
                    },
                    onIdeas = { startActivity(android.content.Intent(this, IdeasActivity::class.java)) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun goals(): Set<String> = prefs.getStringSet("goals", null)?.toSet() ?: setOf(ReadingTags.HABITS)

    private fun refresh() {
        val books = library.repo.findAll()
        val today = getToday()
        val sessions = library.repo.findSessionsSince(today.startOfYear())
        val goals = goals()
        state.value = LibraryState(
            books = books,
            stats = ReadingStats.compute(books, sessions, today),
            goals = goals,
            recommendations = BookRecommender.recommend(goals, books),
            importing = importing
        )
    }

    private fun open(book: Book) {
        if (book.isEbook) startActivity(ReaderActivity.intent(this, book.id!!))
    }

    private fun toggleGoal(tag: String) {
        val g = goals().toMutableSet()
        if (!g.add(tag)) g.remove(tag)
        prefs.edit().putStringSet("goals", g).apply()
        refresh()
    }

    private fun addWanted(rec: Recommendation) {
        library.repo.insert(
            Book(title = rec.book.title, author = rec.book.author, pages = rec.book.pages, tags = rec.book.tags, addedDay = getToday())
        )
        refresh()
    }

    private fun addPaper(result: BookSearchResult, startNow: Boolean) {
        library.repo.insert(
            Book(
                title = result.title,
                author = result.author,
                pages = result.pages,
                tags = result.tags,
                status = if (startNow) BookStatus.READING else BookStatus.WANT,
                addedDay = getToday()
            )
        )
        refresh()
    }

    private fun logReading(book: Book, minutes: Int, page: Int) {
        val newPage = page.coerceIn(book.currentPage, if (book.pages > 0) book.pages else Int.MAX_VALUE)
        library.repo.insertSession(ReadingSession(bookId = book.id!!, day = getToday(), minutes = minutes, pages = newPage - book.currentPage))
        val finished = book.pages > 0 && newPage >= book.pages
        update(
            book.copy(
                currentPage = newPage,
                status = if (finished) BookStatus.FINISHED else book.status,
                finishedDay = if (finished) getToday() else book.finishedDay
            )
        )
    }

    private fun update(book: Book) {
        library.repo.update(book)
        refresh()
    }
}
