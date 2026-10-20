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
        val m = Movement(
            UUID.randomUUID().toString(), cents, income,
            categoryFor(category, note, income), note.trim().take(200), day, System.currentTimeMillis()
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

    // --- Storage ---

    private data class Data(val movements: List<Movement>, val budgets: Map<String, Long>)

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
        return Data(movements, budgets)
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
        EncryptedStore.writeText(file, json.toString())
        StoreCache.wrote(file, Data(data.movements.toList(), data.budgets.toMap()))
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
