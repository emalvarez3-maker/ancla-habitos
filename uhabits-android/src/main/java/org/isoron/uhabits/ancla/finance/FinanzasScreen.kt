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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.PlaceHeader
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class FinTab { RESUMEN, MOVIMIENTOS, AHORRO, PRESUPUESTOS }

/** Colors for the categories, in the night palette; repeated past ten. */
private val CATEGORY_COLORS = listOf(
    Color(0xFF9D7BFF), Color(0xFFFFB627), Color(0xFF3FBF95), Color(0xFFFF7AA8), Color(0xFF5AB8FF),
    Color(0xFFE88B3A), Color(0xFFC9B8FF), Color(0xFF7BE0C3), Color(0xFFB98CFF), Color(0xFF8A84B8)
)

private fun colorOf(category: String): Color {
    val i = FinanceStore.EXPENSE_CATEGORIES.indexOf(category).takeIf { it >= 0 } ?: (category.hashCode() and 0x7fffffff)
    return CATEGORY_COLORS[i % CATEGORY_COLORS.size]
}

/** Everything the screen shows, loaded together off the main thread. */
private class FinView(
    val summary: PeriodSummary,
    val movements: List<Movement>,
    val month: MonthSummary,
    val savings: List<Pair<YearMonth, Long>>,
    val goals: List<SavingsGoal>,
    val projection: Long?
)

/**
 * Finanzas: every expense and income, told to Dexter ("gasté 12 en almuerzo") or added here, sorted
 * by category, for a day, a week, a month or a year. Four tabs: the period at a glance, its
 * movements, savings (what was kept each month and the goals being saved for) and the monthly
 * budgets. Picks up what Dexter adds while it is open.
 */
@Composable
fun FinanzasScreen(store: FinanceStore, openMenu: () -> Unit, startOnSavings: Boolean = false) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(if (startOnSavings) FinTab.AHORRO else FinTab.RESUMEN) }
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var anchor by remember { mutableStateOf(LocalDate.now()) }
    var view by remember { mutableStateOf<FinView?>(null) }
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            runCatching {
                val range = period.range(anchor)
                FinView(
                    store.summary(period, anchor), store.movements(range), store.summary(YearMonth.from(anchor)),
                    store.monthlySavings(6, YearMonth.from(anchor)), store.goals(),
                    if (YearMonth.from(anchor) == YearMonth.now()) store.monthProjection() else null
                )
            }.getOrNull()
        }?.let { view = it }
    }
    LaunchedEffect(period, anchor) {
        // Dexter may add movements while this is open: reload only when the store says they changed.
        var seen = Long.MIN_VALUE
        while (true) {
            val version = withContext(Dispatchers.IO) { runCatching { store.version() }.getOrDefault(seen - 1) }
            if (version != seen) {
                reload()
                seen = version
            }
            delay(2500)
        }
    }
    var editing by remember { mutableStateOf<Movement?>(null) }
    var adding by remember { mutableStateOf(false) }
    var budgetFor by remember { mutableStateOf<String?>(null) }
    var newGoal by remember { mutableStateOf(false) }
    var goalFor by remember { mutableStateOf<SavingsGoal?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PlaceHeader(R.string.fin_caption, R.string.nav_finanzas, openMenu) }
                Text(
                    stringResource(if (tab == FinTab.AHORRO) R.string.fin_goal_add else R.string.fin_add), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(c.lilac)
                        .combinedClickableCompat(Role.Button, { if (tab == FinTab.AHORRO) newGoal = true else adding = true }).padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Chip(stringResource(R.string.fin_tab_summary), tab == FinTab.RESUMEN) { tab = FinTab.RESUMEN }
                Chip(stringResource(R.string.fin_tab_movements), tab == FinTab.MOVIMIENTOS) { tab = FinTab.MOVIMIENTOS }
                Chip(stringResource(R.string.fin_tab_savings), tab == FinTab.AHORRO) { tab = FinTab.AHORRO }
                Chip(stringResource(R.string.fin_tab_budgets), tab == FinTab.PRESUPUESTOS) { tab = FinTab.PRESUPUESTOS }
            }
            // Savings and budgets are monthly; the summary and the movements take any period.
            val periodic = tab == FinTab.RESUMEN || tab == FinTab.MOVIMIENTOS
            if (periodic) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Period.entries.forEach { p -> SmallChip(stringResource(periodName(p)), period == p) { period = p } }
                }
            }
            PeriodPicker(if (periodic) period else Period.MONTH, anchor) { anchor = it }
            val v = view
            if (v != null) when (tab) {
                FinTab.RESUMEN -> Overview(v.summary, if (period == Period.MONTH) v.month.budgets else emptyMap(), if (period == Period.MONTH) v.projection else null) { budgetFor = it }
                FinTab.MOVIMIENTOS -> MovementList(v.movements) { editing = it }
                FinTab.AHORRO -> Savings(v.savings, v.goals) { goalFor = it }
                FinTab.PRESUPUESTOS -> Budgets(v.month) { budgetFor = it }
            }
            Text(
                stringResource(if (tab == FinTab.AHORRO) R.string.fin_savings_hint else R.string.fin_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)
            )
        }
    }

    if (adding || editing != null) {
        MovementSheet(
            initial = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { cents, income, category, note, day ->
                val old = editing
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (old == null) store.add(cents, income, category, note, day) else store.update(old.id, cents, income, category, note, day)
                        }
                    }
                    reload()
                }
                adding = false
                editing = null
            },
            onDelete = { m ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { store.remove(m.id) } }
                    reload()
                }
                editing = null
            }
        )
    }
    budgetFor?.let { category ->
        BudgetSheet(category, view?.month?.budgets?.get(category), onDismiss = { budgetFor = null }) { cents ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.setBudget(category, cents) } }
                reload()
            }
            budgetFor = null
        }
    }
    if (newGoal) {
        GoalSheet(onDismiss = { newGoal = false }) { name, cents, by ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.addGoal(name, cents, by) } }
                reload()
            }
            newGoal = false
        }
    }
    goalFor?.let { goal ->
        ContributeSheet(goal, onDismiss = { goalFor = null }, onRemove = {
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.removeGoal(goal.name) } }
                reload()
            }
            goalFor = null
        }) { cents ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.contribute(goal.name, cents) } }
                reload()
            }
            goalFor = null
        }
    }
}

