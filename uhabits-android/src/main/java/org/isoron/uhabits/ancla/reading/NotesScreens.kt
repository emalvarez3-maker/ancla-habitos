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

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.DictationState
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

enum class NotesTab { NOTES, GRAPH, STUDY }

@Composable
fun kindColor(kind: IdeaKind): Color = when (kind) {
    IdeaKind.IDEA -> Ancla.colors.lilac
    IdeaKind.SUMMARY -> Ancla.colors.mint
    IdeaKind.QUOTE -> Ancla.colors.amber
    IdeaKind.JOURNAL -> Ancla.colors.blush
    IdeaKind.STUDY -> Ancla.colors.inkSoft
}

@Composable
fun kindLabel(kind: IdeaKind): String = stringResource(
    when (kind) {
        IdeaKind.IDEA -> R.string.ideas_kind_idea
        IdeaKind.SUMMARY -> R.string.ideas_kind_summary
        IdeaKind.QUOTE -> R.string.ideas_kind_quote
        IdeaKind.JOURNAL -> R.string.notes_kind_journal
        IdeaKind.STUDY -> R.string.notes_kind_study
    }
)

/** Header shared by the three tabs: back, title and the Notas / Grafo / Estudio switch. */
@Composable
fun NotesHeader(tab: NotesTab, onTab: (NotesTab) -> Unit, onBack: () -> Unit, subtitle: String) {
    val c = Ancla.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                style = MaterialTheme.typography.headlineMedium,
                color = c.ink,
                modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onBack).padding(horizontal = 12.dp, vertical = 2.dp)
                    .semantics { contentDescription = "Volver" }
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.notes_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = c.muted)
            }
        }
        Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(16.dp)).background(c.card).padding(4.dp)) {
            NotesTab.entries.forEach { t ->
                val on = t == tab
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) c.ink else Color.Transparent)
                        .combinedClickableCompat(Role.Tab, { onTab(t) }).padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(
                            when (t) {
                                NotesTab.NOTES -> R.string.notes_tab_notes
                                NotesTab.GRAPH -> R.string.notes_tab_graph
                                NotesTab.STUDY -> R.string.notes_tab_study
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) c.onInk else c.inkSoft
                    )
                }
            }
        }
    }
}

@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.card)
            .combinedClickableCompat(Role.Button, onClick).padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
