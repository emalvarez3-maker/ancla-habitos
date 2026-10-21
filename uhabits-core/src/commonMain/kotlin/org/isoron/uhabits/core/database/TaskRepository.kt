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
import org.isoron.uhabits.core.models.DayTask

/** Stores Ancla's one-off day tasks in the DayTasks table. */
class TaskRepository(private val db: Database) {
    private val columns = "id, title, notes, day, minute, reminder, done"

    /** Tasks for [today], plus pending tasks from earlier days, ordered by day and time. */
    fun findForToday(today: LocalDate): List<DayTask> = query(
        "SELECT $columns FROM DayTasks WHERE day = ? OR (day < ? AND done = 0) " +
            "ORDER BY day, CASE WHEN minute IS NULL THEN 1 ELSE 0 END, minute, id"
    ) {
        bindInt(1, today.daysSince2000)
        bindInt(2, today.daysSince2000)
    }

    /** Pending tasks with a reminder at or after [from] (for rescheduling alarms). */
    fun findUpcomingReminders(from: LocalDate): List<DayTask> = query(
        "SELECT $columns FROM DayTasks WHERE reminder = 1 AND done = 0 AND minute IS NOT NULL AND day >= ?"
    ) { bindInt(1, from.daysSince2000) }

    /** Every task not done yet, whatever its day, ordered by day and time. */
    fun findPending(): List<DayTask> = query(
        "SELECT $columns FROM DayTasks WHERE done = 0 ORDER BY day, CASE WHEN minute IS NULL THEN 1 ELSE 0 END, minute, id"
    ) {}

    fun findById(id: Long): DayTask? = query("SELECT $columns FROM DayTasks WHERE id = ?") { bindLong(1, id) }.firstOrNull()

    fun insert(task: DayTask): Long {
        val stmt = db.prepareStatement(
            "INSERT INTO DayTasks(title, notes, day, minute, reminder, done) VALUES (?, ?, ?, ?, ?, ?)"
        )
        bind(stmt, task)
        stmt.step()
        stmt.finalize()
        return db.queryLong("SELECT last_insert_rowid()")
    }

    fun update(task: DayTask) {
        val stmt = db.prepareStatement(
            "UPDATE DayTasks SET title = ?, notes = ?, day = ?, minute = ?, reminder = ?, done = ? WHERE id = ?"
        )
        bind(stmt, task)
        stmt.bindLong(7, task.id!!)
        stmt.step()
        stmt.finalize()
    }

    fun delete(id: Long) {
        val stmt = db.prepareStatement("DELETE FROM DayTasks WHERE id = ?")
        stmt.bindLong(1, id)
        stmt.step()
        stmt.finalize()
    }

    private fun bind(stmt: PreparedStatement, task: DayTask) {
        stmt.bindText(1, task.title)
        stmt.bindText(2, task.notes)
        stmt.bindInt(3, task.day.daysSince2000)
        if (task.minuteOfDay != null) stmt.bindInt(4, task.minuteOfDay) else stmt.bindNull(4)
        stmt.bindInt(5, if (task.remind) 1 else 0)
        stmt.bindInt(6, if (task.isDone) 1 else 0)
    }

    private fun query(sql: String, bind: PreparedStatement.() -> Unit): List<DayTask> {
        val stmt = db.prepareStatement(sql)
        stmt.bind()
        val out = mutableListOf<DayTask>()
        while (stmt.step() == StepResult.ROW) {
            out.add(
                DayTask(
                    id = stmt.getLong(0),
                    title = stmt.getText(1),
                    notes = stmt.getTextOrNull(2) ?: "",
                    day = LocalDate(stmt.getInt(3)),
                    minuteOfDay = stmt.getIntOrNull(4),
                    remind = stmt.getInt(5) != 0,
                    isDone = stmt.getInt(6) != 0
                )
            )
        }
        stmt.finalize()
        return out
    }
}
