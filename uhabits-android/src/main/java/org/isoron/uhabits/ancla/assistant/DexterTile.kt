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
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import org.isoron.uhabits.R

/**
 * "Oye Dexter" in the Quick Settings panel: one tap from anywhere turns always-listening on or off,
 * and the tile shows whether Dexter is listening. Without the microphone permission it opens
 * Dexter instead, where the user can grant it.
 */
class DexterTile : TileService() {
    private val keys by lazy { SecureKeys(this) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            openDexter()
            return
        }
        if (DexterService.instance != null) {
            keys.alwaysListening = false
            DexterService.stop(this)
        } else {
            keys.alwaysListening = true
            DexterService.start(this)
        }
        refresh(listening = DexterService.instance == null)
    }

    private fun refresh(listening: Boolean = DexterService.instance != null) {
        val tile = qsTile ?: return
        tile.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.dexter_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (listening) R.string.dexter_tile_on else R.string.dexter_tile_off)
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openDexter() {
        val intent = Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the panel to redraw the tile, after the service starts or stops by other means. */
        fun update(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, DexterTile::class.java)) }
        }
    }
}
