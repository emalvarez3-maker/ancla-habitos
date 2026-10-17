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

package org.isoron.uhabits.ancla.reading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class StudyTopic(val name: String, val weeklyGoalMinutes: Int, val created: Long)

data class StudySession(
    val id: String,
    val topic: String,
    val start: Long,
    val minutes: Int,
    /** The note created for this session, where its summary lives. */
    val noteId: String
)

/** Minutes studied per topic, per day and overall, for the charts and for Dexter. */
data class StudyStats(
    /** Last 7 days, oldest first: day label to minutes per topic. */
    val week: List<Pair<String, Map<String, Int>>>,
    val weekTotal: Int,
    val byTopic: List<Pair<String, Int>>,
    val streakDays: Int,
    val totalSessions: Int
)

/**
 * Study topics and timed study sessions. The running session's start time is saved, so the timer
 * keeps counting if the app is closed. Every finished session becomes a note that links to its
 * topic's note, so studying shows up in the notes graph next to books and ideas.
 */
class StudyStore(private val context: Context) {
    private val file = File(context.filesDir, "study.json")

    private var stamp = Long.MIN_VALUE

    // The screen and Dexter each hold a StudyStore: reads reload the file when the other changed it,
    // and changes run under one process-wide lock.
    var topics: List<StudyTopic> = emptyList()
        get() = synchronized(LOCK) { fresh(); field }
        private set
    var sessions: List<StudySession> = emptyList()
        get() = synchronized(LOCK) { fresh(); field }
        private set

    /** Topic and start time of the session in progress, or null. */
    var active: Pair<String, Long>? = null
        get() = synchronized(LOCK) { fresh(); field }
        private set

    private fun fresh() {
        val s = if (file.exists()) file.lastModified() * 31 + file.length() else 0L
        if (s == stamp) return
        stamp = s
        load()
    }

