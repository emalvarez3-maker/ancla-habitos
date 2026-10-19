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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.isoron.uhabits.ancla.assistant.AssistantActivity
import org.isoron.uhabits.ancla.assistant.BargeIn
import org.isoron.uhabits.ancla.assistant.DexterAnnouncer
import org.isoron.uhabits.ancla.assistant.DexterMemory
import org.isoron.uhabits.ancla.assistant.SecureKeys
import org.isoron.uhabits.ancla.reading.IdeaGraph
import org.isoron.uhabits.ancla.reading.LibraryActivity
import org.isoron.uhabits.ancla.reading.NotesActivity
import org.isoron.uhabits.ancla.reading.StudyStore
import org.isoron.uhabits.ancla.robotics.RoboticsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Dexter on a real Android: the Keystore encryption, every Dexter screen opening without a crash
 * (with a screenshot of each for review), reminders spoken on their own, quiet mode, and the
 * microphone listener that lets the user interrupt. Screenshots go to the app's external files
 * dir, under dexter-screens, where the CI job pulls them.
 */
@RunWith(AndroidJUnit4::class)
class DexterDeviceTest {
    @get:Rule
    val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun allowScreenshots() {
        // FLAG_SECURE would turn every review screenshot black; the emulator has no screen lock,
        // so the app lock is off there anyway.
        SecureKeys(ctx).secureScreens = false
    }

    @Test
    fun theAppLockNeedsAPhoneWithAScreenLock() {
        assertEquals(SecureKeys(ctx).appLock && AppLock.deviceSecure(ctx), AppLock.required(ctx))
    }

    private fun secret() = "secreto-" + UUID.randomUUID().toString().take(8)

    private fun fileContains(file: File, text: String) = file.exists() && String(file.readBytes(), Charsets.ISO_8859_1).contains(text)

    // --- Encryption with the real Keystore ---

    @Test
    fun encryptedFileRoundTripsAndHidesItsText() {
        val file = File(ctx.filesDir, "device-test.json")
        val text = "{\"nota\":\"${secret()}\"}"
        EncryptedStore.writeText(file, text)
        assertTrue(EncryptedStore.isEncrypted(file.readBytes()))
        assertFalse(fileContains(file, text))
        assertEquals(text, EncryptedStore.readText(file))
        file.delete()
    }

    @Test
    fun aPlainFileFromAnOlderVersionIsEncryptedOnFirstRead() {
        val file = File(ctx.filesDir, "device-test-plain.json")
        val text = "{\"vieja\":\"${secret()}\"}"
        file.writeText(text)
        assertEquals(text, EncryptedStore.readText(file))
        assertTrue(EncryptedStore.isEncrypted(file.readBytes()))
        assertEquals(text, EncryptedStore.readText(file))
        file.delete()
    }

    @Test
    fun aTamperedFileIsMovedAsideNotOverwritten() {
        val file = File(ctx.filesDir, "device-test-tamper.json")
        EncryptedStore.writeText(file, "{\"x\":\"${secret()}\"}")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertNull(EncryptedStore.readText(file))
        val aside = ctx.filesDir.listFiles().orEmpty().filter { it.name.startsWith("device-test-tamper.json.unreadable") }
        assertTrue("the tampered file must be kept aside", aside.isNotEmpty())
        aside.forEach { it.delete() }
        file.delete()
    }

    @Test
    fun notesStudyAndMemoryNeverReachTheDiskInPlainText() {
        val note = secret()
        val graph = IdeaGraph(ctx)
        val idea = graph.create("Prueba de cifrado $note", note = "Texto privado $note")
        assertFalse(fileContains(File(ctx.filesDir, "ideas.json"), note))
        assertNotNull(IdeaGraph(ctx).byId(idea.id)) // another instance reads it back
        graph.delete(idea.id)

        val topic = "Tema " + secret()
        val study = StudyStore(ctx)
        study.start(topic)
        assertFalse(fileContains(File(ctx.filesDir, "study.json"), topic))
        study.cancel()
        study.removeTopic(topic)

        val fact = "Le gusta " + secret()
        val memory = DexterMemory(ctx)
        memory.remember(fact)
        assertTrue(memory.facts.contains(fact))
        val prefs = File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        assertTrue("Dexter's memory must not be stored in plain text", prefs.none { fileContains(it, fact) })
        memory.forget(fact)
    }

    // --- Dexter speaking on its own ---

    @Test
    fun quietModeSilencesRemindersAndTheyCanSpeakOtherwise() {
        val keys = SecureKeys(ctx)
        val before = keys.quietMode
        keys.quietMode = true
        assertNotNull(DexterAnnouncer.quietReason(ctx))
        DexterAnnouncer.announce(ctx, DexterAnnouncer.taskText("prueba en silencio")) // must do nothing
        keys.quietMode = false
        // The emulator's ringer is normal: Dexter may speak, and starting the voice must not crash.
        DexterAnnouncer.announce(ctx, DexterAnnouncer.taskText("prueba del recordatorio hablado"))
        Thread.sleep(4000)
        keys.quietMode = before
    }

    @Test
    fun theInterruptListenerTakesAndFreesTheMicrophone() {
        val listener = BargeIn {}
        listener.start()
        Thread.sleep(800)
        listener.stop()
        assertFalse(listener.isRunning)
        // Started again right away, as between two replies.
        listener.start()
        Thread.sleep(300)
        listener.stop()
        assertFalse(listener.isRunning)
    }

    // --- Every screen opens, with a screenshot ---

    private fun screen(name: String, intent: Intent, settleMs: Long = 3500) {
        ActivityScenario.launch<android.app.Activity>(intent).use {
            Thread.sleep(settleMs)
            shot(name)
        }
    }

    private fun shot(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val dir = File(ctx.getExternalFilesDir(null), "dexter-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun todayOpens() = screen("01-hoy", Intent(ctx, TodayActivity::class.java))

    @Test
    fun dexterOpensWithTheOwl() = screen("02-dexter", AssistantActivity.intent(ctx), settleMs = 5000)

    @Test
    fun notesOpen() = screen("03-notas", NotesActivity.intent(ctx))

    @Test
    fun graphOpens() = screen("04-grafo", NotesActivity.intent(ctx, tab = "graph"))

    @Test
    fun studyOpens() = screen("05-estudio", NotesActivity.intent(ctx, tab = "study"))

    @Test
    fun libraryOpens() = screen("06-biblioteca", Intent(ctx, LibraryActivity::class.java))

    @Test
    fun roboticsCourseOpens() = screen("07-robotica", RoboticsActivity.intent(ctx))

    @Test
    fun aLessonWithFormulasAndItsSimulatorOpens() = screen("08-leccion-7", RoboticsActivity.intent(ctx, lesson = 7), settleMs = 6000)

    @Test
    fun theMechanismWorkshopOpens() = screen("09-taller", RoboticsActivity.intent(ctx, screen = RoboticsActivity.SCREEN_WORKSHOP))

    @Test
    fun theRobotsIn3dOpen() = screen("10-robots-3d", RoboticsActivity.intent(ctx, screen = RoboticsActivity.SCREEN_ROBOTS))
}
