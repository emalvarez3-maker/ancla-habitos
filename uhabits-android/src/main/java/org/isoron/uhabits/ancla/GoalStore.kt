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

/** A step on a goal's own roadmap, due in a month ("2027-03"), or any time when [due] is empty. */
data class Milestone(val id: String, val title: String, val due: String = "", val done: Boolean = false)

/**
 * One long-term goal on the user's three-year Camino. [habits] are the habits that feed it, by name.
 * WOOP (Gabriele Oettingen's mental contrasting): the title is the Wish, [outcome] the best result
 * imagined, [obstacle] the inner obstacle most likely to get in the way, [plan] the if-then plan for
 * it. [milestones] are the goal's own roadmap; when there are any, they set its progress.
 */
data class Goal(
    val id: String,
    val title: String,
    val year: Int,
    val note: String = "",
    val progress: Int = 0,
    val done: Boolean = false,
    val habits: List<String> = emptyList(),
    val created: Long = 0,
    val outcome: String = "",
    val obstacle: String = "",
    val plan: String = "",
    val milestones: List<Milestone> = emptyList(),
    /** The life roadmap it belongs to (Carrera, Salud, Finanzas…); blank is "General". */
    val roadmap: String = ""
) {
    /** Progress from the milestones when it has them, else the one set by hand. */
    val effectiveProgress: Int
        get() = when {
            done -> 100
            milestones.isNotEmpty() -> milestones.count { it.done } * 100 / milestones.size
            else -> progress
        }

    val hasWoop get() = outcome.isNotBlank() || obstacle.isNotBlank() || plan.isNotBlank()
}

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

    fun add(title: String, year: Int, note: String = "", habit: String? = null, roadmap: String? = null): Goal = synchronized(LOCK) {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "La meta necesita un nombre" }
        require(year in MIN_YEAR..MAX_YEAR) { "Año fuera de rango: $year" }
        val goals = load()
        // The same goal said twice is updated, not duplicated.
        val existing = goals.firstOrNull { IdeaGraph.key(it.title) == IdeaGraph.key(clean) }
        val goal = existing?.copy(year = year, note = note.ifBlank { existing.note }, habits = (existing.habits + listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() })).distinct())
            ?: Goal(UUID.randomUUID().toString(), clean, year, note.trim(), habits = listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() }), created = System.currentTimeMillis())
        val placed = roadmap?.takeIf { it.isNotBlank() }?.let { goal.copy(roadmap = roadmapName(it)) } ?: goal
        save(goals.filter { it.id != placed.id } + placed)
        placed
    }

    /** The roadmaps in use, in the order of [ROADMAPS] and then the user's own. */
    fun roadmaps(): List<String> {
        val used = all().map { it.roadmap.ifBlank { GENERAL } }.distinct()
        return ROADMAPS.filter { it in used } + used.filter { it !in ROADMAPS }.sorted()
    }

    /** Changes the goal matching [title]; null when there is none. */
    fun update(
        title: String,
        progress: Int? = null,
        done: Boolean? = null,
        year: Int? = null,
        newTitle: String? = null,
        note: String? = null,
        habit: String? = null,
        roadmap: String? = null
    ): Goal? = synchronized(LOCK) {
        val target = find(title) ?: return@synchronized null
        year?.let { require(it in MIN_YEAR..MAX_YEAR) { "Año fuera de rango: $it" } }
        val changed = target.copy(
            title = newTitle?.trim()?.takeIf { it.isNotEmpty() } ?: target.title,
            year = year ?: target.year,
            note = note?.trim() ?: target.note,
            progress = (progress ?: if (done == true) 100 else target.progress).coerceIn(0, 100),
            done = done ?: ((progress != null && progress >= 100) || target.done),
            habits = (target.habits + listOfNotNull(habit?.trim()?.takeIf { it.isNotEmpty() })).distinct(),
            roadmap = roadmap?.takeIf { it.isNotBlank() }?.let { roadmapName(it) } ?: target.roadmap
        )
        save(load().map { if (it.id == target.id) changed else it })
        changed
    }

    fun remove(title: String): Goal? = synchronized(LOCK) {
        val target = find(title) ?: return@synchronized null
        save(load().filter { it.id != target.id })
        target
    }

    /** Sets the WOOP of the goal matching [title]; null fields keep what it had. */
    fun setWoop(title: String, outcome: String?, obstacle: String?, plan: String?): Goal? = change(title) { g ->
        g.copy(outcome = outcome?.trim() ?: g.outcome, obstacle = obstacle?.trim() ?: g.obstacle, plan = plan?.trim() ?: g.plan)
    }

    /** Adds a step to the goal's own roadmap, due in [due] ("2027-03") or any time. */
    fun addMilestone(goal: String, title: String, due: String? = null): Goal? {
        val clean = title.trim()
        require(clean.isNotEmpty()) { "El hito necesita un nombre" }
        val month = due?.trim()?.takeIf { Regex("\\d{4}-\\d{2}").matches(it) }.orEmpty()
        return change(goal) { g ->
            val same = g.milestones.firstOrNull { IdeaGraph.key(it.title) == IdeaGraph.key(clean) }
            val m = same?.copy(due = month.ifEmpty { same.due }) ?: Milestone(UUID.randomUUID().toString(), clean, month)
            g.copy(milestones = (g.milestones.filter { it.id != m.id } + m).sortedWith(compareBy({ it.due.ifEmpty { "9999" } })))
        }
    }

    /** Marks the milestone matching [milestone] done or not; reaching the last one completes the goal. */
    fun setMilestoneDone(goal: String, milestone: String, done: Boolean = true): Goal? = change(goal) { g ->
        val k = IdeaGraph.key(milestone)
        val target = g.milestones.firstOrNull { it.id == milestone || IdeaGraph.key(it.title) == k }
            ?: g.milestones.firstOrNull { k.length >= 3 && IdeaGraph.key(it.title).contains(k) }
            ?: throw IllegalArgumentException("No hay un hito «$milestone» en «${g.title}». Hitos: " + g.milestones.joinToString { it.title })
        val milestones = g.milestones.map { if (it.id == target.id) it.copy(done = done) else it }
        g.copy(milestones = milestones, done = milestones.all { it.done })
    }

    fun removeMilestone(goal: String, milestoneId: String): Goal? = change(goal) { g -> g.copy(milestones = g.milestones.filter { it.id != milestoneId }) }

    private fun change(title: String, f: (Goal) -> Goal): Goal? = synchronized(LOCK) {
        val target = find(title) ?: return@synchronized null
        val changed = f(target)
        save(load().map { if (it.id == target.id) changed else it })
        changed
    }

    /** Changes whenever the goals may have changed; cheap, for the Camino screen's poll. */
    fun version(): Long = synchronized(LOCK) { StoreCache.version(file, ::parse) }

    private fun load(): List<Goal> = StoreCache.read(file, ::parse)

    private fun parse(text: String?): List<Goal> {
        text ?: return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("goals") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val habits = o.optJSONArray("habits")
            val steps = o.optJSONArray("milestones")
            Goal(
                o.getString("id"), o.getString("title"), o.getInt("year"), o.optString("note"),
                o.optInt("progress"), o.optBoolean("done"),
                if (habits == null) emptyList() else (0 until habits.length()).map { habits.getString(it) },
                o.optLong("created"),
                o.optString("outcome"), o.optString("obstacle"), o.optString("plan"),
                if (steps == null) emptyList() else (0 until steps.length()).map { steps.getJSONObject(it) }.map {
                    Milestone(it.getString("id"), it.getString("title"), it.optString("due"), it.optBoolean("done"))
                },
                o.optString("roadmap")
            )
        }
    }

    private fun save(goals: List<Goal>) {
        val arr = JSONArray(
            goals.map { g ->
                JSONObject().put("id", g.id).put("title", g.title).put("year", g.year).put("note", g.note)
                    .put("progress", g.progress).put("done", g.done).put("habits", JSONArray(g.habits)).put("created", g.created)
                    .put("outcome", g.outcome).put("obstacle", g.obstacle).put("plan", g.plan)
                    .put("milestones", JSONArray(g.milestones.map { JSONObject().put("id", it.id).put("title", it.title).put("due", it.due).put("done", it.done) }))
                    .put("roadmap", g.roadmap)
            }
        )
        EncryptedStore.writeText(file, JSONObject().put("goals", arr).toString())
        StoreCache.wrote(file, goals.toList())
    }

    companion object {
        private val LOCK = Any()
        const val MIN_YEAR = 2000
        const val MAX_YEAR = 2100

        /** Goals with no roadmap of their own. */
        const val GENERAL = "General"

        /** The life roadmaps offered first; the user can make their own. */
        val ROADMAPS = listOf("Carrera", "Estudios", "Salud", "Finanzas", "Relaciones", "Crecimiento personal", "Aventuras", GENERAL)

        /** The usual spelling of a roadmap ("salud" → "Salud"); a new one keeps the user's words. */
        fun roadmapName(name: String): String {
            val k = IdeaGraph.key(name)
            return ROADMAPS.firstOrNull { IdeaGraph.key(it) == k } ?: name.trim().replaceFirstChar { it.uppercase() }.ifBlank { GENERAL }
        }

        /** For Dexter: the roadmap in a few lines. */
        fun describe(goals: List<Goal>): String = if (goals.isEmpty()) {
            "El Camino está vacío: todavía no hay metas."
        } else {
            goals.groupBy { it.year }.toSortedMap().entries.joinToString("\n") { (year, list) ->
                "$year: " + list.joinToString("; ") { g ->
                    g.title + " [roadmap ${g.roadmap.ifBlank { GENERAL }}]" + (if (g.done) " (lograda)" else " (${g.effectiveProgress} %)") +
                        (if (g.habits.isNotEmpty()) ", la alimentan: ${g.habits.joinToString()}" else "") +
                        (if (g.note.isNotBlank()) ", nota: ${g.note}" else "") +
                        (if (g.outcome.isNotBlank()) ", resultado soñado: ${g.outcome}" else "") +
                        (if (g.obstacle.isNotBlank()) ", obstáculo: ${g.obstacle}" else "") +
                        (if (g.plan.isNotBlank()) ", plan si-entonces: ${g.plan}" else "") +
                        (if (g.milestones.isNotEmpty()) ", hitos: " + g.milestones.joinToString { m -> (if (m.done) "✓ " else "") + m.title + (if (m.due.isNotEmpty()) " (${m.due})" else "") } else "") +
                        (if (!g.hasWoop && !g.done) " [sin WOOP todavía]" else "")
                }
            }
        }
    }
}