    private fun load() {
        topics = emptyList()
        sessions = emptyList()
        active = null
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            topics = json.optJSONArray("topics").objects().map { StudyTopic(it.getString("name"), it.optInt("goal", 0), it.optLong("created")) }
            sessions = json.optJSONArray("sessions").objects().map {
                StudySession(it.getString("id"), it.getString("topic"), it.getLong("start"), it.getInt("minutes"), it.optString("note"))
            }
            json.optJSONObject("active")?.let { active = it.getString("topic") to it.getLong("start") }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun persist() {
        val json = JSONObject()
            .put("topics", JSONArray(topics.map { JSONObject().put("name", it.name).put("goal", it.weeklyGoalMinutes).put("created", it.created) }))
            .put(
                "sessions",
                JSONArray(sessions.map { JSONObject().put("id", it.id).put("topic", it.topic).put("start", it.start).put("minutes", it.minutes).put("note", it.noteId) })
            )
        active?.let { (topic, start) -> json.put("active", JSONObject().put("topic", topic).put("start", start)) }
        val tmp = File(file.parentFile, "study.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
        stamp = file.lastModified() * 31 + file.length()
    }

    fun topic(name: String): StudyTopic? {
        val k = IdeaGraph.key(name)
        return topics.firstOrNull { IdeaGraph.key(it.name) == k } ?: topics.firstOrNull { IdeaGraph.key(it.name).contains(k) && k.length >= 3 }
    }

    /** Adds a topic (or updates its weekly goal) and makes sure it has a note to link to. */
    fun addTopic(name: String, weeklyGoalMinutes: Int? = null): StudyTopic = synchronized(LOCK) {
        fresh()
        val existing = topic(name)?.takeIf { IdeaGraph.key(it.name) == IdeaGraph.key(name) }
        val t = existing?.copy(weeklyGoalMinutes = weeklyGoalMinutes ?: existing.weeklyGoalMinutes)
            ?: StudyTopic(name.trim(), weeklyGoalMinutes ?: 0, System.currentTimeMillis())
        topics = topics.filter { it.name != t.name } + t
        persist()
        IdeaGraph(context).create(t.name)
        return t
    }

    fun removeTopic(name: String) = synchronized(LOCK) {
        fresh()
        topics = topics.filter { it.name != name }
        if (active?.first == name) active = null
        persist()
    }

    /** Starts timing [topic] (creating it if new); a session already running is finished first. */
    fun start(topic: String, now: Long = System.currentTimeMillis()): StudyTopic = synchronized(LOCK) {
        fresh()
        if (active != null) stop(now)
        val t = topic(topic) ?: addTopic(topic)
        active = t.name to now
        persist()
        return t
    }

    /**
     * Finishes the running session and writes its note. Sessions shorter than a minute are
     * discarded. Returns the saved session, or null.
     */
    fun stop(now: Long = System.currentTimeMillis(), summary: String = ""): StudySession? = synchronized(LOCK) {
        fresh()
        val (topic, start) = active ?: return null
        active = null
        val minutes = ((now - start) / 60_000.0).roundToInt()
        if (minutes < 1) {
            persist()
            return null
        }
        val label = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(start))
        val body = buildString {
            appendLine("Tema: [[$topic]]")
            appendLine("Duración: $minutes min")
            if (summary.isNotBlank()) {
                appendLine()
                appendLine(summary.trim())
            }
        }
        val note = IdeaGraph(context).create("Estudio · $topic · $label", IdeaKind.STUDY, body)
        val session = StudySession(UUID.randomUUID().toString(), topic, start, minutes, note.id)
        sessions = sessions + session
        persist()
        return session
    }

    /** Throws the running session away without saving it. */
    fun cancel() = synchronized(LOCK) {
        fresh()
        active = null
        persist()
    }

    /** Minutes of the running session so far. */
    fun elapsedMinutes(now: Long = System.currentTimeMillis()) = active?.let { ((now - it.second) / 60_000).toInt() } ?: 0

    fun stats(now: Long = System.currentTimeMillis()): StudyStats {
        val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val labelFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val days = (6 downTo 0).map { back ->
            val c = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back) }
            dayFmt.format(c.time) to labelFmt.format(c.time).take(3)
        }
        val byDay = sessions.groupBy { dayFmt.format(Date(it.start)) }
        val week = days.map { (key, label) ->
            label to (byDay[key].orEmpty().groupBy { it.topic }.mapValues { (_, s) -> s.sumOf { it.minutes } })
        }
        val weekKeys = days.map { it.first }.toSet()
        val weekSessions = sessions.filter { dayFmt.format(Date(it.start)) in weekKeys }
        val byTopic = weekSessions.groupBy { it.topic }.map { (t, s) -> t to s.sumOf { it.minutes } }.sortedByDescending { it.second }

        // Streak: consecutive days with study, counting today only if already studied.
        var streak = 0
        val c = (cal.clone() as Calendar)
        if (byDay[dayFmt.format(c.time)].isNullOrEmpty()) c.add(Calendar.DAY_OF_YEAR, -1)
        while (!byDay[dayFmt.format(c.time)].isNullOrEmpty()) {
            streak++
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return StudyStats(week, weekSessions.sumOf { it.minutes }, byTopic, streak, sessions.size)
    }

    private companion object {
        val LOCK = Any()
    }

    /** Plain-text summary for Dexter. */
    fun describe(): String {
        val s = stats()
        return buildString {
            active?.let { appendLine("Sesión en curso: ${it.first}, ${elapsedMinutes()} min.") }
            appendLine("Estudio esta semana: ${s.weekTotal} min, racha de ${s.streakDays} días, ${s.totalSessions} sesiones en total.")
            if (topics.isNotEmpty()) {
                appendLine("Temas: " + topics.joinToString { t ->
                    val done = s.byTopic.firstOrNull { it.first == t.name }?.second ?: 0
                    t.name + " ($done min" + (if (t.weeklyGoalMinutes > 0) " de ${t.weeklyGoalMinutes} min/semana" else "") + ")"
                })
            }
        }.trim()
    }
}
