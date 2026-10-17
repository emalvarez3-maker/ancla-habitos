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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlin.random.Random

/** The at-rest encryption of notes, journal, study and Dexter's memory. */
class EncryptedStoreTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            // The JVM has no Android Keystore: a software AES-256 key stands in for it.
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    @Test
    fun roundTripsAnyTextAndNeverWritesItInTheClear() {
        val dir = Stress.tempDir()
        val rnd = Random(1)
        repeat(Stress.iterations(300)) { i ->
            val file = File(dir, "f$i.json")
            val text = Stress.nastyText(rnd, 200) + "SECRET-$i"
            EncryptedStore.writeText(file, text)
            val raw = file.readBytes()
            assertTrue(EncryptedStore.isEncrypted(raw))
            assertFalse("plaintext leaked", String(raw, Charsets.ISO_8859_1).contains("SECRET-$i"))
            assertEquals(text, EncryptedStore.readText(file))
            assertFalse(File(dir, "f$i.json.tmp").exists())
        }
    }

    @Test
    fun everyWriteUsesAFreshIv() {
        val a = EncryptedStore.encrypt("same".toByteArray(), "x")
        val b = EncryptedStore.encrypt("same".toByteArray(), "x")
        assertFalse(a.contentEquals(b))
        assertArrayEquals("same".toByteArray(), EncryptedStore.decrypt(a, "x"))
    }

    @Test
    fun migratesPlainFilesOnFirstRead() {
        val file = File(Stress.tempDir(), "ideas.json")
        file.writeText("{\"ideas\":[]}")
        assertEquals("{\"ideas\":[]}", EncryptedStore.readText(file))
        assertTrue(EncryptedStore.isEncrypted(file.readBytes()))
        assertEquals("{\"ideas\":[]}", EncryptedStore.readText(file))
    }

    @Test
    fun tamperingIsDetectedAndTheFileIsKeptAside() {
        val rnd = Random(2)
        repeat(Stress.iterations(5)) {
            val dir = Stress.tempDir()
            val file = File(dir, "study.json")
            EncryptedStore.writeText(file, "valuable data")
            val bytes = file.readBytes()
            val at = 4 + rnd.nextInt(bytes.size - 4) // anywhere after the magic header
            bytes[at] = (bytes[at].toInt() xor (1 shl rnd.nextInt(8))).toByte()
            file.writeBytes(bytes)
            assertNull(EncryptedStore.readText(file))
            assertFalse("a corrupt file must not stay where it would be overwritten", file.exists())
            assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("study.json.unreadable-") })
        }
    }

    @Test
    fun aFileCopiedUnderAnotherNameDoesNotDecrypt() {
        val dir = Stress.tempDir()
        val notes = File(dir, "ideas.json")
        EncryptedStore.writeText(notes, "notes")
        val study = File(dir, "study.json")
        notes.copyTo(study)
        assertNull(EncryptedStore.readText(study)) // bound to its name by the associated data
    }

    @Test
    fun concurrentWritersNeverLeaveATornFile() {
        val file = File(Stress.tempDir(), "ideas.json")
        val pool = Executors.newFixedThreadPool(8)
        val payloads = (0 until 8).map { t -> "writer $t ".repeat(500) }
        repeat(Stress.iterations(40)) {
            payloads.forEach { p -> pool.execute { synchronized(this) { EncryptedStore.writeText(file, p) } } }
            pool.execute { synchronized(this) { assertTrue(EncryptedStore.readText(file) in payloads) } }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES))
        assertTrue(EncryptedStore.readText(file) in payloads)
    }
}
