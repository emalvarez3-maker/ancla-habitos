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

package org.isoron.uhabits.ancla.finance

import android.content.Context
import org.isoron.uhabits.ancla.EncryptedStore
import org.isoron.uhabits.ancla.StoreCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/** One movement of money: an expense or an income, in cents, on a day. */
data class Movement(
    val id: String,
    val cents: Long,
    val income: Boolean,
    val category: String,
    val note: String,
    val day: LocalDate,
    val created: Long = 0
)

/** A month at a glance: what came in, what went out, and where it went. */
data class MonthSummary(
    val month: YearMonth,
    val spentCents: Long,
    val incomeCents: Long,
    val byCategory: List<Pair<String, Long>>,
    val budgets: Map<String, Long>
) {
    val balanceCents get() = incomeCents - spentCents
}

/** How long a stretch of money is looked at: one day, a week (from Monday), a month or a year. */
enum class Period(val label: String) {
    DAY("Día"), WEEK("Semana"), MONTH("Mes"), YEAR("Año");

    /** The stretch of this kind that contains [day], first and last day included. */
    fun range(day: LocalDate): ClosedRange<LocalDate> = when (this) {
        DAY -> day..day
        WEEK -> day.with(java.time.DayOfWeek.MONDAY).let { it..it.plusDays(6) }
        MONTH -> day.withDayOfMonth(1).let { it..it.plusMonths(1).minusDays(1) }
        YEAR -> day.withDayOfYear(1).let { it..it.plusYears(1).minusDays(1) }
    }

    /** The same stretch, [n] steps later (or earlier, with a negative [n]). */
    fun shift(day: LocalDate, n: Long): LocalDate = when (this) {
        DAY -> day.plusDays(n)
        WEEK -> day.plusWeeks(n)
        MONTH -> day.plusMonths(n)
        YEAR -> day.plusYears(n)
    }

    companion object {
        fun of(name: String?): Period = when (FinanceStore.normalize(name.orEmpty())) {
            "day", "dia", "hoy" -> DAY
            "week", "semana" -> WEEK
            "year", "ano", "anual" -> YEAR
            else -> MONTH
        }
    }
}

/** Any stretch of time at a glance: what came in, what went out, where it went and what was saved. */
data class PeriodSummary(
    val period: Period,
    val from: LocalDate,
    val to: LocalDate,
    val spentCents: Long,
    val incomeCents: Long,
    val byCategory: List<Pair<String, Long>>
) {
    /** What was left: income minus spending (negative when more went out than came in). */
    val savedCents get() = incomeCents - spentCents

    /** The share of income that was kept, 0 to 100 (null with no income). */
    val savingsRate get() = if (incomeCents > 0) (savedCents * 100 / incomeCents).toInt() else null
}

