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

/** A phone action understood on the phone itself: the phone tool to run and its arguments. */
data class QuickCommand(val tool: String, val args: Map<String, Any>)

/**
 * The everyday requests that need no AI: "prende la linterna", "sube el volumen", "pausa la
 * música". Recognized offline from the transcript, they run in a fraction of a second instead of
 * a round trip to the model. Only harmless, undoable actions, and only when the whole request is
 * that command (a polite word or Dexter's name around it is fine): anything more ("prende la
 * linterna y llama a mamá", a question) goes to the AI as before.
 */
object QuickCommands {
    private val FILLER = setOf(
        "oye", "hey", "ey", "dexter", "por", "favor", "porfa", "porfavor", "please", "puedes", "podrias", "me",
        "el", "la", "los", "las", "lo", "del", "de", "mi", "un", "una", "poco", "ya", "ahora", "rapido", "y", "a",
        "telefono", "celular", "cel", "plis"
    )

    private val ON = setOf("prende", "prender", "prendeme", "enciende", "encender", "enciendeme", "activa", "activar", "pon", "ponme", "abre")
    private val OFF = setOf("apaga", "apagar", "apagame", "desactiva", "desactivar", "quita", "quitame", "cierra")
    private val TORCH = setOf("linterna", "flash", "lampara", "luz")

    fun match(transcript: String): QuickCommand? {
        val words = normalize(transcript).split(' ').filter { it.isNotBlank() && it !in FILLER }
        if (words.isEmpty() || words.size > 4) return null
        val set = words.toSet()
        fun only(vararg allowed: Set<String>) = words.all { w -> allowed.any { w in it } }

        // Flashlight
        if (set.any { it in TORCH }) {
            if (set.any { it in ON } && only(ON, TORCH)) return toggle("flashlight", true)
            if (set.any { it in OFF } && only(OFF, TORCH)) return toggle("flashlight", false)
            return null
        }

        // Volume
        if ("volumen" in set || "sonido" in set) {
            val number = words.firstNotNullOfOrNull { it.toIntOrNull() }
            val rest = words.filter { it != "volumen" && it != "sonido" && it.toIntOrNull() == null && it != "al" && it != "en" && it != "por" && it != "ciento" && it != "porciento" }
            return when {
                number != null && number in 0..100 && rest.all { it in setOf("pon", "ponle", "deja", "dejalo", "sube", "subelo", "baja", "bajalo") } -> volume("set", number)
                rest.size == 1 && rest[0] in setOf("sube", "subele", "subir", "aumenta", "mas") -> volume("up")
                rest.size == 1 && rest[0] in setOf("baja", "bajale", "bajar", "disminuye", "menos") -> volume("down")
                rest.size == 1 && rest[0] in setOf("maximo", "max", "todo") -> volume("max")
                rest.size == 1 && rest[0] in setOf("silencia", "silencio", "mutea", "quita") -> volume("mute")
                else -> null
            }
        }
        if (words.size == 1 && words[0] in setOf("silencio", "silencia", "mutea")) return null // could mean Dexter's quiet mode: ask the AI

        // Music and videos
        val music = setOf("musica", "cancion", "video", "podcast", "audio", "tema")
        val rest = words.filter { it !in music }
        if (rest.size == 1) {
            when (rest[0]) {
                "pausa", "pausar", "pausala", "deten", "para" -> return media("pause")
                "reproduce", "reproducir", "continua", "sigue", "reanuda", "play" -> return media("play")
                "siguiente", "salta", "adelanta", "otra" -> return media("next")
                // Not "atrás" or "regresa": on their own they may mean the phone's back button.
                "anterior" -> return media("previous")
            }
        }
        when (rest) {
            listOf("pon", "pausa"), listOf("ponle", "pausa"), listOf("dale", "pausa") -> return media("pause")
            listOf("dale", "play"), listOf("ponle", "play") -> return media("play")
            listOf("pasa", "siguiente"), listOf("pon", "siguiente"), listOf("ponme", "siguiente") -> return media("next")
        }
        // "Pasa la canción", but not "pasa" alone (it may mean something else entirely).
        if (rest == listOf("pasa") && words.size > 1) return media("next")
        if (words == listOf("siguiente", "cancion") || words == listOf("cancion", "siguiente")) return media("next")
        return null
    }

    private fun toggle(what: String, on: Boolean) = QuickCommand("phone_toggle", mapOf("what" to what, "on" to on))

    private fun volume(action: String, percent: Int? = null) =
        QuickCommand("phone_volume", buildMap { put("action", action); percent?.let { put("percent", it) } })

    private fun media(action: String) = QuickCommand("phone_media", mapOf("action" to action))

    /** Lowercase, no accents or punctuation, numbers kept: "¡Súbele al 80%!" → "subele al 80". */
    fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9ñ ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
