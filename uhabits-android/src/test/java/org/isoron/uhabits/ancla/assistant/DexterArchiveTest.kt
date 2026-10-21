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
import org.isoron.uhabits.ancla.Stress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.KeyGenerator

/** Dexter remembers past conversations for months, finds them when asked, and keeps a daily journal. */
class DexterArchiveTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val zone = ZoneId.systemDefault()
    private fun at(day: LocalDate, hour: Int = 10) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val today = LocalDate.of(2026, 10, 21)

    @Test
    fun oldConversationsAreFoundByWhatTheyWereAbout() {
        val a = DexterArchive(File(Stress.tempDir(), "archive"))
        a.add(1, at(today.minusDays(70)), true, "Mi hermana Valentina se gradúa de medicina en diciembre")
        a.add(2, at(today.minusDays(70), 11), false, "¡Qué orgullo! ¿Vas a ir a la ceremonia?")
        a.add(3, at(today.minusDays(5)), true, "Mañana tengo el examen de física con el profesor Ramírez")
        a.add(4, at(today), true, "Pon una alarma a las 7")
        val found = a.search("¿cuándo se gradúa mi hermana?")
        assertEquals("Mi hermana Valentina se gradúa de medicina en diciembre", found.first().text)
        assertTrue("months apart, in another file", a.search("examen fisica").any { it.text.contains("Ramírez") })
        assertTrue(a.search("").isEmpty())
        assertTrue("stop words alone find nothing", a.search("de la que").isEmpty())
    }

    @Test
    fun voiceMessagesAreUpdatedAndFalseWakesRemoved() {
        val a = DexterArchive(File(Stress.tempDir(), "archive"))
        a.add(1, at(today), true, PhoneSafety.AUDIO_TURN)
        a.add(2, at(today, 11), true, "nadie me habló")
        a.update(1, "Quiero aprender a tocar guitarra")
        a.remove(2)
        assertEquals(listOf("Quiero aprender a tocar guitarra"), a.day(today).map { it.text })
    }

    @Test
    fun placeholdersAreNotWhatWasSaid() {
        val a = DexterArchive(File(Stress.tempDir(), "archive"))
        a.add(1, at(today), true, PhoneSafety.WAKE_AUDIO_TURN)
        assertTrue(a.day(today).isEmpty())
        assertTrue(a.daysBetween(today.minusDays(3), today.plusDays(1)).isEmpty())
    }

    @Test
    fun theConversationFeedsTheArchiveAndNumbersNeverRepeat() {
        val dir = Stress.tempDir()
        val archive = DexterArchive(File(dir, "archive"))
        val log = DexterConversation(File(dir, "c.json"), archive)
        val first = log.add(true, "Me encanta el café de Loja")
        log.clear()
        val second = log.add(true, "Estoy leyendo a Borges")
        assertTrue("a cleared log never reuses a number", second > first)
        val words = archive.search("cafe loja") + archive.search("borges")
        assertEquals(2, words.map { it.seq }.distinct().size)
    }

    @Test
    fun theJournalSplitsLinesFromLastingFacts() {
        val (text, facts) = DexterJournal.parse(
            "Hablaron del examen de física del martes; decidió estudiar 2 horas diarias.\n" +
                "Se le notó ansioso pero con ganas.\n" +
                "HECHO: [Personas] Su profesor de física es Ramírez\n" +
                "- HECHO: [Estudios] Estudia ingeniería mecatrónica en la ESPE"
        )
        assertTrue(text.startsWith("Hablaron del examen"))
        assertFalse(text.contains("HECHO"))
        assertEquals(listOf("[Personas] Su profesor de física es Ramírez", "[Estudios] Estudia ingeniería mecatrónica en la ESPE"), facts)
    }

    @Test
    fun pastDaysGetTheirJournalOnceAndItsFactsAreRemembered() {
        val dir = Stress.tempDir()
        val archive = DexterArchive(File(dir, "archive"))
        val journal = DexterJournal(File(dir, "journal.json"))
        val yesterday = today.minusDays(1)
        archive.add(1, at(yesterday), true, "Hoy terminé con mi novia")
        archive.add(2, at(yesterday, 11), false, "Lo siento mucho. ¿Cómo estás?")
        archive.add(3, at(today), true, "Hola")
        archive.add(4, at(today, 11), false, "Hola")
        val asked = mutableListOf<String>()
        DexterJournal.catchUp(archive, journal, null, today) { day ->
            asked += day
            "Le contó a Dexter que terminó con su novia; estaba triste.\nHECHO: [Personas] Terminó con su novia en octubre de 2026"
        }
        assertEquals("only finished days, not today", 1, asked.size)
        assertTrue(asked.first().contains("Usuario: Hoy terminé con mi novia"))
        assertEquals("Le contó a Dexter que terminó con su novia; estaba triste.", journal.all()[yesterday])
        assertEquals(1, journal.recent().size)
    }

    @Test
    fun memoryIsGroupedByTopicWithinItsRoom() {
        val facts = listOf("[Personas] Su hermana es Valentina", "Le gusta el café", "[Estudios] Estudia en la ESPE", "[Personas] Su mamá es Rosa")
        val block = memoryBlock(facts)
        assertTrue(block, block.contains("Personas:\n- Su hermana es Valentina\n- Su mamá es Rosa"))
        assertTrue(block.contains("Otros:\n- Le gusta el café"))
        assertTrue("Otros goes last", block.indexOf("Otros:") > block.indexOf("Personas:"))
        val small = memoryBlock(facts, maxChars = 60)
        assertTrue("the newest stay", small.contains("Su mamá es Rosa"))
        assertTrue(small.contains("recuerdos más antiguos"))
        assertEquals("(nada todavía)", memoryBlock(emptyList()))
    }
}
