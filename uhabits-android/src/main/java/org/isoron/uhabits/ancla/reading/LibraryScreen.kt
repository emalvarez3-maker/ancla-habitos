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

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import org.isoron.uhabits.core.models.Book
import org.isoron.uhabits.core.models.BookStatus
import org.isoron.uhabits.core.models.ReadingStats
import org.isoron.uhabits.core.models.ReadingTags
import org.isoron.uhabits.core.models.Recommendation
import org.isoron.uhabits.core.models.RecommendationReason
import kotlin.math.abs
import kotlin.math.roundToInt

private val coverColors = listOf(0xFF2B4C7E, 0xFFE85D4A, 0xFF3E6B5A, 0xFF7A3B8F, 0xFFC8963E, 0xFF2F2F3E, 0xFF9E5A3C, 0xFF1F7A8C)

@Composable
private fun goalLabel(tag: String) = stringResource(
    when (tag) {
        ReadingTags.HABITS -> R.string.goal_habits
        ReadingTags.FINANCE -> R.string.goal_finance
        ReadingTags.ENGLISH -> R.string.goal_english
        ReadingTags.FICTION -> R.string.goal_fiction
        ReadingTags.PSYCHOLOGY -> R.string.goal_psychology
        ReadingTags.SCIENCE -> R.string.goal_science
        ReadingTags.TECH -> R.string.goal_tech
        ReadingTags.PRODUCTIVITY -> R.string.goal_productivity
        ReadingTags.LATAM -> R.string.goal_latam
        ReadingTags.PHILOSOPHY -> R.string.goal_philosophy
        ReadingTags.HEALTH -> R.string.goal_health
        ReadingTags.HISTORY -> R.string.goal_history
        ReadingTags.BUSINESS -> R.string.goal_business
        else -> R.string.goal_classic
    }
)

@Composable
fun Cover(title: String, modifier: Modifier) {
    val color = Color(coverColors[abs(title.hashCode()) % coverColors.size])
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(6.dp)) {
        Text(
            title.uppercase(),
            color = Color(0xFFFFE6BD),
            fontSize = 8.sp,
            lineHeight = 9.sp,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun hours(minutes: Int): String = if (minutes < 60) "$minutes min" else "%.1f h".format(minutes / 60.0).replace(".0 h", " h")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    state: LibraryState?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onOpen: (Book) -> Unit,
    onToggleGoal: (String) -> Unit,
    onWant: (Recommendation) -> Unit,
    onStart: (Book) -> Unit,
    onLog: (Book, Int, Int) -> Unit,
    onAddPaper: (BookSearchResult, Boolean) -> Unit,
    onDelete: (Book) -> Unit,
    onSearch: (String, (List<BookSearchResult>) -> Unit) -> Unit
) {
    val c = Ancla.colors
    var showAdd by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf<Book?>(null) }
    var deleting by remember { mutableStateOf<Book?>(null) }

    if (showAdd) {
        AddBookSheet(
            onDismiss = { showAdd = false },
            onImport = { showAdd = false; onImport() },
            onSearch = onSearch,
            onAdd = { r, start -> showAdd = false; onAddPaper(r, start) }
        )
    }
    logging?.let { book -> LogReadingDialog(book, onDismiss = { logging = null }, onSave = { m, p -> onLog(book, m, p); logging = null }) }
    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = c.card,
            title = { Text(stringResource(R.string.library_delete_title, book.title), style = MaterialTheme.typography.titleLarge, color = c.ink) },
            confirmButton = { TextButton(onClick = { onDelete(book); deleting = null }) { Text(stringResource(R.string.task_delete), color = c.amberInk) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.task_cancel), color = c.muted) } }
        )
    }

    val books = state?.books.orEmpty()
    val reading = books.filter { it.status == BookStatus.READING }
    val wanted = books.filter { it.status == BookStatus.WANT }
    val finished = books.filter { it.status == BookStatus.FINISHED }

    LazyColumn(
        Modifier.fillMaxSize().background(c.ground).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(Role.Button, onBack), contentAlignment = Alignment.Center) {
                    Text("‹", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_kicker), style = MaterialTheme.typography.labelSmall, color = c.muted)
                    Text(stringResource(R.string.library_title), style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), color = c.ink)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(c.ink).combinedClickableCompat(Role.Button, { showAdd = true })
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) { Text(stringResource(R.string.library_add), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
            }
        }
        if (state?.importing == true) {
            item { Note(stringResource(R.string.library_importing)) }
        }
        if (reading.isEmpty()) {
            item {
                Card {
                    Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                    Text(stringResource(R.string.library_empty_text), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
                    PrimaryButton(stringResource(R.string.library_import_epub), onImport)
                }
            }
        }
        items(reading, key = { "r${it.id}" }) { book ->
            ReadingCard(book, state!!.stats, onOpen, { logging = book }, { deleting = book })
        }
        if (state != null) item { WeekCard(state.stats) }
        if (state != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(stringResource(R.string.library_for_you), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.library_for_you_sub), style = MaterialTheme.typography.labelMedium, color = c.muted)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReadingTags.GOALS.forEach { tag ->
                            val on = tag in state.goals
                            Box(
                                Modifier.heightIn(min = 38.dp).clip(CircleShape).background(if (on) c.lilac else c.card)
                                    .combinedClickableCompat(Role.Checkbox, { onToggleGoal(tag) }).padding(horizontal = 14.dp, vertical = 9.dp)
                            ) { Text(goalLabel(tag), style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.inkSoft) }
                        }
                    }
                }
            }
            items(state.recommendations, key = { "rec${it.book.title}" }) { rec -> RecommendationCard(rec, onWant) }
        }
        if (wanted.isNotEmpty()) {
            item { Text(stringResource(R.string.library_want, wanted.size), style = MaterialTheme.typography.titleLarge, color = c.ink) }
            items(wanted, key = { "w${it.id}" }) { book ->
                ListRow(book, action = stringResource(R.string.library_start), onAction = { if (book.isEbook) onOpen(book) else onStart(book) }, onLong = { deleting = book })
            }
        }
        if (finished.isNotEmpty()) {
            item { Text(stringResource(R.string.library_finished, finished.size), style = MaterialTheme.typography.titleLarge, color = c.ink) }
            items(finished, key = { "f${it.id}" }) { book ->
                ListRow(book, action = if (book.rating > 0) "★".repeat(book.rating) else "", onAction = {}, onLong = { deleting = book })
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = Ancla.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card).padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) { content() }
}

