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
import android.util.Log
import org.isoron.uhabits.ancla.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The running conversation with Dexter, shared by every place the user talks to it (the app,
 * "Oye Dexter", the assistant gesture) and kept across restarts, so Dexter always knows what was
 * said before. Only the words are kept (the user's and Dexter's, plus what it did), encrypted on
 * the phone in no-backup storage. Never read or written while the phone is locked.
 */
class DexterConversation(private val file: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "dexter_conversation.json"))

    class Entry(val seq: Long, val time: Long, val user: Boolean, val text: String)

    /** The last [MAX_ENTRIES] messages of the last [MAX_AGE_DAYS] days, oldest first. */
    fun entries(): List<Entry> = synchronized(LOCK) { load() }

    /** Adds a message and returns its number. */
    fun add(user: Boolean, text: String): Long = synchronized(LOCK) {
        val all = load()
        val seq = (all.lastOrNull()?.seq ?: 0L) + 1
        save(all + Entry(seq, System.currentTimeMillis(), user, text.trim().take(MAX_LENGTH)))
        seq
    }

    /** Replaces the text of message [seq] (a voice message, once it has been transcribed). */
    fun update(seq: Long, text: String) = synchronized(LOCK) {
        save(load().map { if (it.seq == seq) Entry(it.seq, it.time, it.user, text.trim().take(MAX_LENGTH)) else it })
    }

    fun clear() = synchronized(LOCK) { file.delete() }

    private fun load(): List<Entry> {
        val text = runCatching { EncryptedStore.readText(file) }.getOrElse {
            Log.w(TAG, "conversation unreadable: ${it.javaClass.simpleName}")
            null
        } ?: return emptyList()
        val arr = runCatching { JSONArray(text) }.getOrDefault(JSONArray())
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * DAY
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Entry(it.getLong("seq"), it.getLong("time"), it.getBoolean("user"), it.getString("text")) }
            .filter { it.time >= cutoff }
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.takeLast(MAX_ENTRIES).forEach {
            arr.put(JSONObject().put("seq", it.seq).put("time", it.time).put("user", it.user).put("text", it.text))
        }
        EncryptedStore.writeText(file, arr.toString())
    }

    companion object {
        private const val TAG = "DexterConversation"
        private val LOCK = Any()
        private const val MAX_ENTRIES = 60
        private const val MAX_AGE_DAYS = 30L
        private const val MAX_LENGTH = 1500
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}

/**
 * One agent's view of the shared [DexterConversation]: before each message it hands the agent
 * whatever it hasn't seen yet (earlier sessions, or what was said elsewhere in the meantime), and
 * after each turn it records what the user said and what Dexter answered and did.
 */
internal class ConversationSync {
    private var lastSeen = 0L
    private val own = mutableSetOf<Long>()

    /** The messages this agent hasn't seen, as a context block for the next message, or null. */
    fun catchUp(log: DexterConversation): String? {
        val all = log.entries()
        val fresh = all.filter { it.seq > lastSeen && it.seq !in own }
        lastSeen = maxOf(lastSeen, all.lastOrNull()?.seq ?: 0L)
        if (fresh.isEmpty()) return null
        val format = SimpleDateFormat("EEE d MMM HH:mm", Locale("es"))
        val lines = fresh.joinToString("\n") { e ->
            "[${format.format(Date(e.time))}] ${if (e.user) "Usuario" else "Dexter"}: ${e.text}"
        }
        return "$HEADER\n$lines\n$FOOTER"
    }

    fun record(log: DexterConversation, user: Boolean, text: String): Long? {
        if (text.isBlank()) return null
        val seq = runCatching { log.add(user, text) }.getOrNull() ?: return null
        own += seq
        lastSeen = maxOf(lastSeen, seq)
        return seq
    }

    /** Runs one turn of an agent: catches up, then records the user's words and Dexter's reply. */
    fun <T> turn(
        log: DexterConversation?,
        userText: String,
        onEvent: (AgentEvent) -> Unit,
        body: (context: String?, onEvent: (AgentEvent) -> Unit) -> T
    ): Pair<T, Long?> {
        if (log == null) return body(null, onEvent) to null
        val context = catchUp(log)
        val userSeq = record(log, true, userText)
        val said = StringBuilder()
        try {
            val result = body(context) { event ->
                when (event) {
                    is AgentEvent.Reply -> said.append(event.text)
                    is AgentEvent.Action -> said.append("(").append(event.description).append(") ")
                }
                onEvent(event)
            }
            return result to userSeq
        } finally {
            record(log, false, said.toString().trim())
        }
    }

    companion object {
        const val HEADER = "[Conversación anterior contigo, en la app o con «Oye Dexter», la más reciente al final. Es historial para que tengas contexto; no son pedidos nuevos.]"
        const val FOOTER = "[Fin del historial. Mensaje nuevo del usuario:]"
    }
}
