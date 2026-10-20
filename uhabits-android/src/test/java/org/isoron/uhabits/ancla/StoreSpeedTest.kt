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

import org.isoron.uhabits.ancla.assistant.DexterConversation
import org.isoron.uhabits.ancla.finance.FinanceStore
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.crypto.KeyGenerator

/**
 * What the screens and Dexter pay for reading the stores: a Camino or Finanzas poll, and the three
 * reads of the conversation in every turn. Prints the cost per read (the JVM's AES is far quicker
 * than the phone's Keystore, so on the phone every decrypt costs more than here).
 */
class StoreSpeedTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }

        private const val READS = 300
    }

    private fun micros(label: String, body: () -> Unit): Double {
        repeat(30) { body() } // warm up
        val start = System.nanoTime()
        repeat(READS) { body() }
        val us = (System.nanoTime() - start) / 1000.0 / READS
        println("StoreSpeed: $label %.1f µs per read".format(us))
        return us
    }

    /** The same read decrypting the file every time, as before [StoreCache], and as it is now. */
    private fun compare(label: String, file: File, version: () -> Long, read: () -> Unit) {
        val before = micros("$label, decrypting every time") {
            StoreCache.forget(file)
            read()
        }
        val now = micros("$label, from memory") { read() }
        val poll = micros("$label, version only") { version() }
        println("StoreSpeed: $label %.0fx faster; an unchanged poll costs %.1f µs".format(before / now, poll))
        assertTrue("$label: reading from memory is faster than decrypting", now < before)
    }

    @Test
    fun caminoPoll() {
        val file = File(Stress.tempDir(), "goals.json")
        val store = GoalStore(file)
        repeat(30) { i ->
            store.add("Meta número $i", 2027 + i % 3, "una nota de ejemplo para que pese")
            store.addMilestone("Meta número $i", "Primer paso", "2027-03")
            store.setWoop("Meta número $i", "un resultado", "un obstáculo", "si pasa X, haré Y")
        }
        compare("Camino poll (30 goals)", file, store::version) { store.all() }
    }

    @Test
    fun finanzasPoll() {
        val file = File(Stress.tempDir(), "finance.json")
        val store = FinanceStore(file)
        val today = LocalDate.now()
        repeat(400) { i -> store.add(100L + i, i % 10 == 0, null, "almuerzo $i", today.minusDays((i % 60).toLong())) }
        store.setBudget("Comida", 20000)
        val month = YearMonth.now()
        compare("Finanzas poll (400 movements)", file, store::version) {
            store.summary(month)
            store.movements(month)
        }
    }

    @Test
    fun conversationTurn() {
        val file = File(Stress.tempDir(), "c.json")
        val log = DexterConversation(file)
        repeat(60) { log.add(it % 2 == 0, "mensaje bastante largo número $it ".repeat(6)) }
        compare("Conversation entries (60 messages)", file, { log.entries().size.toLong() }) { log.entries() }
    }

    @Test
    fun theFileStampIsOneCheapLook() {
        val file = File(Stress.tempDir(), "x.json").apply { writeText("{}") }
        micros("file stamp (one stat)") { StoreCache.stamp(file) }
        micros("exists + lastModified + length (three)") { if (file.exists()) file.lastModified() * 31 + file.length() }
    }
}
