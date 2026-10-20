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
import android.util.Log
import androidx.activity.compose.setContent
import org.isoron.uhabits.ancla.ui.enableDexterEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.isoron.uhabits.R
import org.isoron.uhabits.ancla.ui.Ancla
import org.isoron.uhabits.ancla.ui.AnclaTheme
import org.isoron.uhabits.ancla.ui.combinedClickableCompat

/**
 * One section of Dexter's setup on a screen of its own, so Dexter can open exactly the part that
 * is missing ("te abrí la pantalla: toca Activar en WhatsApp") instead of sending the user to dig
 * through its settings. Behind the app lock like the rest of the app.
 */
class DexterSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableDexterEdgeToEdge()
        super.onCreate(savedInstanceState)
        val section = intent.getStringExtra(EXTRA_SECTION) ?: PHONE
        val keys = SecureKeys(this)
        setContent {
            AnclaTheme {
                val c = Ancla.colors
                Column(Modifier.fillMaxSize().background(c.ground).statusBarsPadding().navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "‹", style = MaterialTheme.typography.headlineMedium, color = c.ink,
                            modifier = Modifier.clip(CircleShape).combinedClickableCompat(Role.Button, { finish() }).padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge, color = c.ink)
                    }
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (section) {
                            WHATSAPP -> WhatsAppSettings(usingGemini = keys.assistant == AssistantProvider.GEMINI)
                            else -> PhoneControlSettings()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SECTION = "section"
        const val PHONE = "phone"
        const val WHATSAPP = "whatsapp"

        fun intent(context: Context, section: String) =
            Intent(context, DexterSetupActivity::class.java).putExtra(EXTRA_SECTION, section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * Opens [section] and returns what Dexter tells the user: the row ([permission]) whose
         * «Activar» to tap, or the exact [step] when the button says something else. If Android
         * won't open it, how to get there by hand.
         */
        fun ask(context: Context, section: String, permission: String, step: String = "toca «Activar» en «$permission»"): String =
            if (open(context, section)) {
                "Te abrí la configuración: $step, y vuelve a pedírmelo."
            } else {
                "Necesito «$permission». Abre Dexter, toca el engranaje y luego «$permission»."
            }

        /** Opens [section] for the user; false when Android refused (from the background without permission). */
        fun open(context: Context, section: String): Boolean = runCatching { context.startActivity(intent(context, section)) }
            .onFailure { Log.w("AnclaVoice", "setup screen refused: ${it.javaClass.simpleName}") }
            .isSuccess
    }
}
