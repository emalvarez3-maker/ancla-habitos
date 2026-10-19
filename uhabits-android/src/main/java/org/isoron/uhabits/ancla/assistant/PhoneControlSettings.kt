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

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

/** One permission Dexter's phone control can use: what it unlocks, whether it's granted, how to grant it. */
private class Grant(val title: Int, val detail: Int, val granted: (Context) -> Boolean, val open: (Context) -> Unit)

private fun settings(context: Context, action: String, withPackage: Boolean = false) {
    val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) intent.data = Uri.parse("package:" + context.packageName)
    runCatching { context.startActivity(intent) }.onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Dexter is the phone's digital assistant (hold home or power). */
private fun isAssistant(context: Context) =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

private val GRANTS = listOf(
    Grant(
        R.string.phone_grant_battery, R.string.phone_grant_battery_detail,
        { it.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(it.packageName) == true },
        { settings(it, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withPackage = true) }
    ),
    Grant(R.string.phone_grant_screen, R.string.phone_grant_screen_detail, { DexterAccessibility.enabled(it) }, { settings(it, Settings.ACTION_ACCESSIBILITY_SETTINGS) }),
    Grant(
        R.string.phone_grant_files, R.string.phone_grant_files_detail,
        { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() },
        { settings(it, Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, withPackage = true) }
    ),
    Grant(R.string.phone_grant_system, R.string.phone_grant_system_detail, { Settings.System.canWrite(it) }, { settings(it, Settings.ACTION_MANAGE_WRITE_SETTINGS, withPackage = true) }),
    Grant(
        R.string.phone_grant_dnd, R.string.phone_grant_dnd_detail,
        { it.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true },
        { settings(it, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
    ),
    Grant(R.string.phone_grant_assistant, R.string.phone_grant_assistant_detail, ::isAssistant, { settings(it, Settings.ACTION_VOICE_INPUT_SETTINGS) })
)

/** "Control del teléfono" in Dexter's settings: every permission, its state and a button to grant it. */
@Composable
fun PhoneControlSettings() {
    val c = Ancla.colors
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // The user grants things in Android's settings and comes back: re-check every second.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val contactsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Denied twice, Android stops asking: send the user to the app's notification settings.
        if (!granted) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
        tick++
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.titleMedium, color = c.ink)
        Text(stringResource(R.string.phone_explain), style = MaterialTheme.typography.bodyMedium, color = c.inkSoft)
        key(tick) {
            val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            GrantRow(stringResource(R.string.phone_grant_notifications), stringResource(R.string.phone_grant_notifications_detail), notifications) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            GRANTS.forEach { g ->
                GrantRow(stringResource(g.title), stringResource(g.detail), g.granted(context)) { g.open(context) }
            }
            val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
            GrantRow(stringResource(R.string.phone_grant_contacts), stringResource(R.string.phone_grant_contacts_detail), contacts) {
                contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
        val log = remember { DexterActionLog(context) }
        var paused by remember { mutableStateOf(log.paused) }
        var entries by remember { mutableStateOf(log.entries()) }
        LaunchedEffect(tick / 5) { entries = log.entries() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(stringResource(R.string.phone_active), !paused) {
                paused = false
                log.paused = false
            }
            Switch(stringResource(R.string.phone_paused), paused) {
                paused = true
                log.paused = true
            }
        }
        Text(stringResource(R.string.phone_paused_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
        Text(stringResource(R.string.phone_history), style = MaterialTheme.typography.titleSmall, color = c.ink)
        Text(
            if (entries.isEmpty()) stringResource(R.string.phone_history_empty) else entries.takeLast(15).asReversed().joinToString("\n"),
            style = MaterialTheme.typography.bodySmall, color = c.inkSoft
        )
        if (entries.isNotEmpty()) {
            Text(
                stringResource(R.string.phone_history_clear), style = MaterialTheme.typography.labelLarge, color = c.amberInk,
                modifier = Modifier.clip(CircleShape).background(c.ground).combinedClickableCompat(Role.Button, {
                    log.clear()
                    entries = emptyList()
                }).padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Text(stringResource(R.string.phone_safety), style = MaterialTheme.typography.bodySmall, color = c.muted)
        Text(stringResource(R.string.whatsapp_restricted_hint), style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun Switch(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Ancla.colors
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = if (on) c.onInk else c.inkSoft,
        modifier = Modifier.clip(CircleShape).background(if (on) c.ink else c.ground).combinedClickableCompat(Role.RadioButton, onClick).padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun GrantRow(title: String, detail: String, granted: Boolean, onGrant: () -> Unit) {
    val c = Ancla.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.ground).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text((if (granted) "✓ " else "") + title, style = MaterialTheme.typography.titleSmall, color = if (granted) c.mintInk else c.ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        if (!granted) {
            Text(
                stringResource(R.string.phone_grant),
                style = MaterialTheme.typography.labelLarge, color = c.lilacInk,
                modifier = Modifier.clip(CircleShape).background(c.lilacSoft).combinedClickableCompat(Role.Button, onGrant).padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