@Composable
private fun Note(text: String) {
    val c = Ancla.colors
    Text(text, style = MaterialTheme.typography.labelLarge, color = c.mintInk, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.mintSoft).padding(12.dp))
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Ancla.colors
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(c.ink).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = c.onInk) }
}

@Composable
private fun SoftButton(label: String, onClick: () -> Unit) {
    val c = Ancla.colors
    Box(
        Modifier.clip(RoundedCornerShape(14.dp)).background(c.ground).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 14.dp, vertical = 11.dp)
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = c.inkSoft) }
}

@Composable
private fun ReadingCard(book: Book, stats: ReadingStats, onOpen: (Book) -> Unit, onLog: () -> Unit, onLong: () -> Unit) {
    val c = Ancla.colors
    val progress by animateFloatAsState(book.progress.toFloat(), label = "bookProgress")
    val left = ReadingStats.hoursLeft(book, stats.pagesPerHour)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.card)
            .combinedClickableCompat(Role.Button, onClick = { if (book.isEbook) onOpen(book) else onLog() }, onLongClick = onLong).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Cover(book.title, Modifier.width(70.dp).height(100.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(stringResource(R.string.library_reading_now), style = MaterialTheme.typography.labelSmall, color = c.mintInk)
                Text(book.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (book.pages > 0) stringResource(R.string.library_author_pages, book.author, book.currentPage, book.pages) else book.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.track)) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(c.mint))
                }
                Text(
                    stringResource(R.string.library_progress, (book.progress * 100).roundToInt(), "%.1f".format(left).replace(".0", "")),
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkSoft
                )
            }
        }
        if (book.isEbook) {
            PrimaryButton(stringResource(R.string.library_continue), { onOpen(book) }, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.library_log), onLog, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WeekCard(stats: ReadingStats) {
    val c = Ancla.colors
    val days = stringResource(R.string.library_week_days).split(",")
    val total = stats.weekMinutes.sum()
    val max = (stats.weekMinutes.maxOrNull() ?: 0).coerceAtLeast(30)
    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.library_this_week), style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(hours(total), style = MaterialTheme.typography.labelLarge, color = c.inkSoft)
        }
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            stats.weekMinutes.forEachIndexed { i, m ->
                val h by animateFloatAsState(m / max.toFloat(), label = "bar$i")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (m > 0) Text("$m'", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = c.inkSoft)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth().height((70 * h).dp.coerceAtLeast(6.dp)).clip(RoundedCornerShape(8.dp))
                            .background(if (m == 0) c.track else c.mint)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(days.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = c.muted)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(hours(stats.yearMinutes), stringResource(R.string.library_stat_year), Modifier.weight(1f))
            Stat("${stats.booksFinishedThisYear}", stringResource(R.string.library_stat_books), Modifier.weight(1f))
            Stat(if (stats.averageSessionMinutes > 0) "${stats.averageSessionMinutes} min" else "—", stringResource(R.string.library_stat_session), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val c = Ancla.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.ground).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = c.ink)
        Text(label, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = c.muted)
    }
}

