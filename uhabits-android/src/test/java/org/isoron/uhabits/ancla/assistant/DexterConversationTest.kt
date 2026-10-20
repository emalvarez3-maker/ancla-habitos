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

import org.isoron.uhabits.ancla.EncryptedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

/** One conversation shared by the app and "Oye Dexter", kept across restarts. */
class DexterConversationTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun log() = DexterConversation(File(Files.createTempDirectory("conv").toFile(), "c.json"))

    /** A turn of an agent through [sync]: what reaches the model, and the reply it gives. */
    private fun turn(sync: ConversationSync, log: DexterConversation?, said: String, reply: String): String? {
        var seen: String? = "unset"
        sync.turn(log, said, {}) { context, events ->
            seen = context
            events(AgentEvent.Reply(reply))
        }
        return seen
    }

    @Test
    fun aNewAgentPicksUpTheEarlierConversation() {
        val log = log()
        val app = ConversationSync()
        assertNull("nothing before the first message", turn(app, log, "me llamo Esteban", "Anotado."))
        // The app was closed; later, "Oye Dexter" starts a fresh agent on the same log.
        val context = turn(ConversationSync(), log, "¿cómo me llamo?", "Esteban.")
        assertNotNull(context)
        assertTrue(context!!.contains("Usuario: me llamo Esteban"))
        assertTrue(context.contains("Dexter: Anotado."))
    }

    @Test
    fun anAgentOnlyGetsWhatItHasNotSeen() {
        val log = log()
        val app = ConversationSync()
        val service = ConversationSync()
        turn(app, log, "uno", "a")
        turn(service, log, "dos", "b")
        val context = turn(app, log, "tres", "c")!!
        assertFalse("its own turn is already in its history", context.contains("uno"))
        assertTrue(context.contains("Usuario: dos"))
        assertTrue(context.contains("Dexter: b"))
        assertNull("nothing new since", turn(app, log, "cuatro", "d"))
    }

    @Test
    fun actionsAreKeptWithTheReply() {
        val log = log()
        ConversationSync().turn(log, "sube el volumen", {}) { _, events ->
            events(AgentEvent.Action("Volumen al 80 %"))
            events(AgentEvent.Reply("Listo."))
        }
        assertEquals("(Volumen al 80 %) Listo.", log.entries().last().text)
    }

    @Test
    fun withoutALogNothingIsShownOrKept() {
        // A locked phone: the agent gets no log at all.
        assertNull(turn(ConversationSync(), null, "¿qué hablamos ayer?", "Desbloquéalo primero."))
    }

    @Test
    fun aVoiceMessageGetsItsWords() {
        val log = log()
        val (_, seq) = ConversationSync().turn(log, "(mensaje de voz)", {}) { _, events -> events(AgentEvent.Reply("Claro.")) }
        log.update(seq!!, "pon una alarma a las seis")
        assertEquals("pon una alarma a las seis", log.entries().first().text)
    }

    @Test
    fun whatWasNotMeantForDexterLeavesNoTrace() {
        val log = log()
        val sync = ConversationSync()
        turn(sync, log, "hola", "Hola.")
        // The TV, or someone talking to someone else: Dexter answers [IGNORAR].
        val (_, seq) = sync.turn(log, "(mensaje de voz)", {}) { _, events -> events(AgentEvent.Reply(PhoneSafety.IGNORE)) }
        assertNull("nothing to transcribe later", seq)
        assertEquals(listOf("hola", "Hola."), log.entries().map { it.text })
        // Streamed in pieces and with spaces around it, it is still [IGNORAR].
        sync.turn(log, "ruido", {}) { _, events ->
            events(AgentEvent.Reply("  [IGNO"))
            events(AgentEvent.Reply("RAR] \n"))
        }
        assertEquals(2, log.entries().size)
        // Another agent never sees it either.
        assertFalse(turn(ConversationSync(), log, "¿qué dije?", "Hola.")!!.contains("ruido"))
    }

    @Test
    fun ignoreInsideARealAnswerIsKept() {
        val log = log()
        ConversationSync().turn(log, "¿qué significa [IGNORAR]?", {}) { _, events ->
            events(AgentEvent.Reply("Es lo que respondo cuando algo no es para mí: ${PhoneSafety.IGNORE}"))
        }
        assertEquals(2, log.entries().size)
        // An action and [IGNORAR] together is not "nothing happened".
        ConversationSync().turn(log, "algo", {}) { _, events ->
            events(AgentEvent.Action("Linterna encendida"))
            events(AgentEvent.Reply(PhoneSafety.IGNORE))
        }
        assertEquals(4, log.entries().size)
    }

    @Test
    fun aFailedTurnStillKeepsWhatWasSaid() {
        val log = log()
        runCatching {
            ConversationSync().turn(log, "llama a mamá", {}) { _, events ->
                events(AgentEvent.Reply("Llamando…"))
                throw AgentException(AgentException.Kind.NETWORK, "offline")
            }
        }
        assertEquals(listOf("llama a mamá", "Llamando…"), log.entries().map { it.text })
        // A turn that failed before saying anything leaves only the user's words.
        runCatching { ConversationSync().turn(log, "otra vez", {}) { _, _ -> throw AgentException(AgentException.Kind.NETWORK, "offline") } }
        assertEquals("otra vez", log.entries().last().text)
    }

    @Test
    fun readsComeFromMemoryUntilTheFileChanges() {
        val dir = Files.createTempDirectory("conv").toFile()
        val a = DexterConversation(File(dir, "c.json"))
        val b = DexterConversation(File(dir, "c.json"))
        a.add(true, "uno")
        assertEquals("the other store sees it at once", listOf("uno"), b.entries().map { it.text })
        b.add(false, "dos")
        assertEquals(listOf("uno", "dos"), a.entries().map { it.text })
        a.clear()
        assertTrue(b.entries().isEmpty())
        // Deleted from outside: gone for both.
        b.add(true, "tres")
        File(dir, "c.json").delete()
        assertTrue(a.entries().isEmpty())
    }

    @Test
    fun keepsOnlyTheLatestMessagesEncrypted() {
        val dir = Files.createTempDirectory("conv").toFile()
        val log = DexterConversation(File(dir, "c.json"))
        val sync = ConversationSync()
        repeat(40) { turn(sync, log, "secreto $it", "ok $it") }
        val entries = log.entries()
        assertEquals(60, entries.size)
        assertEquals("ok 39", entries.last().text)
        assertFalse(String(File(dir, "c.json").readBytes(), Charsets.ISO_8859_1).contains("secreto"))
        log.clear()
        assertTrue(log.entries().isEmpty())
    }
}
