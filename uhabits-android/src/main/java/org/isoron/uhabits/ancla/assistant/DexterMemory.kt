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

import android.content.Context
import org.isoron.uhabits.ancla.EncryptedStore
import org.json.JSONArray
import java.text.Normalizer

/**
 * What Dexter has learned about the user from their conversations: short facts it saves with
 * the remember tool and reads back at the start of every turn. Stays on the phone.
 */
class DexterMemory(context: Context) {
    private val prefs = context.getSharedPreferences("ancla_memory", Context.MODE_PRIVATE)

    val facts: List<String>
        get() = runCatching { stored() }.getOrDefault(emptyList())

    /** Throws when the facts can't be decrypted, so a change never overwrites them with less. */
    private fun stored(): List<String> {
        val arr = JSONArray(load() ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /** The facts as JSON, decrypted; older versions kept them in plain text, which is migrated here. */
    private fun load(): String? {
        prefs.getString(PLAIN, null)?.let { plain ->
            prefs.edit().putString(ENCRYPTED, EncryptedStore.encryptString(plain, LABEL)).remove(PLAIN).commit()
            return plain
        }
        val stored = prefs.getString(ENCRYPTED, null) ?: return null
        return EncryptedStore.decryptString(stored, LABEL)
    }

    private fun save(list: List<String>) {
        val json = JSONArray(list.takeLast(MAX_FACTS)).toString()
        prefs.edit().putString(ENCRYPTED, EncryptedStore.encryptString(json, LABEL)).remove(PLAIN).apply()
    }

    private fun key(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Saves [fact], replacing an identical one; the oldest facts drop off past [MAX_FACTS]. */
    fun remember(fact: String): String {
        val clean = fact.trim().take(MAX_LENGTH)
        require(clean.isNotEmpty()) { "Nothing to remember" }
        save(stored().filter { key(it) != key(clean) } + clean)
        return "Recordado: $clean"
    }

    /** Removes facts that match [fact] loosely. */
    fun forget(fact: String): String {
        val wanted = key(fact)
        val all = stored()
        val kept = all.filterNot { key(it).contains(wanted) || wanted.contains(key(it)) }
        val removed = all.size - kept.size
        save(kept)
        return if (removed > 0) "Olvidado ($removed)" else "No había nada guardado sobre «$fact»"
    }

    fun clear() = prefs.edit().remove(ENCRYPTED).remove(PLAIN).apply()

    companion object {
        private const val MAX_FACTS = 80
        private const val MAX_LENGTH = 200
        private const val PLAIN = "facts"
        private const val ENCRYPTED = "facts_enc"
        private const val LABEL = "ancla_memory/facts"
    }
}
