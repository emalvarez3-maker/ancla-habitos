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

package org.isoron.uhabits.ancla.assistant

import java.text.Normalizer

/** Which of the user's tasks or habits a spoken name means ("la de comprar pan" → "Comprar pan"). */
object NameMatch {
    sealed class Result<out T> {
        data class Found<T>(val item: T) : Result<T>()
        data class Several<T>(val items: List<T>) : Result<T>()
        object None : Result<Nothing>()
    }

    private val FILLER = setOf("el", "la", "los", "las", "de", "del", "mi", "mis", "un", "una", "tarea", "habito", "que", "lo", "a", "al", "para", "y")

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() && it !in FILLER }.toSet()

    /**
     * The exact name first; then a name containing what was said (or the other way round); then
     * the one sharing the most words. More than one equally good is [Result.Several], so Dexter
     * asks instead of guessing which one to delete.
     */
    fun <T> pick(items: List<T>, wanted: String, name: (T) -> String): Result<T> {
        val w = norm(wanted)
        if (w.isEmpty() || items.isEmpty()) return Result.None
        items.filter { norm(name(it)) == w }.let { if (it.size == 1) return Result.Found(it[0]) else if (it.size > 1) return Result.Several(it) }
        items.filter { norm(name(it)).let { n -> n.contains(w) || w.contains(n) && n.isNotEmpty() } }
            .let { if (it.size == 1) return Result.Found(it[0]) else if (it.size > 1) return Result.Several(it) }
        val ww = words(wanted)
        if (ww.isEmpty()) return Result.None
        val scored = items.map { it to (words(name(it)) intersect ww).size }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return Result.None
        val top = scored.filter { it.second == best }.map { it.first }
        return if (top.size == 1) Result.Found(top[0]) else Result.Several(top)
    }
}
