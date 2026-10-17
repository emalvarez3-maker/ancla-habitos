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
import org.isoron.uhabits.ancla.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class IdeaKind { IDEA, SUMMARY, QUOTE, JOURNAL, STUDY }

/** A note in the user's second brain: Markdown text that links to other notes with [[Title]]. */
data class Idea(
    val id: String,
    val title: String,
    val note: String,
    /** Book (or other source) the note comes from, or empty. */
    val source: String,
    val kind: IdeaKind,
    val created: Long,
    val updated: Long = created
)

/** A connection between two notes: labeled by Dexter or the user, or implied by a [[link]] in the text. */
data class IdeaLink(val from: String, val to: String, val relation: String, val implicit: Boolean = false)

/**
 * The user's notes, Obsidian-style but inside Ancla: Markdown notes with [[wikilinks]], backlinks,
 * labeled connections, a daily journal and a graph, kept as a JSON file in private storage,
 * encrypted with [EncryptedStore].
 *
 * Several parts of the app (Dexter, studying, the screens) each hold an IdeaGraph, so every read
 * reloads the file when someone else changed it, and every change re-reads and writes it under
 * one process-wide lock: no instance can overwrite another's work.
 */
class IdeaGraph(context: Context) {
    private val file = File(context.filesDir, "ideas.json")
    private var cachedIdeas: List<Idea> = emptyList()
    private var cachedLabeled: List<IdeaLink> = emptyList()
    private var cachedStamp = Long.MIN_VALUE

    val ideas: List<Idea>
        get() = synchronized(LOCK) {
            fresh()
            cachedIdeas
        }

    /** Every connection: labeled ones plus those implied by [[links]] in the notes. */
    val links: List<IdeaLink>
        get() = synchronized(LOCK) {
            fresh()
            val byKey = cachedIdeas.associateBy { key(it.title) }
            val implicit = cachedIdeas.flatMap { idea ->
                wikiTargets(idea.note).mapNotNull { byKey[key(it)] }.filter { it.id != idea.id }
                    .map { IdeaLink(idea.id, it.id, "", implicit = true) }
            }
            val labeledPairs = cachedLabeled.map { setOf(it.from, it.to) }.toSet()
            cachedLabeled + implicit.filter { setOf(it.from, it.to) !in labeledPairs }.distinctBy { setOf(it.from, it.to) }
        }

    private fun stamp() = if (file.exists()) file.lastModified() * 31 + file.length() else 0L