private fun periodName(p: Period) = when (p) {
    Period.DAY -> R.string.fin_period_day
    Period.WEEK -> R.string.fin_period_week
    Period.MONTH -> R.string.fin_period_month
    Period.YEAR -> R.string.fin_period_year
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.card).combinedClickableCompat(Role.Tab, onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun SmallChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelMedium, color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.amber else c.cardMuted).combinedClickableCompat(Role.Tab, onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}

/** "Hoy", "Semana del 19 oct", "Octubre 2026", "2026", with arrows to move one period back or forward. */
@Composable
private fun PeriodPicker(period: Period, anchor: LocalDate, onChange: (LocalDate) -> Unit) {
    val c = Ancla.colors
    val today = LocalDate.now()
    val range = period.range(anchor)
    val name = when (period) {
        Period.DAY -> when (anchor) {
            today -> stringResource(R.string.fin_today)
            today.minusDays(1) -> stringResource(R.string.fin_yesterday)
            else -> anchor.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())).replaceFirstChar { it.uppercase() }
        }
        Period.WEEK -> stringResource(R.string.fin_week_of, range.start.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())))
        Period.MONTH -> anchor.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() } + " " + anchor.year
        Period.YEAR -> anchor.year.toString()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("‹", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { onChange(period.shift(anchor, -1)) }).padding(horizontal = 14.dp, vertical = 2.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, color = c.ink)
        if (range.endInclusive < today) {
            Text("›", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { onChange(minOf(period.shift(anchor, 1), today)) }).padding(horizontal = 14.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun Overview(s: PeriodSummary, budgets: Map<String, Long>, projection: Long?, onBudget: (String) -> Unit) {
    val c = Ancla.colors
    val grow = remember(s.from, s.period) { Animatable(0f) }
    LaunchedEffect(s.from, s.period) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(130.dp)) {
                val w = 16.dp.toPx()
                val arcSize = Size(size.width - w, size.height - w)
                val tl = Offset(w / 2, w / 2)
                drawArc(c.track, 0f, 360f, false, tl, arcSize, style = Stroke(w))
                val total = s.spentCents.coerceAtLeast(1)
                var start = -90f
                s.byCategory.forEach { (cat, cents) ->
                    val sweep = 360f * cents / total * grow.value
                    drawArc(colorOf(cat), start, (sweep - 2f).coerceAtLeast(0.5f), false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.fin_spent), style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(FinanceStore.money(s.spentCents), style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 1)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                Text(stringResource(R.string.fin_income), style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(FinanceStore.money(s.incomeCents), style = MaterialTheme.typography.titleLarge, color = c.mint)
            }
            Column {
                Text(stringResource(R.string.fin_saved), style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(FinanceStore.money(s.savedCents), style = MaterialTheme.typography.titleLarge, color = if (s.savedCents >= 0) c.amber else Color(0xFFFF7AA8))
                s.savingsRate?.let { Text(stringResource(R.string.fin_savings_rate, it), style = MaterialTheme.typography.labelSmall, color = c.muted) }
            }
        }
    }
    // Days and weeks are short: an average per day says more than the total.
    val days = (java.time.temporal.ChronoUnit.DAYS.between(s.from, minOf(s.to, LocalDate.now())) + 1).coerceAtLeast(1)
    if (s.period != Period.DAY && s.spentCents > 0) {
        Text(
            stringResource(R.string.fin_per_day, FinanceStore.money(s.spentCents / days)), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)
        )
    }
    projection?.let {
        Text(
            stringResource(R.string.fin_projection, FinanceStore.money(it)), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)
        )
    }
    if (s.byCategory.isEmpty()) {
        Text(stringResource(R.string.fin_empty), style = MaterialTheme.typography.bodyLarge, color = c.muted)
    }
    s.byCategory.forEach { (cat, cents) -> CategoryRow(cat, cents, budgets[cat], s.spentCents) { onBudget(cat) } }
}

