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

import java.time.LocalDateTime

/** A reminder understood on the phone: what, and when. */
data class SpokenReminder(val title: String, val at: LocalDateTime)

/**
 * "Recuérdame tomar agua en dos minutos", "recuérdame llamar a mamá a las nueve y media de la
 * noche": reminders understood on the phone itself, so they work with no internet and with the AI
 * out of quota. The offline transcript spells numbers out ("dos", "nueve y media"), so they are
 * read here too. Anything it isn't sure about goes to the AI as before.
 */
object OfflineReminder {
    private val TRIGGERS = listOf(
        "recuerdame", "recordarme", "recuerdeme", "acuerdame", "avisame", "pon un recordatorio", "ponme un recordatorio",
        "crea un recordatorio", "hazme acuerdo", "hazme acordar"
    )

    private val UNITS = mapOf(
        "cero" to 0, "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14,
        "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
        "veintiun" to 21, "veintiuno" to 21, "veintiuna" to 21, "veintidos" to 22, "veintitres" to 23, "veinticuatro" to 24,
        "veinticinco" to 25, "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29
    )
    private val TENS = mapOf("treinta" to 30, "cuarenta" to 40, "cincuenta" to 50)

    /** A number at [i] ("2", "dos", "treinta y cinco"): its value and how many words it took. */
    fun number(words: List<String>, i: Int): Pair<Int, Int>? {
        val w = words.getOrNull(i) ?: return null
        w.toIntOrNull()?.let { return it to 1 }
        UNITS[w]?.let { return it to 1 }
        val tens = TENS[w] ?: return null
        if (words.getOrNull(i + 1) == "y") UNITS[words.getOrNull(i + 2)]?.takeIf { it in 1..9 }?.let { return tens + it to 3 }
        return tens to 1
    }

    fun parse(transcript: String, now: LocalDateTime = LocalDateTime.now()): SpokenReminder? {
        // Understood without accents; the title keeps the user's words as they said them ("mamá").
        val raw = transcript.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val key = raw.map { QuickCommands.normalize(it) }
        val start = TRIGGERS.map { it.split(' ') }.firstNotNullOfOrNull { t ->
            (0..key.size - t.size).firstOrNull { k -> key.subList(k, k + t.size) == t }?.let { it + t.size }
        } ?: return null
        val words = key.drop(start)
        val said = raw.drop(start)
        val (at, from, to) = whenSaid(words, now) ?: return null
        val kept = (from until to).toSet()
        val title = said.indices.filter { it !in kept }.map { said[it] to words[it] }
            .dropWhile { it.second in LEAD }.dropLastWhile { it.second in LEAD }
            .joinToString(" ") { it.first }.trim()
        if (title.isEmpty() || title.split(' ').size > 12) return null
        return SpokenReminder(title.replaceFirstChar { it.uppercase() }, at)
    }

    /** Words around the time phrase that aren't part of what to remember. */
    private val LEAD = setOf("que", "de", "para", "a", "por", "favor", "porfa", "y", "el", "la", "hoy")

    /** When the reminder is for, and which words said it ([from] until [to]). */
    private fun whenSaid(words: List<String>, now: LocalDateTime): Triple<LocalDateTime, Int, Int>? {
        for (i in words.indices) {
            // "en dos minutos", "en una hora", "dentro de media hora"
            val inAt = when {
                words[i] == "en" -> i + 1
                words[i] == "dentro" && words.getOrNull(i + 1) == "de" -> i + 2
                else -> -1
            }
            if (inAt >= 0) {
                if (words.getOrNull(inAt) == "media" && words.getOrNull(inAt + 1) == "hora") return Triple(now.plusMinutes(30), i, inAt + 2)
                val (n, used) = number(words, inAt) ?: continue
                val unit = words.getOrNull(inAt + used) ?: continue
                val at = when {
                    unit.startsWith("minuto") -> now.plusMinutes(n.toLong())
                    unit.startsWith("hora") -> now.plusHours(n.toLong())
                    else -> continue
                }
                return Triple(at, i, inAt + used + 1)
            }
            // "a las nueve", "a las 9 y media de la noche", "a la una menos cuarto"
            if (words[i] == "a" && words.getOrNull(i + 1) in setOf("las", "la")) {
                val (h, used) = number(words, i + 2) ?: continue
                if (h !in 0..23) continue
                var j = i + 2 + used
                var minute = 0
                var hour = h
                when {
                    words.getOrNull(j) == "y" && words.getOrNull(j + 1) == "media" -> { minute = 30; j += 2 }
                    words.getOrNull(j) == "y" && words.getOrNull(j + 1) == "cuarto" -> { minute = 15; j += 2 }
                    words.getOrNull(j) == "menos" && words.getOrNull(j + 1) == "cuarto" -> { minute = 45; hour -= 1; j += 2 }
                    words.getOrNull(j) == "y" -> number(words, j + 1)?.takeIf { it.first in 1..59 }?.let { (m, u) -> minute = m; j += 1 + u }
                    else -> number(words, j)?.takeIf { it.first in 1..59 }?.let { (m, u) -> minute = m; j += u } // "9 30"
                }
                val part = if (words.getOrNull(j) == "de" && words.getOrNull(j + 1) == "la") words.getOrNull(j + 2) else null
                val afternoon = part in setOf("tarde", "noche")
                val morning = part in setOf("manana", "madrugada")
                if (part != null && (afternoon || morning)) j += 3
                if (afternoon && hour in 1..11) hour += 12
                if (hour < 0) hour += 24
                var at = now.toLocalDate().atTime(hour % 24, minute)
                // "A las 9" said at 3 pm without "de la mañana" means tonight.
                if (!morning && !afternoon && hour in 0..11 && !at.isAfter(now) && at.plusHours(12).isAfter(now)) at = at.plusHours(12)
                if (!at.isAfter(now)) at = at.plusDays(1)
                return Triple(at, i, j)
            }
        }
        return null
    }
}
