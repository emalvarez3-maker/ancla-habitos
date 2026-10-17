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
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the idea map, book notes and Dexter's memory as Markdown into an "Ancla" folder of the
 * user's Obsidian vault, chosen once with Android's folder picker (no broad storage permission).
 * Ideas link to each other and to their books with [[wikilinks]], so Obsidian's graph view shows
 * the same network. Only files inside the app's own subfolders are created, rewritten or removed.
 */
object ObsidianExport {
    private const val PREFS = "ancla_obsidian"
    private const val KEY_TREE = "tree"
    private const val ROOT = "Ancla"
    private const val MIME = "application/octet-stream" // keeps our ".md" names untouched

    fun folder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE, null)?.let(Uri::parse)

    /** Remembers the vault folder the user picked and keeps access to it across restarts. */
    fun setFolder(context: Context, tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply()
    }

    fun isConfigured(context: Context) = folder(context) != null

    /** File-name-safe title: Obsidian links use the file name, so keep it readable. */
    fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|#^\\[\\]\\n\\r\\t]"), " ")
        .replace(Regex("\\s+"), " ").trim().take(90).ifBlank { "Sin título" }

    private fun yaml(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Blocking; call off the main thread. Returns the number of notes written. */
    @Synchronized
    fun export(context: Context, books: List<Book>, graph: IdeaGraph, memory: List<String>): Int {
        val tree = folder(context) ?: return 0
        val vault = DocumentFile.fromTreeUri(context, tree) ?: return 0
        if (!vault.canWrite()) return 0
        val root = child(vault, ROOT) ?: return 0
        val ideasDir = child(root, "Ideas") ?: return 0
        val booksDir = child(root, "Libros") ?: return 0

        val byId = graph.ideas.associateBy { it.id }
        val ideaNames = graph.ideas.associate { it.id to safeName(it.title) }
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var written = 0

        // Ideas
        val keepIdeas = mutableSetOf<String>()
        graph.ideas.forEach { idea ->
            val name = ideaNames.getValue(idea.id)
            keepIdeas += "$name.md"
            val body = buildString {
                appendLine("---")
                appendLine("tipo: ${yaml(kindLabel(idea.kind))}")
                if (idea.source.isNotBlank()) appendLine("fuente: ${yaml(idea.source)}")
                appendLine("creado: ${date.format(Date(idea.created))}")
                appendLine("tags: [ancla]")
                appendLine("---")
                appendLine()
                if (idea.kind == IdeaKind.QUOTE) appendLine("> ${idea.note.replace("\n", "\n> ")}") else appendLine(idea.note)
                if (idea.source.isNotBlank()) {
                    appendLine()
                    appendLine("Fuente: [[${safeName(idea.source)}]]")
                }
                val links = graph.linksOf(idea.id)
                if (links.isNotEmpty()) {
                    appendLine()
                    appendLine("## Conexiones")
                    links.forEach { l ->
                        val otherId = if (l.from == idea.id) l.to else l.from
                        val other = ideaNames[otherId] ?: return@forEach
                        val relation = l.relation.ifBlank { "se relaciona con" }
                        appendLine(if (l.from == idea.id) "- $relation → [[$other]]" else "- [[$other]] → $relation → esta idea")
                    }
                }
            }
            if (write(context, ideasDir, "$name.md", body)) written++
        }
        prune(ideasDir, keepIdeas)

        // Books, each listing the ideas that came from it
        val keepBooks = mutableSetOf<String>()
        books.forEach { book ->
            val name = safeName(book.title)
            keepBooks += "$name.md"
            val fromBook = graph.ideas.filter { IdeaGraph.key(it.source) == IdeaGraph.key(book.title) }
            val body = buildString {
                appendLine("---")
                appendLine("tipo: \"libro\"")
                if (book.author.isNotBlank()) appendLine("autor: ${yaml(book.author)}")
                appendLine("estado: ${yaml(statusLabel(book.status))}")
                appendLine("progreso: ${(book.progress * 100).toInt()}")
                if (book.rating > 0) appendLine("calificacion: ${book.rating}")
                appendLine("tags: [ancla, libro]")
                appendLine("---")
                appendLine()
                appendLine("# ${book.title}")
                if (book.author.isNotBlank()) appendLine("*${book.author}*")
                if (fromBook.isNotEmpty()) {
                    appendLine()
                    appendLine("## Ideas y resúmenes")
                    fromBook.forEach { appendLine("- [[${ideaNames.getValue(it.id)}]] (${kindLabel(it.kind).lowercase()})") }
                }
            }
            if (write(context, booksDir, "$name.md", body)) written++
        }
        prune(booksDir, keepBooks)

        // What Dexter remembers
        if (memory.isNotEmpty()) {
            val body = "---\ntags: [ancla]\n---\n\n# Lo que Dexter sabe de mí\n\n" + memory.joinToString("\n") { "- $it" } + "\n"
            if (write(context, root, "Memoria de Dexter.md", body)) written++
        }
        Log.d("AnclaObsidian", "exported $written notes (${byId.size} ideas, ${books.size} books)")
        return written
    }

    private fun kindLabel(k: IdeaKind) = when (k) {
        IdeaKind.IDEA -> "Idea"
        IdeaKind.SUMMARY -> "Resumen"
        IdeaKind.QUOTE -> "Cita"
    }

    private fun statusLabel(s: BookStatus) = when (s) {
        BookStatus.WANT -> "quiero leer"
        BookStatus.READING -> "leyendo"
        BookStatus.FINISHED -> "leído"
    }

    private fun child(parent: DocumentFile, name: String): DocumentFile? =
        parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)

    /** Writes [text] to [name] in [dir] only when it changed, so Obsidian and sync tools see no noise. */
    private fun write(context: Context, dir: DocumentFile, name: String, text: String): Boolean {
        val existing = dir.findFile(name)
        if (existing != null) {
            val old = runCatching { context.contentResolver.openInputStream(existing.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (old == text) return false
        }
        val file = existing ?: dir.createFile(MIME, name) ?: return false
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(text.toByteArray()) } ?: return false
        return true
    }

    /** Removes notes we exported earlier for ideas or books that no longer exist (only .md in our folders). */
    private fun prune(dir: DocumentFile, keep: Set<String>) {
        dir.listFiles().filter { it.isFile && it.name?.endsWith(".md") == true && it.name !in keep }.forEach { it.delete() }
    }
}
