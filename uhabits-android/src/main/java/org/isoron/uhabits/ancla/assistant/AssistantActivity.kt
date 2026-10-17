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

package org.isoron.uhabits.ancla.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.Brote
import org.isoron.uhabits.ancla.ui.BroteMood
import org.isoron.uhabits.ancla.ui.BroteStage
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

/** Chat with Brote, the Claude-powered assistant, by voice or text. */
class AssistantActivity : AppCompatActivity() {
    private lateinit var controller: VoiceAgentController
    private val showSettings = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        controller = VoiceAgentController(this)
        showSettings.value = !controller.hasAssistantKey
        setContent {
            AnclaTheme {
                AssistantScreen(controller, showSettings.value, onSettings = { showSettings.value = it }, onBack = { finish() })
            }
        }
    }

    override fun onPause() {
        controller.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context) = Intent(context, AssistantActivity::class.java)
    }
}

@Composable
private fun AssistantScreen(controller: VoiceAgentController, settingsOpen: Boolean, onSettings: (Boolean) -> Unit, onBack: () -> Unit) {
    val c = Ancla.colors
    var input by remember { mutableStateOf("") }
    val turns = controller.turns.value
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, controller.thinking.value) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

    if (settingsOpen) KeysSheet(controller.keys) { onSettings(false) }

    Column(Modifier.fillMaxSize().background(c.ground).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).combinedClickableCompat(Role.Button, onBack), contentAlignment = Alignment.Center) {
                Text("‹", style = MaterialTheme.typography.titleLarge.copy(fontSize = 26.sp), color = c.ink)
            }
            Brote(
                mood = if (controller.thinking.value) BroteMood.CALM else BroteMood.HAPPY,
                stage = BroteStage.SPROUT,
                hopKey = turns.size,
                modifier = Modifier.size(44.dp)
            )
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(stringResource(R.string.assistant_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                Text(stringResource(R.string.assistant_subtitle), style = MaterialTheme.typography.labelMedium, color = c.muted)
            }
            Text(
                stringResource(R.string.assistant_keys),
                style = MaterialTheme.typography.labelLarge,
                color = c.lilacInk,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).combinedClickableCompat(Role.Button, { onSettings(true) }).padding(10.dp)
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = list,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Bubble(stringResource(R.string.assistant_welcome), mine = false)
            }
            turns.forEach { t ->
                item { Bubble(t.heard, mine = true) }
                t.actions.forEach { a -> item { Text("✓ $a", style = MaterialTheme.typography.titleMedium, color = c.mintInk, modifier = Modifier.padding(start = 6.dp)) } }
                if (t.reply.isNotBlank()) item { Bubble(t.reply, mine = false) }
            }
            controller.error.value?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = c.amberInk) } }
        }
        DictationPill(controller, Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                input, { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.assistant_input_hint)) },
                shape = RoundedCornerShape(20.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedTextColor = c.ink, unfocusedTextColor = c.ink)
            )
            if (input.isNotBlank()) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(c.ink)
                        .combinedClickableCompat(Role.Button, { controller.send(input.trim()); input = "" }),
                    contentAlignment = Alignment.Center
                ) { Text("↑", color = c.onInk, style = MaterialTheme.typography.titleLarge) }
            } else {
                MicButton(controller, size = 56)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val c = Ancla.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (mine) c.onInk else c.ink,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, if (mine) 6.dp else 20.dp, if (mine) 20.dp else 6.dp))
                .background(if (mine) c.lilac else c.card)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeysSheet(keys: SecureKeys, onDone: () -> Unit) {
    val c = Ancla.colors
    var claude by remember { mutableStateOf(keys.claudeKey) }
    var gemini by remember { mutableStateOf(keys.geminiKey) }
    var assistant by remember { mutableStateOf(keys.assistant) }
    var groq by remember { mutableStateOf(keys.groqKey) }
    var openai by remember { mutableStateOf(keys.openAiKey) }
    var provider by remember { mutableStateOf(keys.transcription) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.lilac, unfocusedBorderColor = c.track, focusedTextColor = c.ink, unfocusedTextColor = c.ink)
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.card) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.keys_title), style = MaterialTheme.typography.headlineSmall, color = c.ink)
            Text(stringResource(R.string.keys_privacy), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
            Text(stringResource(R.string.keys_assistant), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistantProvider.entries.forEach { p ->
                    val on = assistant == p
                    Text(
                        stringResource(if (p == AssistantProvider.GEMINI) R.string.keys_gemini_short else R.string.keys_claude_short),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) c.onInk else c.inkSoft,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.ground)
                            .combinedClickableCompat(Role.RadioButton, { assistant = p }).padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            if (assistant == AssistantProvider.GEMINI) {
                OutlinedTextField(gemini, { gemini = it }, label = { Text(stringResource(R.string.keys_gemini)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.keys_gemini_note), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            } else {
                OutlinedTextField(claude, { claude = it }, label = { Text(stringResource(R.string.keys_claude)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.keys_transcription), style = MaterialTheme.typography.titleMedium, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranscriptionProvider.entries.forEach { p ->
                    val on = provider == p
                    Text(
                        stringResource(
                            when (p) {
                                TranscriptionProvider.ANDROID -> R.string.keys_android
                                TranscriptionProvider.GROQ -> R.string.keys_groq_short
                                TranscriptionProvider.OPENAI -> R.string.keys_openai_short
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) c.onInk else c.inkSoft,
                        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.ground)
                            .combinedClickableCompat(Role.RadioButton, { provider = p }).padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            when (provider) {
                TranscriptionProvider.GROQ -> OutlinedTextField(groq, { groq = it }, label = { Text(stringResource(R.string.keys_groq)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                TranscriptionProvider.OPENAI -> OutlinedTextField(openai, { openai = it }, label = { Text(stringResource(R.string.keys_openai)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), colors = colors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                TranscriptionProvider.ANDROID -> Text(stringResource(R.string.keys_android_note), style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.ink)
                    .combinedClickableCompat(Role.Button, {
                        keys.claudeKey = claude
                        keys.geminiKey = gemini
                        keys.assistant = assistant
                        keys.groqKey = groq
                        keys.openAiKey = openai
                        keys.transcription = provider
                        onDone()
                    }).padding(14.dp),
                contentAlignment = Alignment.Center
            ) { Text(stringResource(R.string.task_save), style = MaterialTheme.typography.labelLarge, color = c.onInk) }
        }
    }
}
