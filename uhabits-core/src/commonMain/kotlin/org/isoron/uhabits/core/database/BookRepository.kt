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
package org.isoron.uhabits.core.database

import org.isoron.platform.io.Database
import org.isoron.platform.io.PreparedStatement
import org.isoron.platform.io.StepResult
import org.isoron.platform.io.queryLong
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.ReadingSession
import org.isoron.uhabits.core.models.ReadingTags

/** Stores Ancla's library (Books) and reading time (ReadingSessions). */
class BookRepository(private val db: Database) {
    private val bookColumns = "id, title, author, pages, status, current_page, tags, rating, added_day, finished_day, file, location"

    fun findAll(): List<Book> = queryBooks("SELECT $bookColumns FROM Books ORDER BY status = 1 DESC, added_day DESC, id DESC") {}

    fun findById(id: Long): Book? = queryBooks("SELECT $bookColumns FROM Books WHERE id = ?") { bindLong(1, id) }.firstOrNull()

    fun insert(book: Book): Long {
        exec("INSERT INTO Books(title, author, pages, status, current_page, tags, rating, added_day, finished_day, file, location) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)") {
            bindBook(this, book)
        }
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun update(book: Book) {
        exec("UPDATE Books SET title = ?, author = ?, pages = ?, status = ?, current_page = ?, tags = ?, rating = ?, added_day = ?, finished_day = ?, file = ?, location = ? WHERE id = ?") {
            bindBook(this, book)
            bindLong(12, book.id!!)
        }
    }

    fun delete(id: Long) {
        exec("DELETE FROM ReadingSessions WHERE book = ?") { bindLong(1, id) }
        exec("DELETE FROM Books WHERE id = ?") { bindLong(1, id) }
    }

    fun insertSession(session: ReadingSession): Long {
        exec("INSERT INTO ReadingSessions(book, day, minutes, pages) VALUES (?, ?, ?, ?)") {
            bindLong(1, session.bookId)
            bindInt(2, session.day.daysSince2000)
            bindInt(3, session.minutes)
            bindInt(4, session.pages)
        }
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun findSessionsSince(from: LocalDate): List<ReadingSession> {
        val stmt = db.prepareStatement("SELECT id, book, day, minutes, pages FROM ReadingSessions WHERE day >= ? ORDER BY day")
        stmt.bindInt(1, from.daysSince2000)
        val out = mutableListOf<ReadingSession>()
        while (stmt.step() == StepResult.ROW) {
            out.add(ReadingSession(stmt.getLong(0), stmt.getLong(1), LocalDate(stmt.getInt(2)), stmt.getInt(3), stmt.getInt(4)))
        }
        stmt.finalize()
        return out
    }

    private fun bindBook(stmt: PreparedStatement, book: Book) {
        stmt.bindText(1, book.title)
        stmt.bindText(2, book.author)
        stmt.bindInt(3, book.pages)
        stmt.bindInt(4, book.status.value)
        stmt.bindInt(5, book.currentPage)
        stmt.bindText(6, ReadingTags.encode(book.tags))
        stmt.bindInt(7, book.rating)
        stmt.bindInt(8, book.addedDay.daysSince2000)
        val finished = book.finishedDay
        if (finished != null) stmt.bindInt(9, finished.daysSince2000) else stmt.bindNull(9)
        stmt.bindText(10, book.file)
        stmt.bindText(11, book.location)
    }

    private fun exec(sql: String, bind: PreparedStatement.() -> Unit) {
        val stmt = db.prepareStatement(sql)
        stmt.bind()
        stmt.step()
        stmt.finalize()
    }

    private fun queryBooks(sql: String, bind: PreparedStatement.() -> Unit): List<Book> {
        val stmt = db.prepareStatement(sql)
        stmt.bind()
        val out = mutableListOf<Book>()
        while (stmt.step() == StepResult.ROW) {
            out.add(
                Book(
                    id = stmt.getLong(0),
                    title = stmt.getText(1),
                    author = stmt.getTextOrNull(2) ?: "",
                    pages = stmt.getInt(3),
                    status = BookStatus.fromInt(stmt.getInt(4)),
                    currentPage = stmt.getInt(5),
                    tags = ReadingTags.decode(stmt.getTextOrNull(6) ?: ""),
                    rating = stmt.getInt(7),
                    addedDay = LocalDate(stmt.getInt(8)),
                    finishedDay = stmt.getIntOrNull(9)?.let { LocalDate(it) },
                    file = stmt.getTextOrNull(10) ?: "",
                    location = stmt.getTextOrNull(11) ?: ""
                )
            )
        }
        stmt.finalize()
        return out
    }
}
