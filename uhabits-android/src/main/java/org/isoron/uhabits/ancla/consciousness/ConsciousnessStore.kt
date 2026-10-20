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

package org.isoron.uhabits.ancla.consciousness

import android.content.Context
import org.isoron.uhabits.ancla.EncryptedStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** The six dimensions of the user's wheel, in the order drawn. */
enum class Dimension(val label: String) {
    ATTENTION("Atención"), EMOTIONS("Emociones"), BODY("Cuerpo"), VALUES("Valores"), RELATIONSHIPS("Relaciones"), PURPOSE("Propósito")
}

/**
 * The mood meter (Yale's RULER): energy by pleasantness. The user names a precise emotion inside
 * one quadrant, because labelling a feeling precisely already eases it.
 */
enum class Quadrant(val label: String, val emotions: List<String>) {
    HIGH_UNPLEASANT("Mucha energía · desagradable", listOf("Ansioso", "Frustrado", "Presionado", "Enojado", "Inquieto")),
    HIGH_PLEASANT("Mucha energía · agradable", listOf("Entusiasmado", "Inspirado", "Orgulloso", "Alegre", "Motivado")),
    LOW_UNPLEASANT("Poca energía · desagradable", listOf("Cansado", "Triste", "Desanimado", "Solo", "Aburrido")),
    LOW_PLEASANT("Poca energía · agradable", listOf("Tranquilo", "Agradecido", "En paz", "Satisfecho", "Relajado"));

    companion object {
        fun of(emotion: String): Quadrant? {
            val k = emotion.trim().lowercase()
            return entries.firstOrNull { q -> q.emotions.any { it.lowercase() == k } }
        }
    }
}

/** Where in the body a feeling is noticed. */
val BODY_PLACES = listOf("Pecho", "Hombros", "Estómago", "Mandíbula", "Cabeza", "Garganta", "No lo noto")

data class CheckIn(val id: String, val time: Long, val emotion: String, val quadrant: Quadrant?, val body: List<String>, val note: String)
data class Reflection(val day: LocalDate, val question: String, val answer: String)
data class Examen(val day: LocalDate, val gratitude: String, val wentWell: String, val reacted: String, val tomorrow: String)

/**
 * Consciencia: the user's pauses (an emotion named precisely and where it is felt), their weekly
 * wheel of six dimensions as they see themselves, the answer to the day's question and the night
 * examen. It is a mirror, never a score: nothing here ranks the user. Encrypted like the rest.
 */
class ConsciousnessStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "consciousness.json"))

    private data class Data(
        val checkIns: List<CheckIn> = emptyList(),
        val wheels: Map<LocalDate, Map<Dimension, Int>> = emptyMap(),
        val reflections: List<Reflection> = emptyList(),
        val examens: List<Examen> = emptyList()
    )

    fun checkIns(): List<CheckIn> = synchronized(LOCK) { load().checkIns }.sortedByDescending { it.time }

    fun addCheckIn(emotion: String, body: List<String> = emptyList(), note: String = "", time: Long = System.currentTimeMillis()): CheckIn = synchronized(LOCK) {
        val clean = emotion.trim()
        require(clean.isNotEmpty()) { "¿Qué sientes? Falta la emoción" }
        val c = CheckIn(UUID.randomUUID().toString(), time, clean.take(40), Quadrant.of(clean), body.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6), note.trim().take(500))
        val d = load()
        save(d.copy(checkIns = (d.checkIns + c).takeLast(MAX_ITEMS)))
        c
    }

    /** The week (from Monday) this day belongs to. */
    fun weekOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The wheel of [week]'s Monday, or the latest one before it. */
    fun wheel(day: LocalDate = LocalDate.now()): Map<Dimension, Int> {
        val wheels = synchronized(LOCK) { load().wheels }
        return wheels.filterKeys { it <= weekOf(day) }.maxByOrNull { it.key }?.value ?: emptyMap()
    }

    fun setWheel(ratings: Map<Dimension, Int>, day: LocalDate = LocalDate.now()) = synchronized(LOCK) {
        require(ratings.values.all { it in 1..5 }) { "Cada dimensión va de 1 a 5" }
        val d = load()
        val week = weekOf(day)
        save(d.copy(wheels = d.wheels + (week to ((d.wheels[week] ?: emptyMap()) + ratings))))
    }

    fun reflection(day: LocalDate = LocalDate.now()): Reflection? = synchronized(LOCK) { load().reflections }.lastOrNull { it.day == day }

    fun reflections(): List<Reflection> = synchronized(LOCK) { load().reflections }.sortedByDescending { it.day }

    fun saveReflection(answer: String, day: LocalDate = LocalDate.now(), question: String = Questions.of(day)): Reflection = synchronized(LOCK) {
        val clean = answer.trim()
        require(clean.isNotEmpty()) { "La respuesta está vacía" }
        val r = Reflection(day, question, clean.take(2000))
        val d = load()
        save(d.copy(reflections = (d.reflections.filter { it.day != day } + r).takeLast(MAX_ITEMS)))
        r
    }

    fun examen(day: LocalDate = LocalDate.now()): Examen? = synchronized(LOCK) { load().examens }.lastOrNull { it.day == day }

    fun examens(): List<Examen> = synchronized(LOCK) { load().examens }.sortedByDescending { it.day }

    /** Saves tonight's examen; null fields keep what was already said tonight. */
    fun saveExamen(gratitude: String?, wentWell: String?, reacted: String?, tomorrow: String?, day: LocalDate = LocalDate.now()): Examen = synchronized(LOCK) {
        val d = load()
        val old = d.examens.lastOrNull { it.day == day } ?: Examen(day, "", "", "", "")
        fun pick(new: String?, was: String) = new?.trim()?.take(1000) ?: was
        val e = Examen(day, pick(gratitude, old.gratitude), pick(wentWell, old.wentWell), pick(reacted, old.reacted), pick(tomorrow, old.tomorrow))
        save(d.copy(examens = (d.examens.filter { it.day != day } + e).takeLast(MAX_ITEMS)))
        e
    }

    /** Days in a row, up to today, with at least one pause. */
    fun pauseStreak(today: LocalDate = LocalDate.now()): Int {
        val days = checkIns().map { java.time.Instant.ofEpochMilli(it.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.toSet()
        var n = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    /**
     * What the user's own records show over the last [days] days, in plain words: the quadrant and
     * body place that come up most and what they said they reacted to. Facts, never judgements.
     */
    fun patterns(today: LocalDate = LocalDate.now(), days: Long = 14): List<String> {
        val zone = java.time.ZoneId.systemDefault()
        val since = today.minusDays(days - 1)
        val recent = checkIns().filter { java.time.Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() >= since }
        val out = mutableListOf<String>()
        if (recent.size >= 3) {
            recent.mapNotNull { it.quadrant }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.let { (q, n) ->
                out += "En tus últimas ${recent.size} pausas, $n fueron de «${q.label.lowercase()}»."
            }
            recent.flatMap { it.body }.filter { it != "No lo noto" }.groupingBy { it }.eachCount().maxByOrNull { it.value }
                ?.takeIf { it.value >= 2 }?.let { (place, n) -> out += "Donde más lo notas: $place ($n veces)." }
        }
        val reacted = examens().filter { it.day >= since && it.reacted.isNotBlank() }
        if (reacted.size >= 2) out += "En ${reacted.size} exámenes reconociste momentos en que reaccionaste en vez de elegir."
        return out
    }

    /** For Dexter, in a few lines. */
    fun describe(today: LocalDate = LocalDate.now()): String = buildString {
        val wheel = wheel(today)
        append("Pregunta de hoy: ${Questions.of(today)}")
        reflection(today)?.let { append(" (ya respondió: ${it.answer.take(160)})") }
        appendLine()
        if (wheel.isNotEmpty()) appendLine("Su rueda esta semana (1-5, cómo se ve): " + Dimension.entries.filter { it in wheel }.joinToString { "${it.label} ${wheel[it]}" })
        checkIns().firstOrNull()?.let { appendLine("Última pausa: ${it.emotion}" + (if (it.body.isNotEmpty()) ", en ${it.body.joinToString()}" else "")) }
        appendLine("Racha de pausas: ${pauseStreak(today)} días")
        examen(today)?.let { appendLine("Examen de hoy hecho") } ?: appendLine("Examen de hoy pendiente")
        patterns(today).forEach { appendLine("Patrón: $it") }
    }.trim()

    private fun load(): Data {
        val text = EncryptedStore.readText(file) ?: return Data()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return Data()
        fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
        fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
        val checkIns = json.optJSONArray("checkIns").objects().map {
            val e = it.getString("emotion")
            CheckIn(it.getString("id"), it.getLong("time"), e, Quadrant.of(e), it.optJSONArray("body").strings(), it.optString("note"))
        }
        val w = json.optJSONObject("wheels") ?: JSONObject()
        val wheels = w.keys().asSequence().associate { k ->
            val o = w.getJSONObject(k)
            LocalDate.parse(k) to o.keys().asSequence().mapNotNull { name -> runCatching { Dimension.valueOf(name) to o.getInt(name) }.getOrNull() }.toMap()
        }
        val reflections = json.optJSONArray("reflections").objects().map { Reflection(LocalDate.parse(it.getString("day")), it.optString("question"), it.getString("answer")) }
        val examens = json.optJSONArray("examens").objects().map {
            Examen(LocalDate.parse(it.getString("day")), it.optString("gratitude"), it.optString("wentWell"), it.optString("reacted"), it.optString("tomorrow"))
        }
        return Data(checkIns, wheels, reflections, examens)
    }

    private fun save(d: Data) {
        val json = JSONObject()
            .put("checkIns", JSONArray(d.checkIns.map { JSONObject().put("id", it.id).put("time", it.time).put("emotion", it.emotion).put("body", JSONArray(it.body)).put("note", it.note) }))
            .put("wheels", JSONObject().apply { d.wheels.forEach { (week, r) -> put(week.toString(), JSONObject(r.mapKeys { it.key.name })) } })
            .put("reflections", JSONArray(d.reflections.map { JSONObject().put("day", it.day.toString()).put("question", it.question).put("answer", it.answer) }))
            .put("examens", JSONArray(d.examens.map { JSONObject().put("day", it.day.toString()).put("gratitude", it.gratitude).put("wentWell", it.wentWell).put("reacted", it.reacted).put("tomorrow", it.tomorrow) }))
        EncryptedStore.writeText(file, json.toString())
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_ITEMS = 2000
    }
}

/** One reflective question a day, the same all day, each one before any repeats. */
object Questions {
    val ALL = listOf(
        "¿Qué hiciste hoy en piloto automático?",
        "¿En qué momento de hoy estuviste realmente presente?",
        "¿Qué emoción se repitió hoy, y qué te quería decir?",
        "¿Qué pensamiento te creíste hoy sin cuestionarlo?",
        "¿Dónde sentiste hoy tensión en el cuerpo?",
        "¿Qué hiciste hoy que va de acuerdo con tus valores?",
        "¿A quién escuchaste de verdad hoy?",
        "¿Qué evitaste hoy, y por qué?",
        "¿Qué dependía de ti hoy y qué no?",
        "¿Qué te hizo sentir vivo hoy?",
        "¿Qué historia te contaste sobre ti mismo hoy?",
        "Si hoy fuera tu último día del año, ¿qué cambiarías de él?",
        "¿Qué pequeño acto de hoy te acerca a quien quieres ser?",
        "¿Qué notaste hoy que normalmente pasas por alto?",
        "¿Cuándo elegiste hoy en vez de reaccionar?",
        "¿Qué necesitabas hoy y no te diste?",
        "¿Por qué estás agradecido hoy, en concreto?",
        "¿Qué te enseñó hoy alguien con quien no estás de acuerdo?",
        "¿Qué hiciste hoy solo para ser visto?",
        "¿Qué parte de tu día querrías repetir mañana?",
        "¿Qué miedo apareció hoy, y qué hiciste con él?"
    )

    fun of(day: LocalDate = LocalDate.now()): String {
        val n = ALL.size
        val step = (5 until 5 + n).first { gcd(it, n) == 1 }
        return ALL[Math.floorMod(day.toEpochDay() * step, n.toLong()).toInt()]
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