fun NotesList(
    ideas: List<Idea>,
    search: (String) -> List<Idea>,
    linkCount: (Idea) -> Int,
    onOpen: (Idea) -> Unit,
    onNew: () -> Unit,
    onToday: () -> Unit
) {
    val c = Ancla.colors
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<IdeaKind?>(null) }
    val shown = remember(ideas, query, filter) { search(query).filter { filter == null || it.kind == filter } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (query.isEmpty()) Text(stringResource(R.string.notes_search), style = MaterialTheme.typography.bodyLarge, color = c.muted)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                    cursorBrush = SolidColor(c.lilac), modifier = Modifier.fillMaxWidth()
                )
            }
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(c.ink).combinedClickableCompat(Role.Button, onNew).padding(horizontal = 16.dp, vertical = 12.dp)
            ) { Text(stringResource(R.string.notes_new), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
        }
        Row(
            Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip(stringResource(R.string.notes_today), false, onToday)
            Chip(stringResource(R.string.notes_all), filter == null) { filter = null }
            IdeaKind.entries.forEach { k -> Chip(kindLabel(k), filter == k) { filter = if (filter == k) null else k } }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(if (ideas.isEmpty()) R.string.notes_empty else R.string.notes_no_match),
                style = MaterialTheme.typography.bodyLarge, color = c.inkSoft,
                modifier = Modifier.padding(32.dp)
            )
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(shown, key = { it.id }) { idea ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card)
                        .combinedClickableCompat(Role.Button, { onOpen(idea) }).padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(kindColor(idea.kind)))
                        Text(idea.title, style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    val preview = idea.note.lineSequence().map { it.trim().trimStart('#', '-', '>', ' ') }.firstOrNull { it.isNotBlank() }.orEmpty()
                    if (preview.isNotBlank()) {
                        Text(preview.replace("[[", "").replace("]]", "").replace("**", ""), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                    val links = linkCount(idea)
                    Text(
                        listOfNotNull(
                            kindLabel(idea.kind),
                            idea.source.takeIf { it.isNotBlank() },
                            DateUtils.getRelativeTimeSpanString(idea.updated).toString(),
                            if (links > 0) pluralLinks(links) else null
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun pluralLinks(n: Int) = if (n == 1) stringResource(R.string.notes_one_link) else stringResource(R.string.notes_links, n)

// --- Markdown with [[links]] ---

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|\\[\\[([^\\]|#\\n]+)(?:[|#]([^\\]\\n]*))?]]|`([^`\\n]+)`|(?<![*\\w])\\*([^*\\n]+)\\*")

/** Renders the note's Markdown: headings, lists, quotes, bold, italics, code and clickable [[links]]. */
@Composable
fun MarkdownNote(text: String, exists: (String) -> Boolean, onLink: (String) -> Unit) {
    val c = Ancla.colors
    val body = MaterialTheme.typography.bodyLarge
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.size(4.dp))
                line.startsWith("### ") -> Text(inline(line.drop(4), exists, onLink), style = MaterialTheme.typography.titleMedium, color = c.ink)
                line.startsWith("## ") -> Text(inline(line.drop(3), exists, onLink), style = MaterialTheme.typography.titleLarge, color = c.ink)
                line.startsWith("# ") -> Text(inline(line.drop(2), exists, onLink), style = MaterialTheme.typography.headlineSmall, color = c.ink)
                line.startsWith("> ") -> Row {
                    Box(Modifier.size(width = 3.dp, height = 22.dp).background(c.lilac))
                    Text(inline(line.drop(2), exists, onLink), style = body.copy(fontStyle = FontStyle.Italic), color = c.inkSoft, modifier = Modifier.padding(start = 10.dp))
                }
                Regex("^\\s*[-*] \\[( |x)] ").containsMatchIn(line) -> {
                    val done = line.contains("[x]")
                    Text(inline((if (done) "☑ " else "☐ ") + line.substringAfter("] "), exists, onLink), style = body.copy(textDecoration = if (done) TextDecoration.LineThrough else null), color = if (done) c.muted else c.ink)
                }
                Regex("^\\s*[-*] ").containsMatchIn(line) -> {
                    val indent = line.takeWhile { it == ' ' }.length / 2
                    Text(inline("•  " + line.trimStart().drop(2), exists, onLink), style = body, color = c.ink, modifier = Modifier.padding(start = (indent * 16).dp))
                }
                else -> Text(inline(line, exists, onLink), style = body, color = c.ink)
            }
        }
    }
}

@Composable
private fun inline(text: String, exists: (String) -> Boolean, onLink: (String) -> Unit): AnnotatedString {
    val c = Ancla.colors
    return buildAnnotatedString {
        var at = 0
        INLINE.findAll(text).forEach { m ->
            append(text.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[1]) }
                g[2].isNotEmpty() -> {
                    val target = g[2].trim()
                    val real = exists(target)
                    withLink(
                        LinkAnnotation.Clickable(
                            target,
                            TextLinkStyles(SpanStyle(color = if (real) c.lilacInk else c.muted, fontWeight = FontWeight.SemiBold, textDecoration = if (real) null else TextDecoration.Underline))
                        ) { onLink(target) }
                    ) { append(g[3].ifBlank { target }) }
                }
                g[4].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.track)) { append(g[4]) }
                g[5].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[5]) }
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}

// --- Editor ---

/** One note open for reading or editing; title and body are hoisted so dictation can type into them. */
class NoteDraft(val id: String, title: String, body: String) {
    var title by mutableStateOf(title)
    var body by mutableStateOf(TextFieldValue(body, TextRange(body.length)))
    var editing by mutableStateOf(body.isBlank())

    /** Inserts dictated text at the cursor, with a space or line break as needed. */
    fun insert(text: String) {
        val v = body
        val before = v.text.substring(0, v.selection.min)
        val spacer = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val piece = spacer + text.trim()
        val newText = before + piece + v.text.substring(v.selection.max)
        body = TextFieldValue(newText, TextRange(before.length + piece.length))
        editing = true
    }
}

@Composable
fun NoteEditor(
    draft: NoteDraft,
    idea: Idea?,
    titles: (String) -> List<String>,
    exists: (String) -> Boolean,
    backlinks: List<Idea>,
    mentions: List<Idea>,
    labeled: List<Pair<String, Idea>>,
    dictation: DictationState,
    onDictateHold: () -> Unit,
    onDictateToggle: () -> Unit,
    onDictateStop: () -> Unit,
    onLink: (String) -> Unit,
    onOpen: (Idea) -> Unit,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    val c = Ancla.colors
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, onBack).padding(horizontal = 12.dp, vertical = 2.dp)
            )
            Text(
                idea?.let { kindLabel(it.kind) + " · " + DateUtils.getRelativeTimeSpanString(it.updated) }.orEmpty(),
                style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f)
            )
            Chip(stringResource(if (draft.editing) R.string.notes_read else R.string.notes_edit), false) { draft.editing = !draft.editing }
            Spacer(Modifier.size(6.dp))
            Chip(stringResource(R.string.notes_delete), false, onDelete)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            BasicTextField(
                draft.title, { draft.title = it.replace("\n", " ") },
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = c.ink),
                cursorBrush = SolidColor(c.lilac), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            if (draft.editing) {
                Box {
                    if (draft.body.text.isEmpty()) Text(stringResource(R.string.notes_body_hint), style = MaterialTheme.typography.bodyLarge, color = c.muted)
                    BasicTextField(
                        draft.body, { draft.body = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink, lineHeight = 26.sp),
                        cursorBrush = SolidColor(c.lilac), modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)
                    )
                }
            } else {
                MarkdownNote(draft.body.text, exists, onLink)
            }
            if (!draft.editing) {
                Connections(stringResource(R.string.notes_backlinks), backlinks.map { "" to it }, onOpen)
                Connections(stringResource(R.string.notes_connections), labeled, onOpen)
                Connections(stringResource(R.string.notes_mentions), mentions.map { "" to it }, onOpen)
            }
            Spacer(Modifier.size(24.dp))
        }

        // [[ autocomplete: suggests note titles while typing a link
        val v = draft.body
        val open = Regex("\\[\\[([^\\]\\n]*)$").find(v.text.substring(0, v.selection.min))
        if (draft.editing && open != null) {
            val suggestions = titles(open.groupValues[1]).take(6)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (suggestions.ifEmpty { listOf(open.groupValues[1]).filter { it.isNotBlank() } }).forEach { t ->
                    Chip(t, false) {
                        val start = open.range.first + 2
                        val text = v.text.substring(0, start) + t + "]]" + v.text.substring(v.selection.min)
                        draft.body = TextFieldValue(text, TextRange(start + t.length + 2))
                    }
                }
            }
        }
        DictationBar(dictation, onDictateHold, onDictateToggle, onDictateStop)
    }
}

