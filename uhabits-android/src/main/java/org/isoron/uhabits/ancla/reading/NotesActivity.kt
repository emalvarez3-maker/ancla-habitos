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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import org.isoron.uhabits.ancla.ui.enableDexterEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.assistant.AgentException
import org.isoron.uhabits.ancla.assistant.AssistantActivity
import org.isoron.uhabits.ancla.assistant.DexterService
import org.isoron.uhabits.ancla.assistant.Dictation
import org.isoron.uhabits.ancla.assistant.DictationMode
import org.isoron.uhabits.ancla.assistant.DictationState
import org.isoron.uhabits.ancla.assistant.GeminiText
import org.isoron.uhabits.ancla.assistant.SecureKeys
import org.isoron.uhabits.ancla.robotics.RoboticsActivity
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme

/**
 * Ancla's second brain: Markdown notes with [[links]], backlinks, a daily journal, a graph and
 * study sessions with charts, all in one place, with Wispr-Flow-style dictation into any note.
 */
class NotesActivity : AppCompatActivity() {
    private lateinit var graph: IdeaGraph
    private lateinit var study: StudyStore
    private val keys by lazy { SecureKeys(this) }
    private val version = mutableIntStateOf(0)
    private val tab = mutableStateOf(NotesTab.NOTES)
    private val draft = mutableStateOf<NoteDraft?>(null)
    private val deleting = mutableStateOf(false)
    private val dictation = mutableStateOf<DictationState>(DictationState.Idle)
    private var pendingMode: DictationMode? = null

