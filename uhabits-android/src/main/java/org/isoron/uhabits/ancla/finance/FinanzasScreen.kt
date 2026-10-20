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

private enum class FinTab { RESUMEN, MOVIMIENTOS, PRESUPUESTOS }

/** Colors for the categories, in the night palette; repeated past ten. */
private val CATEGORY_COLORS = listOf(
    Color(0xFF9D7BFF), Color(0xFFFFB627), Color(0xFF3FBF95), Color(0xFFFF7AA8), Color(0xFF5AB8FF),
    Color(0xFFE88B3A), Color(0xFFC9B8FF), Color(0xFF7BE0C3), Color(0xFFB98CFF), Color(0xFF8A84B8)
)

private fun colorOf(category: String): Color {
    val i = FinanceStore.EXPENSE_CATEGORIES.indexOf(category).takeIf { it >= 0 } ?: (category.hashCode() and 0x7fffffff)
    return CATEGORY_COLORS[i % CATEGORY_COLORS.size]
}

/**
 * Finanzas: every expense and income, told to Dexter ("gasté 12 en almuerzo") or added here, sorted
 * by category. Three tabs: the month at a glance with a ring by category, the movements by day, and
 * the monthly budgets. Picks up what Dexter adds while it is open.
 */
@Composable
fun FinanzasScreen(store: FinanceStore, openMenu: () -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(FinTab.RESUMEN) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var summary by remember { mutableStateOf<MonthSummary?>(null) }
    var movements by remember { mutableStateOf(emptyList<Movement>()) }
    suspend fun reload() {
        withContext(Dispatchers.IO) {
            runCatching { store.summary(month) to store.movements(month) }.getOrNull()
        }?.let { (s, m) ->
            summary = s
            movements = m
        }
    }
    LaunchedEffect(month) {
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { PlaceHeader(R.string.fin_caption, R.string.nav_finanzas, openMenu) }
                Text(
                    stringResource(R.string.fin_add), style = MaterialTheme.typography.labelLarge, color = c.onInk,
                    modifier = Modifier.clip(CircleShape).background(c.lilac).combinedClickableCompat(Role.Button, { adding = true }).padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Chip(stringResource(R.string.fin_tab_summary), tab == FinTab.RESUMEN) { tab = FinTab.RESUMEN }
                Chip(stringResource(R.string.fin_tab_movements), tab == FinTab.MOVIMIENTOS) { tab = FinTab.MOVIMIENTOS }
                Chip(stringResource(R.string.fin_tab_budgets), tab == FinTab.PRESUPUESTOS) { tab = FinTab.PRESUPUESTOS }
            }
            MonthPicker(month, onChange = { month = it })
            val s = summary
            when (tab) {
                FinTab.RESUMEN -> if (s != null) Overview(s) { budgetFor = it }
                FinTab.MOVIMIENTOS -> MovementList(movements) { editing = it }
                FinTab.PRESUPUESTOS -> if (s != null) Budgets(s) { budgetFor = it }
            }
            Text(
                stringResource(R.string.fin_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft,
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
        BudgetSheet(category, summary?.budgets?.get(category), onDismiss = { budgetFor = null }) { cents ->
            scope.launch {
                withContext(Dispatchers.IO) { runCatching { store.setBudget(category, cents) } }
                reload()
            }
            budgetFor = null
        }
    }
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
private fun MonthPicker(month: YearMonth, onChange: (YearMonth) -> Unit) {
    val c = Ancla.colors
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() } + " " + month.year
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("‹", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { onChange(month.minusMonths(1)) }).padding(horizontal = 14.dp, vertical = 2.dp))
        Text(name, style = MaterialTheme.typography.titleLarge, color = c.ink)
        if (month < YearMonth.now()) {
            Text("›", style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { onChange(month.plusMonths(1)) }).padding(horizontal = 14.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun Overview(s: MonthSummary, onBudget: (String) -> Unit) {
    val c = Ancla.colors
    val grow = remember(s.month) { Animatable(0f) }
    LaunchedEffect(s.month) { grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
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
                Text(stringResource(R.string.fin_balance), style = MaterialTheme.typography.labelSmall, color = c.muted)
                Text(FinanceStore.money(s.balanceCents), style = MaterialTheme.typography.titleLarge, color = if (s.balanceCents >= 0) c.amber else Color(0xFFFF7AA8))
            }
        }
    }
    if (s.byCategory.isEmpty()) {
        Text(stringResource(R.string.fin_empty), style = MaterialTheme.typography.bodyLarge, color = c.muted)
    }
    s.byCategory.forEach { (cat, cents) -> CategoryRow(cat, cents, s.budgets[cat], s.spentCents) { onBudget(cat) } }
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
