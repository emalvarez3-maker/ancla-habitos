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
import android.net.Uri
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.database.BookRepository
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.ReadingTags
import java.io.File
import java.util.UUID

/** App-side access to the reading library: the database plus imported EPUB files. */
class Library(private val context: Context) {
    val repo = BookRepository((context.applicationContext as HabitsApplication).component.db)

    fun fileOf(book: Book) = File(context.filesDir, book.file)

    /** Copies the EPUB behind [uri] into private storage and adds it as the book being read. Call off the main thread. */
    fun importEpub(uri: Uri): Book = importInto(BookStatus.READING) { target ->
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file" }
            target.outputStream().use { input.copyTo(it) }
        }
    }

    /**
     * Downloads a public-domain book from Project Gutenberg. If the library already lists it
     * without a file (a "want to read" entry), that entry gets the EPUB. Call off the main thread.
     */
    fun downloadFree(gutenbergId: Int): Book {
        val book = importInto(BookStatus.WANT) { target -> FreeBooks.download(gutenbergId, target) }
        val key = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val existing = repo.findAll().firstOrNull { it.id != book.id && !it.isEbook && key(it.title) == key(book.title) } ?: return book
        // Keep the user's entry (status, notes) and move the file onto it.
        val merged = existing.copy(file = book.file, pages = book.pages, author = existing.author.ifBlank { book.author })
        repo.update(merged)
        book.id?.let(repo::delete)
        return merged
    }

    private fun importInto(status: BookStatus, fetch: (File) -> Unit): Book {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val name = "books/${UUID.randomUUID()}.epub"
        val target = File(context.filesDir, name)
        try {
            fetch(target)
            EpubBook(target).use { epub ->
                val book = Book(
                    title = epub.title,
                    author = epub.author,
                    pages = epub.estimatedPages,
                    status = status,
                    tags = ReadingTags.fromSubjects(epub.subjects + epub.title),
                    addedDay = getToday(),
                    file = name
                )
                return book.copy(id = repo.insert(book))
            }
        } catch (e: Exception) {
            target.delete()
            if (dir.list().isNullOrEmpty()) dir.delete()
            throw e
        }
    }

    fun delete(book: Book) {
        book.id?.let(repo::delete)
        if (book.isEbook) fileOf(book).delete()
    }
}
