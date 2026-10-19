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

import android.app.Activity
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * An invisible, instant screen that gives "Oye Dexter" its microphone back. Android silences the
 * microphone of a service started in the background (after a restart, an update, or when the
 * system recreated it), and only lifts that when the app is on screen. When the service notices,
 * it opens this for a fraction of a second after the phone is unlocked; while it is on top the
 * service is started again, which hands it the microphone, and it closes. It shows nothing.
 */
class DexterMicActivity : Activity() {
    override fun onResume() {
        super.onResume()
        ContextCompat.startForegroundService(this, Intent(this, DexterService::class.java).setAction(DexterService.ACTION_MIC))
        window.decorView.postDelayed({
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }, 350)
    }
}
