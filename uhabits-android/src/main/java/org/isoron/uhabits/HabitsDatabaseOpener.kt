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

package org.isoron.uhabits

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import kotlinx.coroutines.runBlocking
import org.isoron.platform.io.migrateTo
import org.isoron.platform.io.setVersion
import org.isoron.uhabits.core.database.UnsupportedDatabaseVersionException
import org.isoron.uhabits.database.AndroidDatabase

/**
 * Opens the habits database with SQLCipher; [passphrase] comes from DatabaseCipher.prepare
 * ("" only while an old plain database couldn't be converted yet).
 */
class HabitsDatabaseOpener(
    private val context: Context,
    private val databaseFilename: String,
    passphrase: String,
    private val version: Int
) : SQLiteOpenHelper(context, databaseFilename, passphrase, null, version, 0, null, null, false) {

    override fun onCreate(db: SQLiteDatabase) {
        db.disableWriteAheadLogging()
        db.version = 8
        onUpgrade(db, -1, version)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.disableWriteAheadLogging()
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        db.disableWriteAheadLogging()
        if (db.version < 8) throw UnsupportedDatabaseVersionException()
        val wrappedDb = AndroidDatabase(db)
        wrappedDb.setVersion(db.version)
        runBlocking {
            wrappedDb.migrateTo(newVersion) { version ->
                val filename = "%02d.sql".format(version)
                context.assets.open("migrations/$filename").bufferedReader().readText()
            }
        }
    }

    override fun onDowngrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        throw UnsupportedDatabaseVersionException()
    }
}
