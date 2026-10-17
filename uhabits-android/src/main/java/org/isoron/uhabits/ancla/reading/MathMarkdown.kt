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

import java.net.URLEncoder

/**
 * Markdown with formulas to HTML for [MathView]: the same subset as [MarkdownNote] (headings,
 * lists, checkboxes, quotes, bold, italics, code, [[links]]) plus `$inline$` and `$$display$$` math
 * and ``` code blocks.
 *
 * Every piece of the user's text is HTML-escaped. Formulas are kept out of the Markdown rules (so
 * `a_1 * b_2` stays math) and written with `\(...\)` / `\[...\]` delimiters, the only ones KaTeX's
 * auto-render is told to look for, so a price like "$5 y $10" stays plain text.
 */
object MathMarkdown {
    const val LINK_PREFIX = "https://math.ancla/link?t="

    /** $$...$$ on one line, or $...$ that doesn't start or end with a space and isn't followed by a digit (Pandoc's rule). */
    private val INLINE_MATH = Regex("\\$\\$(.+?)\\$\\$|(?<![\\\\$\\w])\\$(?=\\S)([^$\\n]*?\\S)\\$(?![\\w$])")
    private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\[\\[([^\\]|#\\n]+)(?:[|#]([^\\]\\n]*))?]]|`([^`\\n]+)`|(?<![*\\w])\\*([^*\\n]+)\\*")
    private val TASK = Regex("^\\s*[-*] \\[( |x)] ")
    private val BULLET = Regex("^\\s*[-*] ")
    private val NUMBERED = Regex("^\\s*\\d+[.)] ")

    /** Whether [text] has any formula, so the plain Compose renderer is enough otherwise. */
    fun hasMath(text: String) = text.contains("$$") || INLINE_MATH.containsMatchIn(text)

    fun escape(s: String) = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /** [exists] tells real notes from links to notes not written yet, which are shown dimmer. */
    fun toHtml(markdown: String, exists: (String) -> Boolean = { true }): String {
        val out = StringBuilder()
        val lines = markdown.lines()
        var i = 0
        var list: String? = null
        fun closeList() {
            list?.let { out.append("</$it>") }
            list = null
        }
        fun openList(tag: String) {
            if (list != tag) {
                closeList()
                out.append("<$tag>")
                list = tag
            }
        }
        while (i < lines.size) {
            val line = lines[i].trimEnd()
            val trimmed = line.trim()
            // ``` fences a code block, shown as is.
            if (trimmed.startsWith("```")) {
                closeList()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out.append("<pre><code>").append(escape(code.toString().trimEnd())).append("</code></pre>")
                i++
                continue
            }
            // $$ on its own line opens a display block that runs to the next $$.
            if (trimmed.startsWith("$$") && (trimmed == "$$" || !trimmed.drop(2).contains("$$"))) {
                closeList()
                val tex = StringBuilder(trimmed.removePrefix("$$"))
                i++
                while (i < lines.size && !lines[i].contains("$$")) {
                    tex.append('\n').append(lines[i])
                    i++
                }
                if (i < lines.size) tex.append('\n').append(lines[i].substringBefore("$$"))
                out.append("<div class=\"display\">\\[").append(escape(tex.toString().trim())).append("\\]</div>")
                i++
                continue
            }
            when {
                line.isBlank() -> {
                    closeList()
                    out.append("<div class=\"gap\"></div>")
                }
                line.startsWith("### ") -> { closeList(); out.append("<h3>").append(inline(line.drop(4), exists)).append("</h3>") }
                line.startsWith("## ") -> { closeList(); out.append("<h2>").append(inline(line.drop(3), exists)).append("</h2>") }
                line.startsWith("# ") -> { closeList(); out.append("<h1>").append(inline(line.drop(2), exists)).append("</h1>") }
                line.startsWith("> ") -> { closeList(); out.append("<blockquote>").append(inline(line.drop(2), exists)).append("</blockquote>") }
                TASK.containsMatchIn(line) -> {
                    openList("ul")
                    val done = line.contains("[x]")
                    out.append(if (done) "<li class=\"task done\">☑ " else "<li class=\"task\">☐ ").append(inline(line.substringAfter("] "), exists)).append("</li>")
                }
                BULLET.containsMatchIn(line) -> {
                    openList("ul")
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    out.append("<li style=\"margin-left:${indent * 16}px\">").append(inline(line.trimStart().drop(2), exists)).append("</li>")
                }
                NUMBERED.containsMatchIn(line) -> {
                    openList("ol")
                    out.append("<li>").append(inline(line.replaceFirst(NUMBERED, ""), exists)).append("</li>")
                }
                else -> { closeList(); out.append("<p>").append(inline(line, exists)).append("</p>") }
            }
            i++
        }
        closeList()
        return out.toString()
    }

    /** One line: formulas first (untouched by the other rules), then bold, italics, code and [[links]]. */
    fun inline(text: String, exists: (String) -> Boolean = { true }): String {
        val out = StringBuilder()
        var at = 0
        INLINE_MATH.findAll(text).forEach { m ->
            out.append(markdownInline(text.substring(at, m.range.first), exists))
            val display = m.groupValues[1].isNotEmpty()
            val tex = escape(if (display) m.groupValues[1] else m.groupValues[2])
            out.append(if (display) "\\[$tex\\]" else "\\($tex\\)")
            at = m.range.last + 1
        }
        out.append(markdownInline(text.substring(at), exists))
        return out.toString()
    }

    private fun markdownInline(text: String, exists: (String) -> Boolean): String {
        val out = StringBuilder()
        var at = 0
        INLINE.findAll(text).forEach { m ->
            out.append(escape(text.substring(at, m.range.first)))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out.append("<b>").append(markdownInline(g[1], exists)).append("</b>")
                g[2].isNotEmpty() -> {
                    val target = g[2].trim()
                    val cls = if (exists(target)) "link" else "link new"
                    out.append("<a class=\"$cls\" href=\"").append(LINK_PREFIX).append(URLEncoder.encode(target, "UTF-8").replace("+", "%20")).append("\">")
                        .append(escape(g[3].ifBlank { target })).append("</a>")
                }
                g[4].isNotEmpty() -> out.append("<code>").append(escape(g[4])).append("</code>")
                g[5].isNotEmpty() -> out.append("<i>").append(escape(g[5])).append("</i>")
            }
            at = m.range.last + 1
        }
        out.append(escape(text.substring(at)))
        return out.toString()
    }
}