/** What was kept each of the last six months, as bars, and the goals being saved for. */
@Composable
private fun Savings(savings: List<Pair<YearMonth, Long>>, goals: List<SavingsGoal>, onGoal: (SavingsGoal) -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.fin_savings_months), style = MaterialTheme.typography.titleMedium, color = c.ink)
        val top = savings.maxOfOrNull { kotlin.math.abs(it.second) }?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            savings.forEach { (month, cents) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shortMoney(cents), style = MaterialTheme.typography.labelSmall, color = if (cents >= 0) c.amber else Color(0xFFFF7AA8), maxLines = 1)
                    Box(
                        Modifier.fillMaxWidth().height((100f * kotlin.math.abs(cents) / top).coerceAtLeast(3f).dp)
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)).background(if (cents >= 0) c.amber else Color(0xFFFF7AA8))
                    )
                    Text(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
                }
            }
        }
        val kept = savings.map { it.second }
        Text(
            stringResource(R.string.fin_savings_average, FinanceStore.money(kept.sum() / kept.size.coerceAtLeast(1))),
            style = MaterialTheme.typography.bodyMedium, color = c.inkSoft
        )
    }
    Text(stringResource(R.string.fin_goals), style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.padding(top = 4.dp))
    if (goals.isEmpty()) Text(stringResource(R.string.fin_goals_empty), style = MaterialTheme.typography.bodyMedium, color = c.muted)
    goals.forEach { g ->
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).combinedClickableCompat(Role.Button, { onGoal(g) }).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (g.done) "🏆 " + g.name else g.name, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
                Text("${(g.progress * 100).toInt()} %", style = MaterialTheme.typography.titleMedium, color = if (g.done) c.mint else c.amber)
            }
            Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(c.track)) {
                Box(Modifier.fillMaxWidth(g.progress.coerceAtLeast(0.02f)).height(10.dp).clip(CircleShape).background(if (g.done) c.mint else c.amber))
            }
            Text(
                FinanceStore.money(g.savedCents) + " / " + FinanceStore.money(g.targetCents) +
                    (g.by?.let { " · " + it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + it.year } ?: ""),
                style = MaterialTheme.typography.labelMedium, color = c.muted
            )
            g.monthlyNeeded()?.let { Text(stringResource(R.string.fin_goal_monthly, FinanceStore.money(it)), style = MaterialTheme.typography.labelMedium, color = c.lilacInk) }
        }
    }
}

/** "$1.2k", "-$80": short enough for a bar's label. */
private fun shortMoney(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val d = kotlin.math.abs(cents) / 100
    return sign + "$" + if (d >= 1000) String.format(Locale.US, "%.1fk", d / 1000.0) else d.toString()
}