@Composable
private fun Connections(title: String, items: List<Pair<String, Idea>>, onOpen: (Idea) -> Unit) {
    if (items.isEmpty()) return
    val c = Ancla.colors
    Text(title, style = MaterialTheme.typography.titleSmall, color = c.muted, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
    items.forEach { (relation, other) ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card)
                .combinedClickableCompat(Role.Button, { onOpen(other) }).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(kindColor(other.kind)))
            if (relation.isNotBlank()) Text(relation, style = MaterialTheme.typography.labelMedium, color = c.muted)
            Text(other.title, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(6.dp))
    }
}

/**
 * Wispr-Flow-style dictation for notes: tap the mic for hands-free (tap again to finish), or hold it
 * to talk and release to type. The clean text lands where the cursor is.
 */
@Composable
fun DictationBar(state: DictationState, onHold: () -> Unit, onToggle: () -> Unit, onStop: () -> Unit) {
    val c = Ancla.colors
    val scope = rememberCoroutineScope()
    val listening = state is DictationState.Listening
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(c.card).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when (state) {
                is DictationState.Listening -> state.partial.ifBlank { stringResource(R.string.notes_listening) }
                DictationState.Transcribing -> stringResource(R.string.notes_transcribing)
                DictationState.Idle -> stringResource(R.string.notes_dictate_hint)
            },
            style = MaterialTheme.typography.bodyMedium, color = if (listening) c.ink else c.muted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
        )
        var holdJob by remember { mutableStateOf<Job?>(null) }
        var holding by remember { mutableStateOf(false) }
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (listening) Color(0xFFE5484D) else c.lilac)
                .semantics { contentDescription = "Dictar" }
                .pointerInput(listening) {
                    detectTapGestures(onPress = {
                        if (listening) {
                            onStop()
                            return@detectTapGestures
                        }
                        holding = false
                        holdJob = scope.launch {
                            delay(250)
                            holding = true
                            onHold()
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        if (holding) {
                            holding = false
                            onStop()
                        } else {
                            onToggle()
                        }
                    })
                },
            contentAlignment = Alignment.Center
        ) { Text(if (state == DictationState.Transcribing) "…" else "🎙", style = MaterialTheme.typography.titleLarge, color = Color.White) }
    }
}
