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

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The parsed contents of the encrypted stores (goals, finances, Dexter's conversation), kept in
 * memory until their file changes. Without it, every poll of Camino and Finanzas and every read in
 * a Dexter turn decrypted and parsed the whole file again. One entry per file for the whole
 * process: the screen's store and Dexter's store share it, and their writes update it. A change
 * made outside (another app version, a restore) changes the file's stamp and is read again.
 *
 * Callers hold their store's lock; values must be immutable.
 */
internal object StoreCache {
    private class Entry(val stamp: Long, val value: Any, val version: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val versions = AtomicLong()

    /** When the file was last written and its size, in one stat; 0 when there is no file. */
    internal fun stamp(file: File): Long = try {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        a.lastModifiedTime().toMillis() * 31 + a.size()
    } catch (e: IOException) {
        0L
    }

    /** The parsed contents of [file]: from memory when it hasn't changed, else decrypted and [parse]d. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> read(file: File, parse: (String?) -> T): T {
        val key = file.absolutePath
        entries[key]?.takeIf { it.stamp == stamp(file) }?.let { return it.value as T }
        val value = parse(EncryptedStore.readText(file)) // also encrypts an old plain-text file
        entries[key] = Entry(stamp(file), value, versions.incrementAndGet())
        return value
    }

    /** After [file] was written with [value], so the next read needn't decrypt it. */
    fun wrote(file: File, value: Any) {
        entries[file.absolutePath] = Entry(stamp(file), value, versions.incrementAndGet())
    }

    /** After [file] was deleted or written in a way the cache can't follow. */
    fun forget(file: File) {
        entries.remove(file.absolutePath)
        versions.incrementAndGet()
    }

    /** Changes whenever what [read] returns for [file] may have changed: a cheap signal for polling screens. */
    fun <T : Any> version(file: File, parse: (String?) -> T): Long {
        read(file, parse)
        return entries[file.absolutePath]?.version ?: 0L
    }
}