@Composable
private fun CategoryRow(category: String, cents: Long, budget: Long?, total: Long, onClick: () -> Unit) {
    val c = Ancla.colors
    val fraction = if (budget != null && budget > 0) cents.toFloat() / budget else cents.toFloat() / total.coerceAtLeast(1)
    val over = budget != null && cents > budget
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).combinedClickableCompat(Role.Button, onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(colorOf(category)))
            Text(category, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(FinanceStore.money(cents), style = MaterialTheme.typography.titleMedium, color = c.ink)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.track)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(if (over) Color(0xFFFF7AA8) else colorOf(category)))
        }
        if (budget != null) {
            Text(
                if (over) stringResource(R.string.fin_over_budget, FinanceStore.money(cents - budget), FinanceStore.money(budget))
                else stringResource(R.string.fin_left_budget, FinanceStore.money(budget - cents), FinanceStore.money(budget)),
                style = MaterialTheme.typography.labelMedium, color = if (over) Color(0xFFFF7AA8) else c.muted
            )
        }
    }
}

@Composable
private fun MovementList(movements: List<Movement>, onEdit: (Movement) -> Unit) {
    val c = Ancla.colors
    if (movements.isEmpty()) {
        Text(stringResource(R.string.fin_empty), style = MaterialTheme.typography.bodyLarge, color = c.muted)
        return
    }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEEE d", Locale.getDefault()) }
    movements.groupBy { it.day }.forEach { (day, list) ->
        Text(day.format(dayFormat).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = c.muted, modifier = Modifier.padding(top = 4.dp))
        list.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).combinedClickableCompat(Role.Button, { onEdit(m) }).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (m.income) c.mint else colorOf(m.category)))
                Column(Modifier.weight(1f)) {
                    Text(m.note.ifBlank { m.category }, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.category, style = MaterialTheme.typography.labelMedium, color = c.muted)
                }
                Text(
                    (if (m.income) "+" else "−") + FinanceStore.money(m.cents),
                    style = MaterialTheme.typography.titleMedium, color = if (m.income) c.mint else c.ink
                )
            }
        }
    }
}

