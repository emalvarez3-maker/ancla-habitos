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

import org.isoron.uhabits.ancla.reading.MathMarkdown
import org.isoron.uhabits.ancla.robotics.RoboticsCourse
import org.isoron.uhabits.ancla.robotics.Simulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The HTML that reaches the WebView, the course files and the app's texts. */
class ContentTest {
    private val allowedTags = setOf("p", "b", "i", "a", "code", "pre", "ul", "ol", "li", "h1", "h2", "h3", "blockquote", "div")
    private val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")

    @Test
    fun anyNoteBecomesSafeWellFormedHtml() {
        val rnd = Random(40)
        repeat(Stress.iterations(20_000)) {
            val md = buildString {
                repeat(rnd.nextInt(1, 8)) {
                    append(listOf("", "# ", "## ", "### ", "> ", "- ", "  - ", "- [x] ", "- [ ] ", "1. ", "$$", "```")[rnd.nextInt(12)])
                    append(Stress.nastyText(rnd, 25))
                    append('\n')
                }
            }
            val html = MathMarkdown.toHtml(md) { rnd.nextBoolean() }
            val stack = ArrayDeque<String>()
            tag.findAll(html).forEach { m ->
                val name = m.groupValues[2]
                assertTrue("unexpected <$name> from: $md", name in allowedTags)
                val attrs = m.groupValues[3]
                val allowedAttr = Regex("(\\s+(class=\"[a-z ]+\"|style=\"margin-left:\\d+px\"|href=\"https://math\\.ancla/link\\?t=[A-Za-z0-9%._*-]*\"))*")
                assertTrue("attributes: $attrs", allowedAttr.matches(attrs))
                if (m.groupValues[1].isEmpty()) stack.addLast(name) else assertEquals("unbalanced in: $md", name, stack.removeLastOrNull())
            }
            assertTrue("unclosed $stack in: $md", stack.isEmpty())
            // Text outside tags never carries a raw < or > (everything is escaped).
            assertFalse(html.replace(tag, "").contains('<'))
            assertFalse(html.replace(tag, "").contains('>'))
            assertFalse(html.contains("javascript:", ignoreCase = true) && html.contains("href=\"javascript"))
        }
    }

    @Test
    fun theMathPageCanLoadEveryKatexFileAndNothingElse() {
        val katex = listOf("src/main/assets/katex", "uhabits-android/src/main/assets/katex").map(::File).first { it.isDirectory }
        val files = katex.walkTopDown().filter { it.isFile }.map { "/katex/" + it.relativeTo(katex).invariantSeparatorsPath }.toList()
        assertTrue(files.size > 20)
        files.forEach { assertTrue("must load $it", MathMarkdown.isServableAsset(it)) }
        listOf(
            "/katex/../databases/uhabits.db", "/katex/..%2F..%2Fshared_prefs.js", "/katex/./katex.min.js", "/katex//katex.min.js",
            "/files/ideas.json", "/katex/katex.min.js?x=1", "/katex/fonts/x.ttf", "/KATEX/katex.min.js", "/katex/katex.min.js/..", "/katex/.js"
        ).forEach { assertFalse("must refuse $it", MathMarkdown.isServableAsset(it)) }
    }

    private fun courseDir(): File = listOf("src/main/assets/robotics", "uhabits-android/src/main/assets/robotics").map(::File).first { it.isDirectory }

    @Test
    fun everyLessonIsComplete() {
        val files = courseDir().listFiles { f -> f.name.matches(Regex("\\d+\\.md")) }!!.sortedBy { it.name }
        assertTrue(files.size >= 13)
        val lessons = files.map { RoboticsCourse.parse(it.name.removeSuffix(".md").toInt(), it.readText()) }
        assertEquals((1..files.size).toList(), lessons.map { it.number })
        lessons.forEach { l ->
            val where = "lesson ${l.number}"
            assertTrue(where, l.title.isNotBlank() && l.module.isNotBlank() && l.summary.isNotBlank())
            assertNotNull("$where simulator", l.simulator)
            assertTrue("$where body", l.body.length > 1200)
            assertTrue("$where exercises", l.exercises.size >= 4)
            l.exercises.forEach { e -> assertTrue("$where solution", e.question.isNotBlank() && e.solution.length > 20) }
            // Every $ belongs to a formula: none left dangling to show up as a stray dollar.
            (l.body.lines() + l.exercises.flatMap { (it.question + "\n" + it.solution).lines() }).filter { !it.trimStart().startsWith("$$") }.forEach { line ->
                val rest = line.replace(Regex("`[^`]*`"), "").replace(Regex("\\$\\$(.+?)\\$\\$|(?<![\\\\$\\w])\\$(?=\\S)([^$\\n]*?\\S)\\$(?![\\w$])"), "")
                assertFalse("$where stray $ in: $line", rest.contains('$'))
            }
            val note = RoboticsCourse.noteBody(l, lessons.size)
            assertTrue(note.contains("[[Robótica]]"))
            assertTrue(MathMarkdown.hasMath(note))
        }
        assertEquals("every simulator is used by a lesson", Simulator.entries.toSet(), lessons.mapNotNull { it.simulator }.toSet())
        assertTrue("modules in textbook order", lessons.map { it.module }.distinct().size >= 5)
    }

    private fun res(lang: String): Map<String, String> {
        val f = listOf("src/main/res/$lang/strings_ancla.xml", "uhabits-android/src/main/res/$lang/strings_ancla.xml").map(::File).first { it.isFile }
        return Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun englishAndSpanishTextsMatch() {
        val en = res("values")
        val es = res("values-es")
        assertEquals("keys missing in values-es", emptySet<String>(), en.keys - es.keys)
        assertEquals("keys missing in values", emptySet<String>(), es.keys - en.keys)
        val arg = Regex("%(\\d+)\\$[sd]")
        en.forEach { (k, v) ->
            assertEquals("format arguments of $k", arg.findAll(v).map { it.value }.toSortedSet(), arg.findAll(es.getValue(k)).map { it.value }.toSortedSet())
            for (text in listOf(v, es.getValue(k))) {
                assertFalse("unescaped apostrophe in $k", Regex("(?<!\\\\)'").containsMatchIn(text))
                assertFalse("raw & in $k", Regex("&(?!amp;|lt;|gt;|quot;|#)").containsMatchIn(text))
            }
        }
    }
}
