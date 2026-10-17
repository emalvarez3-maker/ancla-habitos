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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathMarkdownTest {
    @Test
    fun detectsFormulasButNotPrices() {
        assertTrue(MathMarkdown.hasMath("La energía es \$E = mc^2\$."))
        assertTrue(MathMarkdown.hasMath("\$\$\n\\theta_1\n\$\$"))
        assertFalse(MathMarkdown.hasMath("Cuesta \$5 y luego \$10."))
        assertFalse(MathMarkdown.hasMath("Sin fórmulas [[Robótica]]"))
    }

    @Test
    fun formulasAreKeptOutOfMarkdownAndEscaped() {
        val html = MathMarkdown.inline("Si \$a_1 * b_2 < c\$ entonces **sí**")
        assertEquals("Si \\(a_1 * b_2 &lt; c\\) entonces <b>sí</b>", html)
    }

    @Test
    fun displayBlocksAndLinks() {
        val html = MathMarkdown.toHtml("# Título\n\$\$\nx = 1\n\$\$\nVer [[Robótica|el tema]] y [[Nueva nota]]") { it == "Robótica" }
        assertTrue(html.contains("<h1>Título</h1>"))
        assertTrue(html.contains("<div class=\"display\">\\[x = 1\\]</div>"))
        assertTrue(html.contains("<a class=\"link\" href=\"https://math.ancla/link?t=Rob%C3%B3tica\">el tema</a>"))
        assertTrue(html.contains("<a class=\"link new\" href=\"https://math.ancla/link?t=Nueva%20nota\">Nueva nota</a>"))
    }

    @Test
    fun userTextCannotInjectHtml() {
        val html = MathMarkdown.toHtml("<script>alert(1)</script> \$<img src=x>\$")
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun listsAndTasks() {
        val html = MathMarkdown.toHtml("- uno\n- [x] hecho\n1. primero")
        assertEquals("<ul><li style=\"margin-left:0px\">uno</li><li class=\"task done\">☑ hecho</li></ul><ol><li>primero</li></ol>", html)
    }
}
