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
import org.isoron.uhabits.ancla.finance.Period
import org.isoron.uhabits.ancla.finance.SavingsGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import javax.crypto.KeyGenerator

/** Money by day, week, month or year; what was saved each month; and savings goals. */
class FinanceSavingsTest {
    companion object {
        @BeforeClass @JvmStatic
        fun key() {
            EncryptedStore.cachedKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val thursday = LocalDate.of(2026, 10, 22)
    private fun store() = FinanceStore(File(Stress.tempDir(), "finance.json"))

    @Test
    fun periodsCoverTheRightDays() {
        assertEquals(thursday..thursday, Period.DAY.range(thursday))
        assertEquals(LocalDate.of(2026, 10, 19)..LocalDate.of(2026, 10, 25), Period.WEEK.range(thursday))
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), Period.MONTH.range(thursday))
        assertEquals(LocalDate.of(2026, 1, 1)..LocalDate.of(2026, 12, 31), Period.YEAR.range(thursday))
        assertEquals(LocalDate.of(2026, 10, 15), Period.WEEK.shift(thursday, -1))
        assertEquals(Period.WEEK, Period.of("semana"))
        assertEquals(Period.YEAR, Period.of("año"))
        assertEquals(Period.DAY, Period.of("hoy"))
        assertEquals("unknown means the month", Period.MONTH, Period.of(null))
    }

    @Test
    fun summariesForAnyPeriod() {
        val money = store()
        money.add(80000, true, "Sueldo", "Pasantía", LocalDate.of(2026, 10, 1))
        money.add(1200, false, null, "almuerzo", thursday)
        money.add(300, false, null, "bus", thursday.minusDays(1))
        money.add(5000, false, null, "libro", LocalDate.of(2026, 10, 2))
        money.add(900, false, null, "cine", LocalDate.of(2026, 3, 4))

        val day = money.summary(Period.DAY, thursday)
        assertEquals(1200L, day.spentCents)
        assertEquals(listOf("Comida" to 1200L), day.byCategory)

        val week = money.summary(Period.WEEK, thursday)
        assertEquals(1500L, week.spentCents)
        assertEquals(0L, week.incomeCents)

        val month = money.summary(Period.MONTH, thursday)
        assertEquals(6500L, month.spentCents)
        assertEquals(80000L - 6500L, month.savedCents)
        assertEquals(91, month.savingsRate)

        assertEquals(7400L, money.summary(Period.YEAR, thursday).spentCents)
        assertNull("no income, no rate", week.savingsRate)
    }

    @Test
    fun savingsEachMonthAndTheProjection() {
        val money = store()
        money.add(50000, true, null, "sueldo", LocalDate.of(2026, 9, 1))
        money.add(20000, false, null, "arriendo", LocalDate.of(2026, 9, 2))
        money.add(10000, false, null, "ropa", LocalDate.of(2026, 10, 5))
        val months = money.monthlySavings(3, YearMonth.of(2026, 10))
        assertEquals(listOf(YearMonth.of(2026, 8) to 0L, YearMonth.of(2026, 9) to 30000L, YearMonth.of(2026, 10) to -10000L), months)
        // $100 by the 10th of a 31-day month: about $310 by its end.
        assertEquals(31000L, money.monthProjection(LocalDate.of(2026, 10, 10)))
        assertNull("too early in the month to say", money.monthProjection(LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun savingsGoalsAreKeptAndFilled() {
        val file = File(Stress.tempDir(), "finance.json")
        val money = FinanceStore(file)
        money.addGoal("viaje a Galápagos", 120000, YearMonth.of(2027, 3))
        money.contribute("galapagos", 30000)
        money.contribute("viaje", 20000)
        money.contribute("viaje", -5000)
        val g = FinanceStore(file).goals().single()
        assertEquals("Viaje a Galápagos", g.name)
        assertEquals(45000L, g.savedCents)
        assertEquals(YearMonth.of(2027, 3), g.by)
        assertNull("an unknown goal", money.contribute("moto", 100))
        assertEquals("Viaje a Galápagos", money.removeGoal("viaje")!!.name)
        assertTrue(money.goals().isEmpty())
    }

    @Test
    fun whatToSetAsideEachMonth() {
        val g = SavingsGoal("1", "Laptop", 100000, 40000, YearMonth.of(2027, 3))
        // October to March is six months: $600 left is $100 a month.
        assertEquals(10000L, g.monthlyNeeded(YearMonth.of(2026, 10)))
        assertEquals("rounded up so it's reached in time", 30000L, g.copy(savedCents = 10000).monthlyNeeded(YearMonth.of(2027, 1)))
        assertEquals("the last month takes the rest", 60000L, g.monthlyNeeded(YearMonth.of(2027, 5)))
        assertNull("no date, no monthly amount", g.copy(by = null).monthlyNeeded())
        assertNull("reached", g.copy(savedCents = 100000).monthlyNeeded())
        assertEquals(0.4f, g.progress, 0.001f)
    }
}
