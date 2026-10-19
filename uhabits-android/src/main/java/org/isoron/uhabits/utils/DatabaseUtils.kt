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
package org.isoron.uhabits.utils

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.isoron.platform.time.DateFormats.Companion.getBackupDateFormat
import org.isoron.uhabits.HabitsApplication.Companion.isTestMode
import org.isoron.uhabits.HabitsDatabaseOpener
import org.isoron.uhabits.core.DATABASE_FILENAME
import org.isoron.uhabits.core.DATABASE_VERSION
import org.isoron.uhabits.database.DatabaseCipher
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat

object DatabaseUtils {
    private var opener: HabitsDatabaseOpener? = null

    @JvmStatic
    fun getDatabaseFile(context: Context): File {
        val databaseFilename = databaseFilename
        val root = context.filesDir.path
        return File("$root/../databases/$databaseFilename")
    }

    private val databaseFilename: String
        get() {
            var databaseFilename: String = DATABASE_FILENAME
            if (isTestMode()) databaseFilename = "test.db"
            return databaseFilename
        }

    fun initializeDatabase(context: Context?) {
        // Encrypts an old plain database first; the passphrase never leaves this call chain.
        val passphrase = DatabaseCipher.prepare(context!!, getDatabaseFile(context))
        opener = HabitsDatabaseOpener(
            context,
            databaseFilename,
            passphrase,
            DATABASE_VERSION
        )
    }

    /**
     * The file a backup is made from. [plain]: an unencrypted copy (an export the user asked for,
     * importable anywhere), written to the cache and deleted by the caller. Otherwise the encrypted
     * database itself (automatic backups: useless to anyone without this phone's Keystore key).
     */
    private fun backupSource(context: Context, plain: Boolean): File {
        if (!plain) return getDatabaseFile(context)
        val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.db")
        DatabaseCipher.exportPlain(openDatabase(), out)
        return out
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun saveDatabaseCopy(context: Context, dir: File, plain: Boolean = false): String {
        val dateFormat: SimpleDateFormat = getBackupDateFormat()
        val date = dateFormat.format(System.currentTimeMillis())
        val filename = "${dir.absolutePath}/Loop Habits Backup $date.db"
        Log.i("DatabaseUtils", "Writing: $filename")
        val db = backupSource(context, plain)
        val dbCopy = File(filename)
        try {
            db.copyTo(dbCopy)
        } finally {
            if (plain) db.delete()
        }
        return dbCopy.absolutePath
    }

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun saveDatabaseCopy(context: Context, dir: DocumentFile, plain: Boolean = false): String {
        val dateFormat: SimpleDateFormat = getBackupDateFormat()
        val date = dateFormat.format(System.currentTimeMillis())
        val file = dir.createFile("application/octet-stream", "Loop Habits Backup $date.db")
            ?: throw IOException("Unable to create backup file")
        Log.i("DatabaseUtils", "Writing: ${file.uri}")
        val db = backupSource(context, plain)
        try {
            FileInputStream(db).use { input ->
                context.contentResolver.openOutputStream(file.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            if (plain) db.delete()
        }
        return file.uri.toString()
    }

    fun openDatabase(): SQLiteDatabase {
        checkNotNull(opener)
        return opener!!.writableDatabase
    }
}
