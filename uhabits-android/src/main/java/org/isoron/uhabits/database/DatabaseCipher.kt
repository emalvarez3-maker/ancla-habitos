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

package org.isoron.uhabits.database

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.isoron.uhabits.ancla.EncryptedStore
import java.io.File
import java.security.SecureRandom

/**
 * The habits database (habits, check-ins, tasks, books) encrypted with SQLCipher: AES-256 on
 * every page of the file, so a copy of uhabits.db reveals nothing.
 *
 * The passphrase is 32 random bytes, generated once and stored in SharedPreferences encrypted
 * by [EncryptedStore], whose key lives in the Android Keystore and never leaves it. A plain
 * database from an older version is converted on first open (sqlcipher_export), checked table by
 * table, and only then put in place of the plain file; if anything fails the plain file stays
 * and the conversion is retried on the next launch, so no data is ever lost.
 */
object DatabaseCipher {
    private const val TAG = "DatabaseCipher"
    private const val PREFS = "ancla_database"
    private const val KEY = "passphrase_enc"
    private const val LABEL = "db/passphrase"
    private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    @Volatile private var loaded = false

    /** Why the last conversion failed (exception and SQLite message, never the key), for logs and tests. */
    @Volatile var lastMigrationError: String? = null
        private set

    /** The passphrase of the open database, or null while it is still plain text. */
    @Volatile var current: String? = null
        private set

    fun loadLibrary() = synchronized(this) {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }

