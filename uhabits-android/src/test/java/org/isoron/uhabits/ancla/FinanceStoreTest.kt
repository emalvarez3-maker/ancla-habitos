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

import org.isoron.uhabits.ancla.finance.FinanceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.crypto.KeyGenerator

/** Dexter's money manager: amounts in cents, categories, budgets, encrypted. */
class FinanceStoreTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private fun store() = FinanceStore(File(Stress.tempDir(), "finance.json"))

    @Test
    fun amountsAreParsedToCents() {
        assertEquals(1200L, FinanceStore.parseAmount("12"))
        assertEquals(1250L, FinanceStore.parseAmount("12.50"))
        assertEquals(1250L, FinanceStore.parseAmount("12,50"))
        assertEquals(120050L, FinanceStore.parseAmount("$1.200,50"))
        assertEquals(120050L, FinanceStore.parseAmount("1,200.50"))
        assertNull(FinanceStore.parseAmount("doce"))
        assertNull(FinanceStore.parseAmount("0"))
        assertNull(FinanceStore.parseAmount("-5"))
        assertEquals("$1,250.05", FinanceStore.money(125005))
    }

    @Test
    fun amountsAsPeopleWriteThem() {
        // Thousands with dots (as in Ecuador) or commas.
        assertEquals(120000L, FinanceStore.parseAmount("1.200"))
        assertEquals(120000000L, FinanceStore.parseAmount("1.200.000"))
        assertEquals(120000050L, FinanceStore.parseAmount("1.200.000,50"))
        assertEquals(1250000L, FinanceStore.parseAmount("12,500"))
        assertEquals(120000000L, FinanceStore.parseAmount("1,200,000"))
        assertEquals(120000L, FinanceStore.parseAmount("$ 1 200"))
        // Half-written decimals.
        assertEquals(1200L, FinanceStore.parseAmount("12."))
        assertEquals(50L, FinanceStore.parseAmount(".5"))
        assertEquals(50L, FinanceStore.parseAmount(",5"))
        assertEquals(1250L, FinanceStore.parseAmount("12,5"))
        assertEquals("three digits after a dot are thousands", 1234500L, FinanceStore.parseAmount("12.345"))
        assertEquals("rounded to the cent", 1001L, FinanceStore.parseAmount("10.0051"))
        assertEquals("a leading zero is a decimal, not thousands", 50L, FinanceStore.parseAmount("0.500"))
        assertEquals(1234500L, FinanceStore.parseAmount("12,345.0"))
        assertEquals(500L, FinanceStore.parseAmount("5 USD"))
        assertEquals(500L, FinanceStore.parseAmount("  $5  "))
    }

    @Test
    fun whatIsNotAnAmountIsRefused() {
        for (s in listOf("", " ", "$", ".", ",", "..", "1.2.3", "12,50,3", "0,00", "0.004", "-12", "+12", "12-3", "1e5", "1e999999999", "NaN", "Infinity", "0x10", "doce dólares", "12abc")) {
            assertNull(s, FinanceStore.parseAmount(s))
        }
    }

    @Test
    fun hugeAmountsNeverWrapAround() {
        assertEquals(FinanceStore.MAX_CENTS, FinanceStore.parseAmount("100000000"))
        assertNull(FinanceStore.parseAmount("100000000.01"))
        assertNull(FinanceStore.parseAmount("92233720368547758.08")) // Long.MAX_VALUE + 1 cent
        assertNull(FinanceStore.parseAmount("9".repeat(40)))
        assertNull(FinanceStore.parseAmount("1" + "0".repeat(5000)))
        // Whatever parses can be saved.
        val money = store()
        listOf("1", "99999999.99", "100000000", "0.01").forEach { money.add(FinanceStore.parseAmount(it)!!, false, null, it) }
        assertEquals(4, money.movements().size)
    }

    @Test
    fun theScreenSeesDextersChangesThroughACheapVersion() {
        val dir = Stress.tempDir()
        val screen = FinanceStore(File(dir, "finance.json"))
        val dexter = FinanceStore(File(dir, "finance.json"))
        val v = screen.version()
        assertEquals(v, screen.version())
        val m = dexter.add(1250, false, null, "almuerzo")
        val added = screen.version()
        assertTrue(added != v)
        assertEquals(listOf(m), screen.movements())
        dexter.setBudget("Comida", 5000)
        assertTrue(screen.version() != added)
        assertEquals(mapOf("Comida" to 5000L), screen.budgets())
        dexter.remove(m.id)
        assertTrue(screen.movements().isEmpty())
    }

    @Test
    fun categoriesAreGuessedFromTheNote() {
        assertEquals("Comida", FinanceStore.categoryFor(null, "almuerzo con Ana", false))
        assertEquals("Transporte", FinanceStore.categoryFor(null, "Uber al trabajo", false))
        assertEquals("Suscripciones", FinanceStore.categoryFor(null, "Netflix", false))
        assertEquals("Servicios", FinanceStore.categoryFor(null, "pago de la luz", false))
        assertEquals("Otros", FinanceStore.categoryFor(null, "cosas varias", false))
        assertEquals("Sueldo", FinanceStore.categoryFor(null, "sueldo de octubre", true))
        assertEquals("Comida", FinanceStore.categoryFor("comida", "lo que sea", false))
        assertEquals("Mascotas", FinanceStore.categoryFor("mascotas", "veterinario", false))
    }

    @Test
    fun monthSummaryAddsUpByCategoryAndBudget() {
        val dir = Stress.tempDir()
        val money = FinanceStore(File(dir, "finance.json"))
        val oct = LocalDate.of(2026, 10, 5)
        money.add(1250, false, null, "almuerzo", oct)
        money.add(750, false, "Comida", "café", oct.plusDays(1))
        money.add(300, false, null, "bus", oct)
        money.add(150000, true, null, "sueldo", oct)
        money.add(999, false, null, "almuerzo", LocalDate.of(2026, 9, 30)) // another month
        money.setBudget("comida", 5000)
        val s = money.summary(YearMonth.of(2026, 10))
        assertEquals(2300L, s.spentCents)
        assertEquals(150000L, s.incomeCents)
        assertEquals(147700L, s.balanceCents)
        assertEquals(listOf("Comida" to 2000L, "Transporte" to 300L), s.byCategory)
        assertEquals(mapOf("Comida" to 5000L), s.budgets)
        assertTrue(FinanceStore.describe(s).contains("Comida: $20.00 de $50.00 presupuestados (40 %)"))
        assertFalse(String(File(dir, "finance.json").readBytes(), Charsets.ISO_8859_1).contains("almuerzo"))
    }

    @Test
    fun editingRemovingAndTheLastOne() {
        val money = store()
        val a = money.add(500, false, null, "taxi")
        val b = money.add(800, false, null, "pizza")
        assertEquals(b.id, money.last()!!.id)
        assertEquals("Ocio", money.update(a.id, category = "ocio")!!.category)
        assertEquals(b.id, money.remove(b.id)!!.id)
        assertEquals(1, money.movements().size)
        assertTrue(runCatching { money.add(0, false, null, "nada") }.isFailure)
        money.setBudget("Comida", 1000)
        money.setBudget("Comida", 0)
        assertTrue(money.budgets().isEmpty())
    }
}
