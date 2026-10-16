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
    fun importEpub(uri: Uri): Book {
        val dir = File(context.filesDir, "books").apply { mkdirs() }
        val name = "books/${UUID.randomUUID()}.epub"
        val target = File(context.filesDir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open the selected file" }
            target.outputStream().use { input.copyTo(it) }
        }
        try {
            EpubBook(target).use { epub ->
                val book = Book(
                    title = epub.title,
                    author = epub.author,
                    pages = epub.estimatedPages,
                    status = BookStatus.READING,
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
