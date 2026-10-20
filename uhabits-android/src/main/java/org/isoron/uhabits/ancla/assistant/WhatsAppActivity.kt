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
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import org.isoron.uhabits.ancla.ui.enableDexterEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.combinedClickableCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The saved WhatsApp messages, by chat, with search. The window is marked secure: no screenshots,
 * no screen recording and a blank thumbnail in recent apps.
 */
class WhatsAppActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableDexterEdgeToEdge()
        super.onCreate(savedInstanceState)
        val vault = WhatsAppVault(this)
        setContent {
            AnclaTheme {
                val c = Ancla.colors
                var state by remember { mutableStateOf<Result<List<ChatMessage>>?>(null) }
                var chat by remember { mutableStateOf<String?>(null) }
                var query by remember { mutableStateOf("") }
                LaunchedEffect(Unit) { state = withContext(Dispatchers.IO) { runCatching { vault.readAll() } } }
                BackHandler(chat != null) { chat = null }
                Column(Modifier.fillMaxSize().background(c.ground).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                            modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { if (chat != null) chat = null else finish() }).padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                        Text(chat ?: "WhatsApp", style = MaterialTheme.typography.titleLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (query.isEmpty()) Text(stringResource(R.string.whatsapp_search), style = MaterialTheme.typography.bodyLarge, color = c.muted)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink), cursorBrush = SolidColor(c.lilac), modifier = Modifier.fillMaxWidth())
                    }
                    val result = state
                    when {
                        result == null -> Note(stringResource(R.string.whatsapp_loading))
                        result.isFailure -> Note(
                            stringResource(if (result.exceptionOrNull() is WhatsAppVault.LockedException) R.string.whatsapp_locked else R.string.whatsapp_read_error)
                        )
                        else -> {
                            val all = result.getOrThrow()
                            val q = ChatNotifications.norm(query)
                            val shown = all.filter { (chat == null || it.chat == chat) && (q.isEmpty() || ChatNotifications.norm(it.text + " " + it.sender + " " + it.chat).contains(q)) }
                            if (shown.isEmpty()) Note(stringResource(if (all.isEmpty()) R.string.whatsapp_empty else R.string.notes_no_match))
                            if (chat == null && q.isEmpty()) {
                                val chats = shown.groupBy { it.chat }.toList().sortedByDescending { it.second.last().time }
                                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(chats, key = { it.first }) { (name, msgs) ->
                                        Column(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
                                                .combinedClickableCompat(Role.Button, { chat = name }).padding(14.dp)
                                        ) {
                                            Text("$name · ${msgs.size}", style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val last = msgs.last()
                                            Text(
                                                time(last.time) + "  " + (if (last.group) last.sender + ": " else "") + last.text,
                                                style = MaterialTheme.typography.bodyMedium, color = c.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(shown.asReversed(), key = { it.key }) { m ->
                                        val mine = m.sender == ChatNotifications.ME
                                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (mine) c.lilacSoft else c.card).padding(12.dp)) {
                                            Text(
                                                time(m.time) + " · " + (if (chat == null) m.chat + " · " else "") + m.sender,
                                                style = MaterialTheme.typography.labelMedium, color = c.muted
                                            )
                                            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, WhatsAppActivity::class.java)
    }
}

private fun time(t: Long) = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(t))

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Ancla.colors.inkSoft, modifier = Modifier.padding(24.dp))

/** The WhatsApp section of Dexter's settings: access, the on/off switch, retention, viewing and wiping. */
@Composable
fun WhatsAppSettings(usingGemini: Boolean) {
    val c = Ancla.colors
    val context = LocalContext.current
    val vault = remember { WhatsAppVault(context) }
    var confirmWipe by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(vault.hasAccess) }
    var secure by remember { mutableStateOf(vault.deviceSecure) }
    // The user grants access or sets a screen lock in Android's settings and comes back: re-check.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            access = vault.hasAccess
            secure = vault.deviceSecure
        }
    }
    var enabled by remember { mutableStateOf(vault.enabled) }
    var days by remember { mutableIntStateOf(vault.retentionDays) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.whatsapp_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(stringResource(R.string.whatsapp_explain), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        Text(
            listOf(
                stringResource(if (secure) R.string.whatsapp_lock_ok else R.string.whatsapp_lock_missing),
                stringResource(if (access) R.string.whatsapp_access_ok else R.string.whatsapp_access_missing)
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodyMedium, color = if (secure && access) c.mintInk else c.amberInk
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(stringResource(if (access) R.string.whatsapp_access_manage else R.string.whatsapp_access_grant)) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            if (!secure) Action(stringResource(R.string.whatsapp_set_lock)) { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        }
        Text(stringResource(R.string.whatsapp_restricted_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.whatsapp_on), enabled) {
                if (secure) {
                    enabled = true
                    vault.enabled = true
                }
            }
            Choice(stringResource(R.string.whatsapp_off), !enabled) {
                enabled = false
                vault.enabled = false
            }
        }
        if (!secure) Text(stringResource(R.string.whatsapp_needs_lock), style = MaterialTheme.typography.bodySmall, color = c.amberInk)
        Text(stringResource(R.string.whatsapp_retention), style = MaterialTheme.typography.labelLarge, color = c.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 7, 30).forEach { d ->
                Choice(stringResource(R.string.whatsapp_days, d), days == d) {
                    days = d
                    vault.retentionDays = d
                }
            }
        }
        if (usingGemini) Text(stringResource(R.string.whatsapp_gemini_warning), style = MaterialTheme.typography.bodySmall, color = c.amberInk)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Action(stringResource(R.string.whatsapp_view)) { context.startActivity(WhatsAppActivity.intent(context)) }
            Action(stringResource(R.string.whatsapp_wipe)) { confirmWipe = true }
        }
    }
    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text(stringResource(R.string.whatsapp_wipe)) },
            text = { Text(stringResource(R.string.whatsapp_wipe_confirm)) },
            confirmButton = {
                TextButton({
                    vault.wipe()
                    confirmWipe = false
                }) { Text(stringResource(R.string.notes_delete)) }
            },
            dismissButton = { TextButton({ confirmWipe = false }) { Text(stringResource(R.string.study_cancel)) } }
        )
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = c.lilacInk,
        modifier = Modifier.clip(CircleShape).background(c.lilacSoft).combinedClickableCompat(Role.Button, onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.ground).combinedClickableCompat(Role.RadioButton, onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}
