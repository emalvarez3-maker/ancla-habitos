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

import org.isoron.uhabits.ancla.reading.IdeaGraph
import org.isoron.uhabits.ancla.reading.IdeaKind
import org.isoron.uhabits.ancla.reading.StudyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlin.math.roundToInt
import kotlin.random.Random

/** Notes and study data under random use, several writers at once and reloads, compared against a simple model. */
class NotesStoreStressTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    @Test
    fun randomOperationsMatchAModel() {
        val rnd = Random(10)
        val file = File(Stress.tempDir(), "ideas.json")
        val a = IdeaGraph(file)
        val b = IdeaGraph(file) // a second holder, like Dexter next to the notes screen
        val model = LinkedHashMap<String, Pair<String, String>>() // id -> title, note
        repeat(Stress.iterations(600)) { step ->
            val g = if (rnd.nextBoolean()) a else b
            when (rnd.nextInt(6)) {
                0, 1 -> {
                    val title = "Nota ${rnd.nextInt(40)}"
                    val idea = g.create(title, note = "texto $step")
                    if (model.values.none { IdeaGraph.key(it.first) == IdeaGraph.key(title) }) model[idea.id] = title to "texto $step"
                    assertEquals(IdeaGraph.key(title), IdeaGraph.key(idea.title))
                }
                2 -> model.keys.randomOrNull(rnd)?.let { id ->
                    val (title, _) = model.getValue(id)
                    val body = Stress.nastyText(rnd, 30)
                    g.edit(id, title, body)
                    model[id] = title to body
                }
                3 -> model.keys.randomOrNull(rnd)?.let { id ->
                    g.delete(id)
                    model.remove(id)
                }
                4 -> {
                    val idea = g.appendJournal("entrada $step")
                    model[idea.id] = idea.title to idea.note
                }
                else -> {
                    // Reload from disk with a brand-new instance.
                    val fresh = IdeaGraph(file).ideas.associate { it.id to (it.title to it.note) }
                    assertEquals(model, fresh)
                }
            }
            val actual = g.ideas.associate { it.id to (it.title to it.note) }
            if (actual != model) {
                val diff = (model.keys + actual.keys).filter { model[it] != actual[it] }.map { "$it: model=${model[it]?.toList()?.map { s -> s.map { ch -> ch.code } }} store=${actual[it]?.toList()?.map { s -> s.map { ch -> ch.code } }}" }
                throw AssertionError("step $step differs: $diff")
            }
        }
        assertFalse("note text must never be stored in the clear", String(file.readBytes(), Charsets.ISO_8859_1).contains("texto"))
    }

    @Test
    fun renamingANoteUpdatesEveryLinkToIt() {
        val g = IdeaGraph(File(Stress.tempDir(), "ideas.json"))
        val target = g.create("Cinemática")
        val others = (1..20).map { g.create("Nota $it", note = "Ver [[Cinemática]] y [[cinematica|alias]] y [[Cinemática#DH]]") }
        g.edit(target.id, "Cinemática directa", "")
        others.forEach { o ->
            val n = g.byId(o.id)!!.note
            assertFalse(n, n.contains("[[Cinemática]]"))
            assertEquals(3, IdeaGraph.wikiTargets(n).count { it == "Cinemática directa" })
        }
        assertEquals(20, g.backlinks(g.byId(target.id)!!).size)
    }

    @Test
    fun manyThreadsWritingAtOnceLoseNothing() {
        val file = File(Stress.tempDir(), "ideas.json")
        val threads = 8
        val perThread = Stress.iterations(40)
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                val g = IdeaGraph(file)
                start.await()
                repeat(perThread) { i -> g.create("T$t-$i", note = "n") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES))
        val titles = IdeaGraph(file).ideas.map { it.title }.toSet()
        assertEquals(threads * perThread, titles.size)
    }

    @Test
    fun studySessionsSurviveRestartsAndWriteTheirNotes() {
        val dir = Stress.tempDir()
        val rnd = Random(11)
        var now = 1_700_000_000_000L
        var expected = 0
        repeat(Stress.iterations(150)) {
            val store = StudyStore(dir) // as if the app restarted between actions
            when (rnd.nextInt(4)) {
                0 -> {
                    // Starting while a session runs saves that one first (if it reached a minute).
                    store.active?.let { if (((now - it.second) / 60_000.0).roundToInt() >= 1) expected++ }
                    store.start("Robótica", now)
                }
                1 -> {
                    now += rnd.nextLong(0, 90 * 60_000)
                    if (store.stop(now) != null) expected++
                }
                2 -> store.cancel()
                else -> store.addTopic("Tema ${rnd.nextInt(5)}", rnd.nextInt(0, 600))
            }
            now += rnd.nextLong(1, 60_000)
            assertEquals(expected, StudyStore(dir).sessions.size)
        }
        val sessions = StudyStore(dir).sessions
        val notes = IdeaGraph(File(dir, "ideas.json"))
        sessions.forEach { s ->
            val note = notes.byId(s.noteId)
            assertNotNull(note)
            assertEquals(IdeaKind.STUDY, note!!.kind)
            assertTrue(note.note.contains("[[${s.topic}]]"))
            assertTrue(s.minutes >= 1)
        }
        assertNotNull(notes.find("Robótica"))
    }

    @Test
    fun aShortSessionIsDiscardedAndAStatsWeekHasSevenDays() {
        val store = StudyStore(Stress.tempDir())
        store.start("Robótica", 0)
        assertNull(store.stop(20_000))
        assertNull(store.active)
        assertEquals(7, store.stats().week.size)
    }
}
