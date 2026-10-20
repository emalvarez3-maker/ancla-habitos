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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Everyday commands that run offline, and everything else that must still reach the AI. */
class QuickCommandsTest {
    private fun torch(on: Boolean) = QuickCommand("phone_toggle", mapOf("what" to "flashlight", "on" to on))

    @Test
    fun flashlight() {
        for (s in listOf("prende la linterna", "Oye Dexter, prende la linterna", "enciende el flash por favor", "pon la linterna", "¡Préndeme la linterna!")) {
            assertEquals(s, torch(true), QuickCommands.match(s))
        }
        for (s in listOf("apaga la linterna", "hey dexter apaga la linterna ya", "quita el flash")) {
            assertEquals(s, torch(false), QuickCommands.match(s))
        }
    }

    @Test
    fun volume() {
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "up")), QuickCommands.match("sube el volumen"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "down")), QuickCommands.match("Dexter, bájale al volumen"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "set", "percent" to 50)), QuickCommands.match("pon el volumen al 50"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "set", "percent" to 80)), QuickCommands.match("volumen al 80 por ciento"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "max")), QuickCommands.match("volumen al máximo"))
    }

    @Test
    fun media() {
        assertEquals(QuickCommand("phone_media", mapOf("action" to "pause")), QuickCommands.match("pausa la música"))
        assertEquals(QuickCommand("phone_media", mapOf("action" to "next")), QuickCommands.match("siguiente canción"))
        assertEquals(QuickCommand("phone_media", mapOf("action" to "play")), QuickCommands.match("oye dexter reproduce la música"))
        assertEquals(QuickCommand("phone_media", mapOf("action" to "previous")), QuickCommands.match("canción anterior"))
    }

    @Test
    fun theWayPeopleReallySayIt() {
        for (s in listOf("prende la luz del celular", "Dexter, enciéndeme la linterna porfa", "ey dexter pon el flash", "activa la linterna ya")) {
            assertEquals(s, torch(true), QuickCommands.match(s))
        }
        for (s in listOf("apágame la linterna", "Apaga la luz del teléfono, por favor", "desactiva el flash")) {
            assertEquals(s, torch(false), QuickCommands.match(s))
        }
        val up = QuickCommand("phone_volume", mapOf("action" to "up"))
        val down = QuickCommand("phone_volume", mapOf("action" to "down"))
        for (s in listOf("súbele al volumen", "Súbele un poco al volumen", "más volumen", "sube el sonido")) assertEquals(s, up, QuickCommands.match(s))
        for (s in listOf("bájale un poco al volumen", "menos volumen", "baja el sonido porfa")) assertEquals(s, down, QuickCommands.match(s))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "set", "percent" to 30)), QuickCommands.match("pon el volumen al 30%"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "set", "percent" to 20)), QuickCommands.match("volumen a 20"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "set", "percent" to 0)), QuickCommands.match("deja el volumen en 0"))
        assertEquals(QuickCommand("phone_volume", mapOf("action" to "mute")), QuickCommands.match("quita el sonido"))
        val pause = QuickCommand("phone_media", mapOf("action" to "pause"))
        for (s in listOf("pon pausa", "ponle pausa a la música", "pausa", "para la música", "detén el video", "Dexter, pausa el podcast")) {
            assertEquals(s, pause, QuickCommands.match(s))
        }
        for (s in listOf("dale play", "dale play a la música", "continúa la música", "reanuda el podcast")) {
            assertEquals(s, QuickCommand("phone_media", mapOf("action" to "play")), QuickCommands.match(s))
        }
        for (s in listOf("pasa la canción", "pasa a la siguiente", "pon la siguiente", "salta la canción", "otra canción", "la siguiente canción")) {
            assertEquals(s, QuickCommand("phone_media", mapOf("action" to "next")), QuickCommands.match(s))
        }
    }

    @Test
    fun negationsQuestionsAndLaterGoToTheAi() {
        for (s in listOf(
            "no prendas la linterna",
            "no apagues la linterna",
            "no pauses la música",
            "¿por qué está prendida la linterna?",
            "prende la linterna en 5 minutos",
            "apaga la linterna cuando llegue a casa",
            "linterna",
            "pasa",
            "pon",
            "dale",
            "pon música",
            "sube el brillo",
            "silencia el teléfono",
            "volumen",
            "volumen al 101",
            "sube el volumen y pon música",
            "cuál es el volumen"
        )) {
            assertNull(s, QuickCommands.match(s))
        }
    }

    @Test
    fun anythingElseGoesToTheAi() {
        for (s in listOf(
            "prende la linterna y llama a mamá",
            "¿está prendida la linterna?",
            "pon música para dormir",
            "silencio",
            "atrás",
            "baja el brillo",
            "sube",
            "oye dexter",
            "",
            "recuérdame apagar la linterna a las 9",
            "volumen al 150",
            "pon una canción de Bad Bunny"
        )) {
            assertNull(s, QuickCommands.match(s))
        }
    }
}