    /** True for an unencrypted SQLite file (SQLCipher files start with random bytes). */
    fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < HEADER.size) return false
        val start = ByteArray(HEADER.size)
        file.inputStream().use { if (it.read(start) != start.size) return false }
        return start.contentEquals(HEADER)
    }

    /** The database passphrase, created on first use. Null when it exists but can't be decrypted. */
    private fun passphrase(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        if (stored != null) {
            repeat(3) { attempt ->
                try {
                    return EncryptedStore.decryptString(stored, LABEL)
                } catch (e: Exception) { // the Keystore can be briefly busy; a lost key fails every time
                    Thread.sleep(150L * (attempt + 1))
                }
            }
            return null
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = bytes.joinToString("") { "%02x".format(it) }
        bytes.fill(0)
        check(prefs.edit().putString(KEY, EncryptedStore.encryptString(hex, LABEL)).commit()) { "Could not save the database key" }
        return hex
    }

    /**
     * Prepares [file] to be opened and returns the passphrase to open it with ("" for a database
     * that is still plain text because its conversion failed this time).
     */
    fun prepare(context: Context, file: File): String {
        loadLibrary()
        var key = passphrase(context)
        if (key == null) {
            // The Keystore key is gone (restored or wiped): this encrypted file can never be read
            // again. Keep it aside, never delete it, and start a new database with a new key.
            if (file.exists() && !isPlaintext(file)) {
                val aside = File(file.path + ".unreadable-" + System.currentTimeMillis())
                file.renameTo(aside)
                Log.w(TAG, "database key unavailable; kept the old file as ${aside.name}")
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit()
            key = passphrase(context) ?: error("No database key")
        }
        if (isPlaintext(file) && !migrate(file, key)) {
            current = null
            return ""
        }
        current = key
        return key
    }

    private fun tableCounts(db: SQLiteDatabase, schema: String = "main"): Map<String, Long> {
        val names = mutableListOf<String>()
        db.rawQuery("SELECT name FROM $schema.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        return names.associateWith { name ->
            db.rawQuery("SELECT count(*) FROM $schema.\"${name.replace("\"", "\"\"")}\"", emptyArray<String>()).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
    }

    private fun ident(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    /**
     * Copies every table (with its rows), index, trigger and view of [src] into the empty [dst],
     * plus the schema version, in one transaction. Two separate connections and plain SQL: no
     * ATTACH, whose effect depends on which pooled connection a statement happens to run on.
     */
    private fun copyDatabase(src: SQLiteDatabase, dst: SQLiteDatabase) {
        class Item(val type: String, val name: String, val sql: String)
        val items = mutableListOf<Item>()
        src.rawQuery("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'", emptyArray<String>()).use { c ->
            while (c.moveToNext()) items.add(Item(c.getString(0), c.getString(1), c.getString(2)))
        }
        // Tables the new file already has (android_metadata) keep their own rows.
        val existing = mutableSetOf<String>()
        dst.rawQuery("SELECT name FROM sqlite_master", emptyArray<String>()).use { c -> while (c.moveToNext()) existing.add(c.getString(0)) }
        dst.beginTransaction()
        try {
            val tables = items.filter { it.type == "table" && it.name !in existing }
            tables.forEach { dst.execSQL(it.sql) }
            for (table in tables) {
                src.rawQuery("SELECT * FROM ${ident(table.name)}", emptyArray<String>()).use { c ->
                    val columns = c.columnNames.joinToString(", ") { ident(it) }
                    val insert = "INSERT INTO ${ident(table.name)} ($columns) VALUES (${c.columnNames.joinToString(", ") { "?" }})"
                    while (c.moveToNext()) {
                        val args = Array<Any?>(c.columnCount) { i ->
                            when (c.getType(i)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                                android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                                else -> c.getString(i)
                            }
                        }
                        dst.execSQL(insert, args)
                    }
                }
            }
            // Indexes, views and triggers after the rows, so no trigger fires during the copy.
            items.filter { it.type != "table" && it.name !in existing }.forEach { dst.execSQL(it.sql) }
            dst.version = src.version
            dst.setTransactionSuccessful()
        } finally {
            dst.endTransaction()
        }
    }

    /** Converts the plain [file] into an encrypted one with [key]; false (and the plain file untouched) on any problem. */
    fun migrate(file: File, key: String): Boolean {
        val tmp = File(file.path + ".encrypting")
        listOf("", "-journal", "-wal", "-shm").forEach { File(tmp.path + it).delete() }
        lastMigrationError = null
        return try {
            val (counts, version) = SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, null, null).use { plain ->
                SQLiteDatabase.openOrCreateDatabase(tmp, key, null, null).use { encrypted -> copyDatabase(plain, encrypted) }
                tableCounts(plain) to plain.version
            }
            SQLiteDatabase.openDatabase(tmp.path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { encrypted ->
                check(encrypted.version == version) { "schema version differs" }
                check(tableCounts(encrypted) == counts) { "row counts differ" }
            }
            check(!isPlaintext(tmp)) { "the copy is not encrypted" }
            listOf("-journal", "-wal", "-shm").forEach { File(tmp.path + it).delete() }
            if (!tmp.renameTo(file)) error("could not replace the plain database")
            listOf("-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
            Log.i(TAG, "database encrypted (${counts.size} tables)")
            true
        } catch (e: Exception) {
            // SQLite messages name tables and statements, never rows; the key is cut out anyway.
            lastMigrationError = "${e.javaClass.simpleName}: ${e.message.orEmpty().replace(key, "<key>")}".take(300)
            Log.w(TAG, "could not encrypt the database yet: $lastMigrationError")
            listOf("", "-journal", "-wal", "-shm").forEach { File(tmp.path + it).delete() }
            false
        }
    }

    /**
     * Writes an unencrypted copy of the open database [db] to [out], for an export the user asked
     * for (so it can be imported anywhere). Automatic backups copy the encrypted file instead.
     */
    fun exportPlain(db: SQLiteDatabase, out: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(out.path + it).delete() }
        if (current == null) {
            File(db.path).copyTo(out, overwrite = true) // still plain text
            return
        }
        SQLiteDatabase.openOrCreateDatabase(out, "", null, null).use { plain -> copyDatabase(db, plain) }
        listOf("-journal", "-wal", "-shm").forEach { File(out.path + it).delete() }
    }

    /** Opens a database file chosen by the user (an import): plain, or encrypted with this phone's key. */
    fun openForImport(path: String): SQLiteDatabase {
        loadLibrary()
        val keys = listOfNotNull("", current).distinct()
        var last: Exception? = null
        for (key in keys) {
            try {
                val db = SQLiteDatabase.openDatabase(path, key, null, SQLiteDatabase.OPEN_READWRITE, null, null)
                try {
                    // SQLCipher only notices a wrong key on the first read.
                    db.rawQuery("SELECT count(*) FROM sqlite_master", emptyArray<String>()).use { it.moveToFirst() }
                    return db
                } catch (e: Exception) {
                    db.close()
                    throw e
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Could not open $path")
    }
}