@Composable
private fun Budgets(s: MonthSummary, onBudget: (String) -> Unit) {
    val c = Ancla.colors
    Text(stringResource(R.string.fin_budgets_explain), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
    val spent = s.byCategory.toMap()
    FinanceStore.EXPENSE_CATEGORIES.union(s.budgets.keys).union(spent.keys).forEach { cat ->
        val budget = s.budgets[cat]
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).combinedClickableCompat(Role.Button, { onBudget(cat) }).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(colorOf(cat)))
            Text(cat, style = MaterialTheme.typography.titleMedium, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                if (budget != null) FinanceStore.money(spent[cat] ?: 0) + " / " + FinanceStore.money(budget) else stringResource(R.string.fin_no_budget),
                style = MaterialTheme.typography.labelLarge, color = if (budget != null) c.ink else c.muted
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MovementSheet(
    initial: Movement?,
    onDismiss: () -> Unit,
    onSave: (cents: Long, income: Boolean, category: String?, note: String, day: LocalDate) -> Unit,
    onDelete: (Movement) -> Unit
) {
    val c = Ancla.colors
    var amount by remember { mutableStateOf(initial?.let { FinanceStore.money(it.cents).removePrefix("$").replace(",", "") }.orEmpty()) }
    var income by remember { mutableStateOf(initial?.income ?: false) }
    var category by remember { mutableStateOf(initial?.category) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var day by remember { mutableStateOf(initial?.day ?: LocalDate.now()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val cents = FinanceStore.parseAmount(amount)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (initial == null) R.string.fin_new else R.string.fin_edit), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.fin_expense), !income) { income = false; category = null }
                Chip(stringResource(R.string.fin_income_one), income) { income = true; category = null }
            }
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_amount)) }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_note)) }, placeholder = { Text(stringResource(R.string.fin_note_hint)) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            val guessed = FinanceStore.categoryFor(null, note, income)
            val shown = category ?: guessed
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                (if (income) FinanceStore.INCOME_CATEGORIES else FinanceStore.EXPENSE_CATEGORIES).forEach { cat ->
                    Chip(cat, cat == shown) { category = cat }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                Chip(stringResource(R.string.fin_today), day == today) { day = today }
                Chip(stringResource(R.string.fin_yesterday), day == today.minusDays(1)) { day = today.minusDays(1) }
                if (day != today && day != today.minusDays(1)) Chip(day.toString(), true) {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.fin_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(if (cents == null) c.track else c.lilac)
                        .combinedClickableCompat(Role.Button, { if (cents != null) onSave(cents, income, shown, note, day) })
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                )
                if (initial != null) {
                    Text(
                        stringResource(if (confirmDelete) R.string.camino_delete_confirm else R.string.camino_delete), style = MaterialTheme.typography.labelLarge, color = c.amberInk,
                        modifier = Modifier.clip(CircleShape).background(c.ground).combinedClickableCompat(Role.Button, { if (confirmDelete) onDelete(initial) else confirmDelete = true })
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetSheet(category: String, current: Long?, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    val c = Ancla.colors
    var amount by remember { mutableStateOf(current?.let { FinanceStore.money(it).removePrefix("$").replace(",", "") }.orEmpty()) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.fin_budget_for, category), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_budget_monthly)) }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.fin_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(c.lilac).combinedClickableCompat(Role.Button, { onSave(FinanceStore.parseAmount(amount) ?: 0L) }).padding(horizontal = 22.dp, vertical = 12.dp)
                )
                if (current != null) {
                    Text(
                        stringResource(R.string.fin_budget_remove), style = MaterialTheme.typography.labelLarge, color = c.amberInk,
                        modifier = Modifier.clip(CircleShape).background(c.ground).combinedClickableCompat(Role.Button, { onSave(0L) }).padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalSheet(onDismiss: () -> Unit, onSave: (name: String, cents: Long, by: YearMonth?) -> Unit) {
    val c = Ancla.colors
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var months by remember { mutableStateOf<Long?>(6) }
    val cents = FinanceStore.parseAmount(amount)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.fin_goal_new), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_goal_name)) }, placeholder = { Text(stringResource(R.string.fin_goal_name_hint)) },
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_goal_target)) }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth()
            )
            Text(stringResource(R.string.fin_goal_by), style = MaterialTheme.typography.labelLarge, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(3L, 6L, 12L, 24L).forEach { m -> Chip(stringResource(R.string.fin_goal_months, m.toInt()), months == m) { months = m } }
                Chip(stringResource(R.string.fin_goal_no_date), months == null) { months = null }
            }
            Text(
                stringResource(R.string.fin_save), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                modifier = Modifier.clip(CircleShape).background(if (cents == null || name.isBlank()) c.track else c.lilac)
                    .combinedClickableCompat(Role.Button, { if (cents != null && name.isNotBlank()) onSave(name, cents, months?.let { YearMonth.now().plusMonths(it) }) })
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContributeSheet(goal: SavingsGoal, onDismiss: () -> Unit, onRemove: () -> Unit, onSave: (Long) -> Unit) {
    val c = Ancla.colors
    var amount by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val cents = FinanceStore.parseAmount(amount)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedLabelColor = c.lilacInk,
        cursorColor = c.lilac, focusedTextColor = c.ink, unfocusedTextColor = c.ink
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(goal.name, style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Text(FinanceStore.money(goal.savedCents) + " / " + FinanceStore.money(goal.targetCents), style = MaterialTheme.typography.titleMedium, color = c.amber)
            OutlinedTextField(
                value = amount, onValueChange = { amount = it }, singleLine = true,
                label = { Text(stringResource(R.string.fin_goal_amount)) }, prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(16.dp), colors = fieldColors, textStyle = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.fin_goal_put), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(if (cents == null) c.track else c.amber)
                        .combinedClickableCompat(Role.Button, { cents?.let(onSave) }).padding(horizontal = 20.dp, vertical = 12.dp)
                )
                Text(
                    stringResource(R.string.fin_goal_take), style = MaterialTheme.typography.labelLarge, color = c.ink,
                    modifier = Modifier.clip(CircleShape).background(c.ground)
                        .combinedClickableCompat(Role.Button, { cents?.let { onSave(-it) } }).padding(horizontal = 16.dp, vertical = 12.dp)
                )
                Text(
                    stringResource(if (confirmDelete) R.string.camino_delete_confirm else R.string.camino_delete), style = MaterialTheme.typography.labelLarge, color = c.amberInk,
                    modifier = Modifier.clip(CircleShape).background(c.ground).combinedClickableCompat(Role.Button, { if (confirmDelete) onRemove() else confirmDelete = true })
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}