/** Something the user is saving for: how much, how much is already set aside, and by when. */
data class SavingsGoal(val id: String, val name: String, val targetCents: Long, val savedCents: Long, val by: YearMonth?) {
    val done get() = savedCents >= targetCents
    val progress get() = (savedCents.toFloat() / targetCents.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** What to set aside each month from [now] to reach it in time (null without a date, or when done). */
    fun monthlyNeeded(now: YearMonth = YearMonth.now()): Long? {
        val end = by ?: return null
        if (done) return null
        val months = (java.time.temporal.ChronoUnit.MONTHS.between(now, end) + 1).coerceAtLeast(1)
        val left = targetCents - savedCents
        return (left + months - 1) / months
    }
}

/**
 * Dexter's money manager: every expense and income the user tells Dexter or types in, sorted into
 * categories, with monthly budgets per category. Kept encrypted on the phone like notes and goals.
 * Amounts are whole cents, so sums never drift.
 */
class FinanceStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "finance.json"))

    fun movements(): List<Movement> = synchronized(LOCK) { load().movements }.sortedWith(compareByDescending<Movement> { it.day }.thenByDescending { it.created })

    fun movements(month: YearMonth): List<Movement> = movements().filter { YearMonth.from(it.day) == month }

    fun budgets(): Map<String, Long> = synchronized(LOCK) { load().budgets }

    /** Adds a movement. Without a [category], one is guessed from the [note]. Returns the saved movement. */
    fun add(cents: Long, income: Boolean, category: String?, note: String, day: LocalDate = LocalDate.now()): Movement = synchronized(LOCK) {
        require(cents > 0) { "El monto debe ser mayor que cero" }
        require(cents <= MAX_CENTS) { "Ese monto es demasiado grande" }
        val data = load()
        // Strictly after the newest one, so "borra eso" always finds this entry even when two are
        // added within the same millisecond.
        val created = maxOf(System.currentTimeMillis(), (data.movements.maxOfOrNull { it.created } ?: 0L) + 1)
        val m = Movement(
            UUID.randomUUID().toString(), cents, income,
            categoryFor(category, note, income), note.trim().take(200), day, created
        )
        save(data.copy(movements = data.movements + m))
        m
    }

    fun update(id: String, cents: Long? = null, income: Boolean? = null, category: String? = null, note: String? = null, day: LocalDate? = null): Movement? =
        synchronized(LOCK) {
            val data = load()
            val old = data.movements.firstOrNull { it.id == id } ?: return@synchronized null
            cents?.let { require(it in 1..MAX_CENTS) { "Monto inválido" } }
            val m = old.copy(
                cents = cents ?: old.cents,
                income = income ?: old.income,
                category = category?.let { canonical(it) } ?: old.category,
                note = note?.trim()?.take(200) ?: old.note,
                day = day ?: old.day
            )
            save(data.copy(movements = data.movements.map { if (it.id == id) m else it }))
            m
        }

    fun remove(id: String): Movement? = synchronized(LOCK) {
        val data = load()
        val m = data.movements.firstOrNull { it.id == id } ?: return@synchronized null
        save(data.copy(movements = data.movements.filter { it.id != id }))
        m
    }

    /** The movement added most recently ("borra el último gasto"). */
    fun last(): Movement? = movements().maxByOrNull { it.created }

    /** Sets (or with 0, removes) the monthly budget of [category]. */
    fun setBudget(category: String, cents: Long) = synchronized(LOCK) {
        require(cents >= 0) { "El presupuesto no puede ser negativo" }
        val data = load()
        val name = canonical(category)
        save(data.copy(budgets = if (cents == 0L) data.budgets - name else data.budgets + (name to cents)))
        name
    }

    fun summary(month: YearMonth = YearMonth.now()): MonthSummary {
        val list = movements(month)
        val spent = list.filter { !it.income }
        return MonthSummary(
            month,
            spent.sumOf { it.cents },
            list.filter { it.income }.sumOf { it.cents },
            spent.groupBy { it.category }.map { (k, v) -> k to v.sumOf { it.cents } }.sortedByDescending { it.second },
            budgets()
        )
    }

    /** Any day, week, month or year: the one of [period] that contains [day]. */
    fun summary(period: Period, day: LocalDate = LocalDate.now()): PeriodSummary {
        val range = period.range(day)
        val list = movements().filter { it.day in range }
        val spent = list.filter { !it.income }
        return PeriodSummary(
            period, range.start, range.endInclusive,
            spent.sumOf { it.cents },
            list.filter { it.income }.sumOf { it.cents },
            spent.groupBy { it.category }.map { (k, v) -> k to v.sumOf { it.cents } }.sortedByDescending { it.second }
        )
    }

    fun movements(range: ClosedRange<LocalDate>): List<Movement> = movements().filter { it.day in range }

    /** What was saved each of the last [months] months, oldest first (income minus spending). */
    fun monthlySavings(months: Int = 6, until: YearMonth = YearMonth.now()): List<Pair<YearMonth, Long>> {
        val all = movements()
        return (months - 1 downTo 0).map { back ->
            val m = until.minusMonths(back.toLong())
            m to all.filter { YearMonth.from(it.day) == m }.sumOf { if (it.income) it.cents else -it.cents }
        }
    }

    /**
     * At this month's pace, what will have been spent by its end ([today] counted as a full day).
     * Null early in the month, when the pace says little.
     */
    fun monthProjection(today: LocalDate = LocalDate.now()): Long? {
        if (today.dayOfMonth < 5) return null
        val spent = summary(Period.MONTH, today).spentCents
        return spent * today.lengthOfMonth() / today.dayOfMonth
    }

    // --- Savings goals ---

    fun goals(): List<SavingsGoal> = synchronized(LOCK) { load().goals }

    /** A new goal ("un viaje a Galápagos, $1.200, para diciembre"). */
    fun addGoal(name: String, targetCents: Long, by: YearMonth? = null): SavingsGoal = synchronized(LOCK) {
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "¿Para qué es el ahorro?" }
        require(targetCents in 1..MAX_CENTS) { "La meta necesita un monto" }
        val data = load()
        val g = SavingsGoal(UUID.randomUUID().toString(), clean.replaceFirstChar { it.uppercase() }, targetCents, 0, by)
        save(data.copy(goals = data.goals + g))
        g
    }

    /** Sets [cents] aside for the goal named [name] (negative takes it back out). */
    fun contribute(name: String, cents: Long): SavingsGoal? = synchronized(LOCK) {
        val data = load()
        val g = findGoal(data.goals, name) ?: return@synchronized null
        val updated = g.copy(savedCents = (g.savedCents + cents).coerceIn(0, MAX_CENTS))
        save(data.copy(goals = data.goals.map { if (it.id == g.id) updated else it }))
        updated
    }

    fun removeGoal(name: String): SavingsGoal? = synchronized(LOCK) {
        val data = load()
        val g = findGoal(data.goals, name) ?: return@synchronized null
        save(data.copy(goals = data.goals.filter { it.id != g.id }))
        g
    }

    private fun findGoal(goals: List<SavingsGoal>, name: String): SavingsGoal? {
        val k = normalize(name)
        return goals.firstOrNull { normalize(it.name) == k } ?: goals.firstOrNull { normalize(it.name).contains(k) || k.contains(normalize(it.name)) }
    }

    // --- Storage ---

    private data class Data(val movements: List<Movement>, val budgets: Map<String, Long>, val goals: List<SavingsGoal> = emptyList())

    /** Changes whenever the movements or budgets may have changed; cheap, for the Finanzas screen's poll. */
    fun version(): Long = synchronized(LOCK) { StoreCache.version(file, ::parse) }

    private fun load(): Data = StoreCache.read(file, ::parse)

    private fun parse(text: String?): Data {
        text ?: return Data(emptyList(), emptyMap())
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return Data(emptyList(), emptyMap())
        val arr = json.optJSONArray("movements") ?: JSONArray()
        val movements = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Movement(
                it.getString("id"), it.getLong("cents"), it.optBoolean("income"), it.getString("category"),
                it.optString("note"), LocalDate.parse(it.getString("day")), it.optLong("created")
            )
        }
        val b = json.optJSONObject("budgets") ?: JSONObject()
        val budgets = b.keys().asSequence().associateWith { b.getLong(it) }
        val g = json.optJSONArray("goals") ?: JSONArray()
        val goals = (0 until g.length()).map { g.getJSONObject(it) }.map {
            SavingsGoal(
                it.getString("id"), it.getString("name"), it.getLong("target"), it.optLong("saved"),
                it.optString("by").takeIf { s -> s.isNotEmpty() }?.let { s -> YearMonth.parse(s) }
            )
        }
        return Data(movements, budgets, goals)
    }

    private fun save(data: Data) {
        val json = JSONObject()
            .put(
                "movements",
                JSONArray(
                    data.movements.map {
                        JSONObject().put("id", it.id).put("cents", it.cents).put("income", it.income).put("category", it.category)
                            .put("note", it.note).put("day", it.day.toString()).put("created", it.created)
                    }
                )
            )
            .put("budgets", JSONObject(data.budgets))
            .put(
                "goals",
                JSONArray(data.goals.map { JSONObject().put("id", it.id).put("name", it.name).put("target", it.targetCents).put("saved", it.savedCents).put("by", it.by?.toString() ?: "") })
            )
        EncryptedStore.writeText(file, json.toString())
        StoreCache.wrote(file, Data(data.movements.toList(), data.budgets.toMap(), data.goals.toList()))
    }

    companion object {
        private val LOCK = Any()
        const val MAX_CENTS = 100_000_000_00L

        /** Spending categories, in the order they are offered. */
        val EXPENSE_CATEGORIES = listOf(
            "Comida", "Transporte", "Casa", "Servicios", "Salud", "Ocio", "Compras", "Educación", "Suscripciones", "Otros"
        )
        val INCOME_CATEGORIES = listOf("Sueldo", "Freelance", "Ventas", "Regalos", "Otros ingresos")

        private val KEYWORDS = mapOf(
            "Comida" to listOf(
                "almuerzo", "desayuno", "cena", "comida", "restaurante", "cafe", "supermercado", "super", "mercado", "pizza", "hamburguesa",
                "pedidosya", "rappi", "uber eats", "snack", "pan", "frutas", "bebida", "helado", "sushi", "pollo"
            ),
            "Transporte" to listOf("uber", "taxi", "bus", "gasolina", "combustible", "indriver", "ktaxi", "metro", "pasaje", "parqueadero", "peaje", "cabify", "didi"),
            "Casa" to listOf("arriendo", "alquiler", "renta", "muebles", "limpieza", "reparacion", "hogar"),
            "Servicios" to listOf("luz", "agua", "internet", "celular", "plan", "telefono", "gas", "electricidad", "recarga"),
            "Salud" to listOf("farmacia", "medico", "doctor", "medicina", "dentista", "hospital", "seguro medico", "gimnasio", "gym", "vitaminas"),
            "Ocio" to listOf("cine", "bar", "salida", "fiesta", "concierto", "juego", "videojuego", "viaje", "hotel", "discoteca", "entrada"),
            "Compras" to listOf("ropa", "zapatos", "amazon", "regalo", "tienda", "shein", "mercadolibre", "electronica", "accesorios"),
            "Educación" to listOf("curso", "libro", "universidad", "matricula", "colegiatura", "clase", "udemy", "coursera", "cuaderno", "copias"),
            "Suscripciones" to listOf("netflix", "spotify", "youtube premium", "disney", "hbo", "prime", "icloud", "suscripcion", "chatgpt", "claude")
        )
        private val INCOME_KEYWORDS = mapOf(
            "Sueldo" to listOf("sueldo", "salario", "pago mensual", "nomina", "quincena"),
            "Freelance" to listOf("freelance", "proyecto", "cliente", "honorarios", "factura"),
            "Ventas" to listOf("venta", "vendi"),
            "Regalos" to listOf("regalo", "me regalaron", "me dieron")
        )

        fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** The category's usual spelling ("comida" → "Comida"); new categories keep the user's words. */
        fun canonical(category: String): String {
            val k = normalize(category)
            return (EXPENSE_CATEGORIES + INCOME_CATEGORIES).firstOrNull { normalize(it) == k }
                ?: category.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Otros" }
        }

        /** The category to use: the one given, else a guess from the words of the note. */
        fun categoryFor(category: String?, note: String, income: Boolean): String {
            if (!category.isNullOrBlank()) return canonical(category)
            val words = " ${normalize(note)} "
            val table = if (income) INCOME_KEYWORDS else KEYWORDS
            return table.entries.firstOrNull { (_, keys) -> keys.any { words.contains(" $it ") } }?.key
                ?: if (income) "Otros ingresos" else "Otros"
        }

        /**
         * "12", "12.50", "12,50", "$1.200,50", "1.200.000" → cents; null when it isn't an amount, rounds
         * to nothing, or is beyond [MAX_CENTS].
         */
        fun parseAmount(text: String): Long? {
            var t = text.trim().replace("$", "").replace(" ", "").replace("usd", "", ignoreCase = true)
            // Only digits and separators: no sign, and no "1e999999999" for BigDecimal to blow up on.
            if (t.isEmpty() || !Regex("[0-9.,]+").matches(t)) return null
            t = when {
                // A comma followed by one or two digits at the end is a decimal comma.
                Regex(",\\d{1,2}$").containsMatchIn(t) -> t.replace(".", "").replace(",", ".")
                // Dots between groups of three digits are thousands ("1.200", "1.200.000").
                Regex("[1-9]\\d{0,2}(\\.\\d{3})+").matches(t) -> t.replace(".", "")
                else -> t.replace(",", "")
            }
            val value = t.toBigDecimalOrNull() ?: return null
            if (value <= BigDecimal.ZERO || value > BigDecimal.valueOf(MAX_CENTS, 2)) return null
            return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong().takeIf { it > 0 }
        }

        /** Cents as "$12.50" (or "$1,250.00"). */
        fun money(cents: Long): String {
            val sign = if (cents < 0) "-" else ""
            val abs = kotlin.math.abs(cents)
            return sign + "$" + String.format(java.util.Locale.US, "%,d.%02d", abs / 100, abs % 100)
        }

        /** For Dexter: the month in a few lines. */
        fun describe(s: MonthSummary): String = buildString {
            appendLine("Finanzas de ${s.month}: gastado ${money(s.spentCents)}, ingresos ${money(s.incomeCents)}, saldo ${money(s.balanceCents)}.")
            if (s.byCategory.isEmpty()) appendLine("Sin gastos este mes.")
            s.byCategory.forEach { (cat, cents) ->
                val budget = s.budgets[cat]
                append("- $cat: ${money(cents)}")
                if (budget != null) append(" de ${money(budget)} presupuestados (${cents * 100 / budget.coerceAtLeast(1)} %)")
                appendLine()
            }
            s.budgets.filterKeys { k -> s.byCategory.none { it.first == k } }.forEach { (cat, b) -> appendLine("- $cat: $0.00 de ${money(b)} presupuestados") }
        }.trim()
    }
}
