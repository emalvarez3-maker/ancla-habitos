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

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens the connection to Google's API while the user is still talking, so the TCP and TLS
 * handshakes (a few hundred milliseconds on mobile data) are done before the request is ready.
 * HttpURLConnection keeps the socket in its pool once a response is read to the end and closed,
 * and the chat and voice requests reuse it. Nothing personal is sent: no key, no body.
 */
object Warmup {
    private const val GOOGLE = "https://generativelanguage.googleapis.com/"
    private const val EVERY_MS = 20_000L

    @Volatile private var last = 0L

    fun google() {
        val now = System.currentTimeMillis()
        if (now - last < EVERY_MS) return
        last = now
        Thread {
            runCatching {
                val conn = (URL(GOOGLE).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                // Read to the end and close (not disconnect) so the socket goes back to the pool.
                (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            }
        }.apply { name = "ancla-warmup" }.start()
    }
}
