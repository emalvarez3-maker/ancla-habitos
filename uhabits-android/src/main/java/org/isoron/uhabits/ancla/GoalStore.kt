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

package org.isoron.uhabits.ancla

import android.content.Context
import org.isoron.uhabits.ancla.reading.IdeaGraph
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One long-term goal on the user's three-year Camino. [habits] are the habits that feed it, by name. */
data class Goal(
    val id: String,
    val title: String,
    val year: Int,
    val note: String = "",
    val progress: Int = 0,
    val done: Boolean = false,
    val habits: List<String> = emptyList(),
    val created: Long = 0
)

/**
 * The goals of the Camino, the three-year roadmap: added and changed by the user on screen or by
 * telling Dexter. Kept encrypted on the phone, like notes and study.
 */
class GoalStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "goals.json"))

    /** Every goal, nearest year first, then in the order they were added. */
    fun all(): List<Goal> = synchronized(LOCK) { load() }.sortedWith(compareBy<Goal>({ it.year }, { it.created }))

    /** The goal whose title matches [title] best: the same words, else one containing the other. */
    fun find(title: String): Goal? {
        val k = IdeaGraph.key(title)
        if (k.isEmpty()) return null
        val goals = all()
        return goals.firstOrNull { IdeaGraph.key(it.title) == k }
            ?: goals.firstOrNull { k.length >= 3 && (IdeaGraph.key(it.title).contains(k) || k.contains(IdeaGraph.key(it.title))) }
    }

    fun add(title: String, year: Int, note: String = "", habit: String? = null): Goal = synchronized(LOCK) {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "La meta necesita un nombre" }
        require(year in MIN_YEAR..MAX_YEAR) { "Año fuera de rango: $year" }
        val goals = load()
        // The same goal said twice is updated, not duplicated.
        val existing = goals.firstOrNull { IdeaGraph.key(it.title) == IdeaGraph.key(clean) }
        val goal = existing?.copy(year = year, note = note.ifBlank { existing.note }, habits = (existing.habits + listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() })).distinct())
            ?: Goal(UUID.randomUUID().toString(), clean, year, note.trim(), habits = listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() }), created = System.currentTimeMillis())
        save(goals.filter { it.id != goal.id } + goal)
        goal
    }

    /** Changes the goal matching [title]; null when there is none. */
    fun update(
        title: String,
        progress: Int? = null,
        done: Boolean? = null,
        year: Int? = null,
        newTitle: String? = null,
        note: String? = null,
        habit: String? = null
    ): Goal? = synchronized(LOCK) {
        val target = find(title) ?: return@synchronized null
        year?.let { require(it in MIN_YEAR..MAX_YEAR) { "Año fuera de rango: $it" } }
        val changed = target.copy(
            title = newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: target.title,
            year = year ?: target.year,
            note = note?.trim() ?: target.note,
            progress = (progress ?: if (done == true) 100 else target.progress).coerceIn(0, 100),
            done = done ?: ((progress != null && progress >= 100) || target.done),
            habits = (target.habits + listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() })).distinct()
        )
        save(load().map { if (it.id == target.id) changed else it })
        changed
    }

    fun remove(title: String): Goal? = synchronized(LOCK) {
        val target = find(title) ?: return@synchronized null
        save(load().filter { it.id != target.id })
        target
    }

    private fun load(): List<Goal> {
        val text = EncryptedStore.readText(file) ?: return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("goals") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val habits = o.optJSONArray("habits")
            Goal(
                o.getString("id"), o.getString("title"), o.getInt("year"), o.optString("note"),
                o.optInt("progress"), o.optBoolean("done"),
                if (habits == null) emptyList() else (0 until habits.length()).map { habits.getString(it) },
                o.optLong("created")
            )
        }
    }

    private fun save(goals: List<Goal>) {
        val arr = JSONArray(
            goals.map {
                JSONObject().put("id", it.id).put("title", it.title).put("year", it.year).put("note", it.note)
                    .put("progress", it.progress).put("done", it.done).put("habits", JSONArray(it.habits)).put("created", it.created)
            }
        )
        EncryptedStore.writeText(file, JSONObject().put("goals", arr).toString())
    }

    companion object {
        private val LOCK = Any()
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2100

        /** For Dexter: the roadmap in a few lines. */
        fun describe(goals: List<Goal>): String = if (goals.isEmpty()) {
            "El Camino está vacío: todavía no hay metas."
        } else {
            goals.groupBy { it.year }.toSortedMap().entries.joinToString("\n") { (year, list) ->
                "$year: " + list.joinToString("; ") { g ->
                    g.title + (if (g.done) " (lograda)" else " (${g.progress} %)") +
                        (if (g.habits.isNotEmpty()) ", la alimentan: ${g.habits.joinToString()}" else "") +
                        (if (g.note.isNotBlank()) ", nota: ${g.note}" else "")
                }
            }
        }
    }
}
