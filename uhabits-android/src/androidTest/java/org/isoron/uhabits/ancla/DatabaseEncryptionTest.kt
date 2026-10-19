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

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.database.DatabaseCipher
import org.isoron.uhabits.utils.DatabaseUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The habits database on a real device: encrypted with SQLCipher, converted without losing rows, exported in plain only on request. */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionTest {
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DatabaseCipher.loadLibrary()
        HabitsApplication.component.habitList.size() // the app has opened its database
    }

    private fun header(file: File) = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }

    private fun count(db: SQLiteDatabase, table: String) = db.rawQuery("SELECT count(*) FROM $table", emptyArray<String>()).use { it.moveToFirst(); it.getLong(0) }

    /** A plain SQLite file like the ones older versions left behind. */
    private fun plainDatabase(name: String, rows: Int): File {
        val file = File(ctx.cacheDir, name)
        listOf("", "-journal", "-wal", "-shm", ".encrypting").forEach { File(file.path + it).delete() }
        SQLiteDatabase.openOrCreateDatabase(file, "", null, null).use { db ->
            db.execSQL("CREATE TABLE habits (id INTEGER PRIMARY KEY, name TEXT)")
            db.execSQL("CREATE TABLE repetitions (habit INTEGER, day INTEGER, value INTEGER)")
            db.beginTransaction()
            for (i in 1..rows) {
                db.execSQL("INSERT INTO habits (name) VALUES (?)", arrayOf<Any>("Hábito secreto $i"))
                db.execSQL("INSERT INTO repetitions VALUES (?, ?, ?)", arrayOf<Any>(i, i * 86400, 2))
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            db.version = 23
        }
        return file
    }

    @Test
    fun theAppDatabaseIsEncryptedOnDisk() {
        val file = DatabaseUtils.getDatabaseFile(ctx)
        assertTrue(file.exists())
        assertFalse("the database must not be a plain SQLite file", DatabaseCipher.isPlaintext(file))
        assertNotNull("the database must be open with a key", DatabaseCipher.current)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("CREATE TABLE"))
    }

    @Test
    fun thePassphraseIsNotStoredInPlainText() {
        val key = DatabaseCipher.current ?: return fail("no key")
        val prefs = File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
        assertTrue(prefs.none { String(it.readBytes(), Charsets.ISO_8859_1).contains(key) })
    }

    @Test
    fun aPlainDatabaseIsConvertedWithEveryRowAndItsVersion() {
        val file = plainDatabase("migrate-test.db", 500)
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertTrue("the test database must start plain", DatabaseCipher.isPlaintext(file))
        val migrated = DatabaseCipher.migrate(file, key)
        assertTrue("conversion failed: ${DatabaseCipher.lastMigrationError}", migrated)
        assertFalse(DatabaseCipher.isPlaintext(file))
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("Hábito secreto"))
        SQLiteDatabase.openDatabase(file.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
            assertEquals(500L, count(db, "habits"))
            assertEquals(500L, count(db, "repetitions"))
            assertEquals(23, db.version)
        }
        // Without the key the file is unreadable.
        try {
            SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db -> count(db, "habits") }
            fail("opened without the key")
        } catch (expected: Exception) {
        }
        file.delete()
    }

    @Test
    fun aFailedConversionLeavesThePlainFileUntouched() {
        val file = File(ctx.cacheDir, "broken.db")
        val bytes = "SQLite format 3\u0000".toByteArray() + ByteArray(4096) { 7 }
        file.writeBytes(bytes)
        assertFalse(DatabaseCipher.migrate(file, "abc"))
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(file.path + ".encrypting").exists())
        file.delete()
    }

    @Test
    fun anExportTheUserAsksForIsPlainAndImportable() {
        val out = File(ctx.cacheDir, "export-test.db")
        DatabaseCipher.exportPlain(DatabaseUtils.openDatabase(), out)
        assertTrue(DatabaseCipher.isPlaintext(out))
        DatabaseCipher.openForImport(out.path).use { db -> assertTrue(count(db, "sqlite_master") > 0) }
        out.delete()
    }

    @Test
    fun anAutomaticBackupStaysEncryptedButThisPhoneCanImportIt() {
        val dir = File(ctx.cacheDir, "auto-backup-test").apply { deleteRecursively(); mkdirs() }
        val path = DatabaseUtils.saveDatabaseCopy(ctx, dir)
        val copy = File(path)
        assertFalse(DatabaseCipher.isPlaintext(copy))
        assertFalse(header(copy).contentEquals("SQLite format 3\u0000".toByteArray()))
        DatabaseCipher.openForImport(copy.path).use { db -> assertTrue(count(db, "sqlite_master") > 0) }
        dir.deleteRecursively()
    }
}