    private fun fresh() {
        if (stamp() == cachedStamp) return
        cachedIdeas = emptyList()
        cachedLabeled = emptyList()
        val text = EncryptedStore.readText(file) // also encrypts an old plain-text file
        cachedStamp = stamp()
        if (text == null) return
        runCatching {
            val json = JSONObject(text)
            cachedIdeas = json.optJSONArray("ideas").objects().map {
                val created = it.optLong("created")
                Idea(
                    id = it.getString("id"),
                    title = it.getString("title"),
                    note = it.optString("note"),
                    source = it.optString("source"),
                    kind = IdeaKind.entries.firstOrNull { k -> k.name == it.optString("kind") } ?: IdeaKind.IDEA,
                    created = created,
                    updated = it.optLong("updated", created)
                )
            }
            cachedLabeled = json.optJSONArray("links").objects().map { IdeaLink(it.getString("from"), it.getString("to"), it.optString("relation")) }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** Applies [change] to the latest notes and links on disk and saves the result. */
    private fun <T> mutate(change: (MutableList<Idea>, MutableList<IdeaLink>) -> T): T = synchronized(LOCK) {
        fresh()
        val ideas = cachedIdeas.toMutableList()
        val labeled = cachedLabeled.toMutableList()
        val result = change(ideas, labeled)
        val json = JSONObject()
            .put(
                "ideas",
                JSONArray(
                    ideas.map {
                        JSONObject().put("id", it.id).put("title", it.title).put("note", it.note)
                            .put("source", it.source).put("kind", it.kind.name).put("created", it.created).put("updated", it.updated)
                    }
                )
            )
            .put("links", JSONArray(labeled.map { JSONObject().put("from", it.from).put("to", it.to).put("relation", it.relation) }))
        EncryptedStore.writeText(file, json.toString())
        cachedIdeas = ideas
        cachedLabeled = labeled
        cachedStamp = stamp()
        result
    }

    private fun MutableList<Idea>.upsert(idea: Idea) {
        val i = indexOfFirst { it.id == idea.id }
        if (i >= 0) set(i, idea) else add(idea)
    }

    /**
     * Dexter saving an idea: a new note, or, when a note with that title exists, the new text is
     * added below the existing one, so nothing the user wrote is ever overwritten.
     */
    fun save(title: String, note: String, source: String, kind: IdeaKind): Idea = mutate { ideas, _ ->
        val now = System.currentTimeMillis()
        val existing = ideas.firstOrNull { key(it.title) == key(title) }
        val idea = if (existing == null) {
            Idea(UUID.randomUUID().toString(), title.trim(), note.trim(), source.trim(), kind, now)
        } else {
            val merged = when {
                note.isBlank() || existing.note.contains(note.trim()) -> existing.note
                existing.note.isBlank() -> note.trim()
                else -> existing.note.trimEnd() + "\n\n" + note.trim()
            }
            existing.copy(note = merged, source = existing.source.ifBlank { source.trim() }, updated = now)
        }
        ideas.upsert(idea)
        idea
    }

    /** A new note (from the notes screen, a study session or an unresolved [[link]]); returns the existing one if the title is taken. */
    fun create(title: String, kind: IdeaKind = IdeaKind.IDEA, note: String = ""): Idea = mutate { ideas, _ ->
        val name = title.trim().ifBlank { "Nota sin título" }
        ideas.firstOrNull { key(it.title) == key(name) }
            ?: Idea(UUID.randomUUID().toString(), name, note, "", kind, System.currentTimeMillis()).also { ideas.add(it) }
    }

    /** The user editing a note. Renaming updates [[links]] to it in every other note. */
    fun edit(id: String, title: String, note: String): Idea? = mutate { ideas, _ ->
        val i = ideas.indexOfFirst { it.id == id }
        if (i < 0) return@mutate null
        val old = ideas[i]
        val newTitle = title.trim().ifBlank { old.title }
        if (newTitle == old.title && note == old.note) return@mutate old
        val edited = old.copy(title = newTitle, note = note, updated = System.currentTimeMillis())
        ideas[i] = edited
        if (newTitle != old.title) {
            for (j in ideas.indices) {
                if (j != i && ideas[j].note.contains("[[")) ideas[j] = ideas[j].copy(note = renameLinks(ideas[j].note, old.title, newTitle))
            }
        }
        edited
    }

    /** Today's journal note ("Diario YYYY-MM-DD"), with [text] added as a timestamped line. */
    fun appendJournal(text: String, at: Date = Date()): Idea = mutate { ideas, _ ->
        val title = journalTitle(at)
        val time = SimpleDateFormat("HH:mm", Locale.US).format(at)
        val line = "- **$time** — ${text.trim().replace(Regex("\\s*\\n\\s*"), " ")}"
        val note = ideas.firstOrNull { it.kind == IdeaKind.JOURNAL && it.title == title }
            ?: Idea(UUID.randomUUID().toString(), title, "", "", IdeaKind.JOURNAL, at.time)
        val updated = note.copy(note = (note.note.trimEnd() + "\n" + line).trimStart(), updated = at.time)
        ideas.upsert(updated)
        updated
    }

    /** The journal note for [at], created (empty) when missing. */
    fun journal(at: Date = Date()): Idea = create(journalTitle(at), IdeaKind.JOURNAL)

    /** Links two notes found by title (loosely) with a label; returns them, or null if one is missing. */
    fun link(from: String, to: String, relation: String): Pair<Idea, Idea>? = mutate { ideas, labeled ->
        val a = findIn(ideas, from) ?: return@mutate null
        val b = findIn(ideas, to) ?: return@mutate null
        if (a.id == b.id) return@mutate null
        labeled.removeAll { (it.from == a.id && it.to == b.id) || (it.from == b.id && it.to == a.id) }
        labeled.add(IdeaLink(a.id, b.id, relation.trim()))
        a to b
    }

    fun delete(id: String) = mutate { ideas, labeled ->
        ideas.removeAll { it.id == id }
        labeled.removeAll { it.from == id || it.to == id }
    }

    fun find(title: String): Idea? = findIn(ideas, title)

    fun byId(id: String) = ideas.firstOrNull { it.id == id }

    fun linksOf(id: String) = links.filter { it.from == id || it.to == id }

    /** Notes whose text links to [idea] with [[its title]] (Obsidian's backlinks). */
    fun backlinks(idea: Idea): List<Idea> {
        val k = key(idea.title)
        return ideas.filter { other -> other.id != idea.id && wikiTargets(other.note).any { key(it) == k } }
    }

    /** Notes that mention [idea]'s title in plain text without linking it (unlinked mentions). */
    fun mentions(idea: Idea): List<Idea> {
        val k = key(idea.title)
        if (k.length < 4) return emptyList()
        val linked = backlinks(idea).map { it.id }.toSet()
        return ideas.filter { it.id != idea.id && it.id !in linked && key(it.note).contains(k) }
    }

    /** Full-text search over titles, notes and sources, best title matches first. */
    fun search(query: String): List<Idea> {
        val words = key(query).split(" ").filter { it.isNotEmpty() }
        val all = ideas
        if (words.isEmpty()) return all.sortedByDescending { it.updated }
        return all.mapNotNull { i ->
            val title = key(i.title)
            val body = key(i.note + " " + i.source)
            if (words.all { title.contains(it) || body.contains(it) }) i to words.count { title.contains(it) } else null
        }.sortedWith(compareByDescending<Pair<Idea, Int>> { it.second }.thenByDescending { it.first.updated }).map { it.first }
    }

    companion object {
        private val LOCK = Any()
        const val JOURNAL_PREFIX = "Diario"

        private val WIKILINK = Regex("\\[\\[([^\\]|#\\n]+)(?:[|#][^\\]\\n]*)?]]")

        fun journalTitle(at: Date = Date()) = "$JOURNAL_PREFIX " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(at)

        /** Titles linked with [[Title]] (or [[Title|alias]], [[Title#section]]) in [text]. */
        fun wikiTargets(text: String): List<String> = WIKILINK.findAll(text).map { it.groupValues[1].trim() }.toList()

        private fun renameLinks(text: String, old: String, new: String) =
            WIKILINK.replace(text) { m -> if (key(m.groupValues[1]) == key(old)) m.value.replaceFirst(m.groupValues[1], new) else m.value }

        private fun findIn(ideas: List<Idea>, title: String): Idea? {
            val k = key(title)
            if (k.isEmpty()) return null
            return ideas.firstOrNull { key(it.title) == k } ?: ideas.firstOrNull { key(it.title).contains(k) || k.contains(key(it.title)) }
        }

        fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
