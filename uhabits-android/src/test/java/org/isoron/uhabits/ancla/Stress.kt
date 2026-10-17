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

import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/**
 * Shared pieces for the stress tests. Every test is deterministic (fixed seeds) so a failure can be
 * replayed; set ANCLA_STRESS=10 (or more) to multiply the iterations for a long soak run.
 */
object Stress {
    val factor: Int = (System.getenv("ANCLA_STRESS") ?: System.getProperty("ancla.stress"))?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun iterations(base: Int) = base * factor

    fun tempDir(): File = Files.createTempDirectory("ancla-test").toFile().apply { deleteOnExit() }

    /**
     * Random text with the characters most likely to break parsers, escaping and encodings. (No lone
     * surrogates: they aren't valid Unicode, no keyboard produces them and UTF-8 can't store them.)
     */
    fun nastyText(rnd: Random, max: Int = 80): String {
        val pieces = listOf(
            "<", ">", "&", "\"", "'", "$", "$$", "\\", "*", "**", "_", "`", "[[", "]]", "|", "#", "\n", "\r\n", "\t", " ",
            "<script>", "</div>", "javascript:", "onerror=", "{", "}", "^", "%", "%1\$s", "\u0000", " ", "‮",
            "😀", "🦾", "ñ", "á", "Ü", "汉字", "a", "b", "x", "1", "0", "-", ".", ",", "(", ")", "\\frac{1}{2}", "\\theta"
        )
        return buildString { repeat(rnd.nextInt(max + 1)) { append(pieces[rnd.nextInt(pieces.size)]) } }
    }
}