@Composable
private fun RecommendationCard(rec: Recommendation, onWant: (Recommendation) -> Unit) {
    val c = Ancla.colors
    val why = when (rec.reason) {
        RecommendationReason.GOAL -> stringResource(R.string.rec_goal, goalLabel(rec.reasonArg))
        RecommendationReason.LIKED_BOOK -> stringResource(R.string.rec_liked, rec.reasonArg)
        RecommendationReason.POPULAR -> stringResource(R.string.rec_popular)
    }
    val hoursEstimate = (rec.book.pages / ReadingStats.DEFAULT_PAGES_PER_HOUR).roundToInt().coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Cover(rec.book.title, Modifier.width(52.dp).height(74.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rec.book.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(stringResource(R.string.rec_author_hours, rec.book.author, hoursEstimate), style = MaterialTheme.typography.labelMedium, color = c.muted)
            Text(why, style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            Spacer(Modifier.height(2.dp))
            SoftButton(stringResource(R.string.rec_want)) { onWant(rec) }
        }
    }
}

@Composable
private fun ListRow(book: Book, action: String, onAction: () -> Unit, onLong: () -> Unit) {
    val c = Ancla.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(c.card)
            .combinedClickableCompat(Role.Button, onClick = onAction, onLongClick = onLong).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Cover(book.title, Modifier.width(32.dp).height(46.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium, color = c.muted, maxLines = 1)
        }
        if (action.isNotEmpty()) Text(action, style = MaterialTheme.typography.labelLarge, color = if (action.startsWith("★")) c.amber else c.lilacInk)
    }
}

@Composable
private fun LogReadingDialog(book: Book, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val c = Ancla.colors
    var minutes by remember { mutableStateOf("20") }
    var page by remember { mutableStateOf(book.currentPage.toString()) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.mint, unfocusedBorderColor = c.track, focusedTextColor = c.ink, unfocusedTextColor = c.ink)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.card,
        title = { Text(stringResource(R.string.library_log), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.library_log_minutes)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = colors, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.library_log_page)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = colors, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(minutes.toIntOrNull() ?: 0, page.toIntOrNull() ?: book.currentPage) }, enabled = (minutes.toIntOrNull() ?: 0) > 0) {
                Text(stringResource(R.string.task_save), color = c.mintInk, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.task_cancel), color = c.muted) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBookSheet(
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onSearch: (String, (List<BookSearchResult>) -> Unit) -> Unit,
    onAdd: (BookSearchResult, Boolean) -> Unit
) {
    val c = Ancla.colors
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BookSearchResult>?>(null) }
    var chosen by remember { mutableStateOf<BookSearchResult?>(null) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedTextColor = c.ink, unfocusedTextColor = c.ink)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.library_add_title), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            PrimaryButton(stringResource(R.string.library_import_epub), onImport, Modifier.fillMaxWidth())
            Text(stringResource(R.string.library_paper), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    query, { query = it }, modifier = Modifier.weight(1f), singleLine = true, colors = colors, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text(stringResource(R.string.library_search_hint)) }
                )
                SoftButton(stringResource(R.string.library_search)) {
                    searching = true
                    chosen = null
                    onSearch(query) { results = it; searching = false }
                }
            }
            when {
                searching -> Note(stringResource(R.string.library_searching))
                results?.isEmpty() == true -> Note(stringResource(R.string.library_no_results))
            }
            val manual = BookSearchResult(query.trim(), "", 0, ReadingTags.fromSubjects(listOf(query)))
            val options = results.orEmpty().take(5)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { r ->
                    val selected = chosen == r
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) c.lilacSoft else c.ground)
                            .combinedClickableCompat(Role.RadioButton, { chosen = r }).padding(12.dp)
                    ) {
                        Column {
                            Text(r.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(r.author, if (r.pages > 0) "${r.pages} p." else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        }
                    }
                }
            }
            val pick = chosen ?: manual.takeIf { it.title.isNotBlank() }
            if (pick != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton(stringResource(R.string.library_add_want)) { onAdd(pick, false) }
                    PrimaryButton(stringResource(R.string.library_add_reading), { onAdd(pick, true) })
                }
            }
        }
    }
}
