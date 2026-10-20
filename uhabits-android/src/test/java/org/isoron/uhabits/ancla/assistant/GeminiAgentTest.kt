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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a turn ends on the tools' own words, and what the history keeps of a voice message. */
class GeminiAgentTest {
    private fun quick(text: String = "", failed: Boolean = false, tools: List<String>, results: List<String>) =
        GeminiAgent.quickReply(text, failed, tools, results)

    @Test
    fun aSimpleActionThatWorkedIsItsOwnAnswer() {
        assertEquals("Linterna encendida.", quick(tools = listOf("phone_toggle"), results = listOf("Linterna encendida")))
        assertEquals("Linterna encendida.", quick(tools = listOf("phone_toggle"), results = listOf("  Linterna encendida.  ")))
        assertEquals(
            "Volumen al 80 %. Música en pausa.",
            quick(tools = listOf("phone_volume", "phone_media"), results = listOf("Volumen al 80 %.", "Música en pausa"))
        )
        assertEquals("Gasto de $12.50 en Comida.", quick(tools = listOf("add_movement"), results = listOf("Gasto de $12.50 en Comida.")))
    }

    @Test
    fun anythingElseGoesBackToTheModel() {
        // The model already spoke: its words are the answer, not a second one from the tools.
        assertNull(quick(text = "Listo, la prendí.", tools = listOf("phone_toggle"), results = listOf("Linterna encendida")))
        // A tool failed: the model explains it or tries another way.
        assertNull(quick(failed = true, tools = listOf("phone_toggle"), results = listOf("Sin permiso para la linterna")))
        // One slow tool among quick ones: the model has to read its result.
        assertNull(quick(tools = listOf("phone_toggle", "read_file"), results = listOf("Linterna encendida", "…")))
        assertNull(quick(tools = listOf("send_whatsapp"), results = listOf("Mensaje listo para enviar")))
        assertNull(quick(tools = listOf("today_summary"), results = listOf("Hoy: 3 hábitos")))
        assertNull(quick(tools = emptyList(), results = emptyList()))
    }

    @Test
    fun aVoiceMessageLeavesTheHistoryOnceHeard() {
        val audio = JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", "A".repeat(500_000)))
        val turn = JSONObject().put("role", "user").put(
            "parts",
            JSONArray().put(JSONObject().put("text", "[historial]")).put(audio).put(JSONObject().put("text", PhoneSafety.AUDIO_TURN))
        )
        GeminiAgent.withoutAudio(turn)
        assertFalse(turn.toString().contains("inlineData"))
        assertTrue("half a megabyte less in every later request", turn.toString().length < 2_000)
        val parts = turn.getJSONArray("parts")
        assertEquals(3, parts.length())
        assertEquals("[historial]", parts.getJSONObject(0).getString("text"))
        assertEquals("(mensaje de voz)", parts.getJSONObject(1).getString("text"))
        assertEquals(PhoneSafety.AUDIO_TURN, parts.getJSONObject(2).getString("text"))

        // When the transcription arrives, the placeholder gets its words.
        GeminiAgent.withWords(turn, " pon una alarma a las seis ")
        assertEquals("(mensaje de voz: «pon una alarma a las seis»)", parts.getJSONObject(1).getString("text"))
        assertEquals("the rest is untouched", PhoneSafety.AUDIO_TURN, parts.getJSONObject(2).getString("text"))
    }

    @Test
    fun aTextTurnIsLeftAsItIs() {
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "prende la linterna")))
        val before = turn.toString()
        GeminiAgent.withoutAudio(turn)
        GeminiAgent.withWords(turn, "otra cosa")
        assertEquals(before, turn.toString())
        GeminiAgent.withoutAudio(JSONObject()) // no parts at all
    }
}