    private val engine by lazy {
        Dictation(
            this,
            keys,
            onState = { dictation.value = it },
            onTranscript = { text -> draft.value?.insert(text) },
            onError = { message -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show() },
            onAudio = { file ->
                val audio = file.readBytes().also { file.delete() }
                dictation.value = DictationState.Transcribing
                Thread {
                    val result = runCatching { GeminiText.transcribe(keys.geminiKey, audio) }
                    runOnUiThread {
                        dictation.value = DictationState.Idle
                        result.onSuccess { draft.value?.insert(it) }.onFailure {
                            val msg = if ((it as? AgentException)?.kind == AgentException.Kind.RATE_LIMIT) R.string.voice_rate_limit else R.string.voice_failed
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            },
            preferGeminiAudio = true
        )
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingMode
        pendingMode = null
        if (granted && mode != null) engine.start(mode) else if (!granted) Toast.makeText(this, R.string.voice_need_mic, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableDexterEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = IdeaGraph(this)
        study = StudyStore(this)
        handle(intent)
        setContent {
            AnclaTheme {
                version.intValue // re-read the stores whenever something changes
                val c = Ancla.colors
                val d = draft.value
                BackHandler(d != null) { closeDraft() }
                LaunchedEffect(d, d?.title, d?.body?.text) {
                    if (d != null) {
                        delay(700)
                        saveDraft()
                    }
                }
                Box(Modifier.fillMaxSize().background(c.ground).statusBarsPadding()) {
                    if (d != null) {
                        val idea = graph.byId(d.id)
                        NoteEditor(
                            draft = d,
                            idea = idea,
                            titles = { q -> graph.search(q).filter { it.id != d.id }.map { it.title } },
                            exists = { t -> graph.find(t)?.let { IdeaGraph.key(it.title) == IdeaGraph.key(t) } == true },
                            backlinks = idea?.let(graph::backlinks).orEmpty(),
                            mentions = idea?.let(graph::mentions).orEmpty(),
                            labeled = idea?.let { i ->
                                graph.linksOf(i.id).filter { !it.implicit }.mapNotNull { l ->
                                    graph.byId(if (l.from == i.id) l.to else l.from)?.let { other -> l.relation to other }
                                }
                            }.orEmpty(),
                            dictation = dictation.value,
                            onDictateHold = { dictate(DictationMode.HOLD) },
                            onDictateToggle = { if (engine.isActive) engine.stop() else dictate(DictationMode.HANDS_FREE) },
                            onDictateStop = { engine.stop() },
                            onLink = ::followLink,
                            onOpen = ::open,
                            onBack = ::closeDraft,
                            onDelete = { deleting.value = true }
                        )
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            val ideas = graph.ideas
                            val links = graph.links
                            NotesHeader(tab.value, { tab.value = it }, { finish() }, stringResource(R.string.notes_count, ideas.size, links.size))
                            when (tab.value) {
                                NotesTab.NOTES -> NotesList(
                                    ideas = ideas,
                                    search = graph::search,
                                    linkCount = { i -> links.count { it.from == i.id || it.to == i.id } },
                                    onOpen = ::open,
                                    onNew = { open(graph.create(getString(R.string.notes_untitled) + " " + (ideas.size + 1))) },
                                    onToday = { open(graph.journal()) }
                                )
                                NotesTab.GRAPH -> NotesGraph(ideas, links, ::open) {
                                    startActivity(AssistantActivity.intent(this@NotesActivity).putExtra(AssistantActivity.EXTRA_PROMPT, getString(R.string.ideas_dexter_prompt)))
                                }
                                NotesTab.STUDY -> StudyScreen(
                                    topics = study.topics,
                                    sessions = study.sessions,
                                    active = study.active,
                                    stats = study.stats(),
                                    onStart = { study.start(it); changed() },
                                    onStop = ::finishStudy,
                                    onCancel = { study.cancel(); changed() },
                                    onAddTopic = { name, goal -> study.addTopic(name, goal); changed() },
                                    onRemoveTopic = { study.removeTopic(it); changed() },
                                    onOpenSession = { s -> graph.byId(s.noteId)?.let(::open) },
                                    onRobotics = { startActivity(RoboticsActivity.intent(this@NotesActivity)) }
                                )
                            }
                        }
                    }
                    if (deleting.value && d != null) {
                        AlertDialog(
                            onDismissRequest = { deleting.value = false },
                            title = { Text(stringResource(R.string.notes_delete_title)) },
                            text = { Text(d.title) },
                            confirmButton = {
                                TextButton({
                                    deleting.value = false
                                    graph.delete(d.id)
                                    draft.value = null
                                    changed()
                                }) { Text(stringResource(R.string.notes_delete)) }
                            },
                            dismissButton = { TextButton({ deleting.value = false }) { Text(stringResource(R.string.study_cancel)) } }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        when (intent.getStringExtra(EXTRA_TAB)) {
            "graph" -> tab.value = NotesTab.GRAPH
            "study" -> tab.value = NotesTab.STUDY
        }
        intent.getStringExtra(EXTRA_OPEN)?.let { title -> graph.find(title)?.let(::open) }
    }

    override fun onResume() {
        super.onResume()
        DexterService.instance?.pause()
        changed() // Dexter may have written notes or sessions meanwhile
    }

    override fun onPause() {
        saveDraft()
        engine.cancel()
        DexterService.instance?.resume()
        super.onPause()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }

    private fun changed() {
        version.intValue++
    }

    private fun open(idea: Idea) {
        saveDraft()
        engine.cancel()
        draft.value = NoteDraft(idea.id, idea.title, idea.note)
        changed()
    }

    private fun saveDraft() {
        val d = draft.value ?: return
        val before = graph.byId(d.id) ?: return
        if (before.title == d.title.trim().ifBlank { before.title } && before.note == d.body.text) return
        graph.edit(d.id, d.title, d.body.text)
        changed()
    }

    private fun closeDraft() {
        saveDraft()
        engine.cancel()
        draft.value = null
        changed()
    }

    /** [[Link]] tap: opens the note, or creates it first, like Obsidian. */
    private fun followLink(title: String) {
        val target = graph.find(title)?.takeIf { IdeaGraph.key(it.title) == IdeaGraph.key(title) } ?: graph.create(title)
        open(target)
    }

    private fun dictate(mode: DictationMode) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            engine.start(mode)
        } else {
            pendingMode = mode
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** Ends the study session and opens its note, ready for a dictated summary. */
    private fun finishStudy() {
        val session = study.stop()
        changed()
        if (session == null) {
            Toast.makeText(this, R.string.study_too_short, Toast.LENGTH_LONG).show()
            return
        }
        graph.byId(session.noteId)?.let { note ->
            open(note)
            draft.value?.editing = true
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_OPEN = "open"

        fun intent(context: Context, tab: String? = null, open: String? = null) =
            Intent(context, NotesActivity::class.java).putExtra(EXTRA_TAB, tab).putExtra(EXTRA_OPEN, open)
    }
}
