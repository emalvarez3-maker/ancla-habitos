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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

enum class IdeaKind { IDEA, SUMMARY, QUOTE }

data class Idea(
    val id: String,
    val title: String,
    val note: String,
    /** Book (or other source) the idea comes from, or empty. */
    val source: String,
    val kind: IdeaKind,
    val created: Long
)

data class IdeaLink(val from: String, val to: String, val relation: String)

/**
 * The user's network of ideas: notes, book summaries and quotes, connected by labeled links.
 * Kept as a small JSON file in the app's private storage.
 */
class IdeaGraph(context: Context) {
    private val file = File(context.filesDir, "ideas.json")

    var ideas: List<Idea> = emptyList()
        private set
    var links: List<IdeaLink> = emptyList()
        private set

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            ideas = json.optJSONArray("ideas").objects().map {
                Idea(
                    id = it.getString("id"),
                    title = it.getString("title"),
                    note = it.optString("note"),
                    source = it.optString("source"),
                    kind = IdeaKind.entries.firstOrNull { k -> k.name == it.optString("kind") } ?: IdeaKind.IDEA,
                    created = it.optLong("created")
                )
            }
            links = json.optJSONArray("links").objects().map { IdeaLink(it.getString("from"), it.getString("to"), it.optString("relation")) }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun save() {
        val json = JSONObject()
            .put(
                "ideas",
                JSONArray(
                    ideas.map {
                        JSONObject().put("id", it.id).put("title", it.title).put("note", it.note)
                            .put("source", it.source).put("kind", it.kind.name).put("created", it.created)
                    }
                )
            )
            .put("links", JSONArray(links.map { JSONObject().put("from", it.from).put("to", it.to).put("relation", it.relation) }))
        val tmp = File(file.parentFile, "ideas.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
    }

    /** Adds an idea, or updates the one with the same title. Returns it. */
    @Synchronized
    fun save(title: String, note: String, source: String, kind: IdeaKind): Idea {
        val existing = ideas.firstOrNull { key(it.title) == key(title) }
        val idea = existing?.copy(note = note.ifBlank { existing.note }, source = source.ifBlank { existing.source }, kind = kind)
            ?: Idea(UUID.randomUUID().toString(), title.trim(), note.trim(), source.trim(), kind, System.currentTimeMillis())
        ideas = ideas.filter { it.id != idea.id } + idea
        save()
        return idea
    }

    /** Links two ideas found by title (loosely); returns false if one is missing. */
    @Synchronized
    fun link(from: String, to: String, relation: String): Pair<Idea, Idea>? {
        val a = find(from) ?: return null
        val b = find(to) ?: return null
        if (a.id == b.id) return null
        links = links.filterNot { (it.from == a.id && it.to == b.id) || (it.from == b.id && it.to == a.id) } +
            IdeaLink(a.id, b.id, relation.trim())
        save()
        return a to b
    }

    @Synchronized
    fun delete(id: String) {
        ideas = ideas.filter { it.id != id }
        links = links.filter { it.from != id && it.to != id }
        save()
    }

    fun find(title: String): Idea? {
        val k = key(title)
        return ideas.firstOrNull { key(it.title) == k }
            ?: ideas.firstOrNull { key(it.title).contains(k) || k.contains(key(it.title)) }
    }

    fun linksOf(id: String) = links.filter { it.from == id || it.to == id }

    companion object {
        fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
