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

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FreeBook(val id: Int, val title: String, val author: String, val language: String)

/**
 * Public-domain books from Project Gutenberg, through its own OPDS catalog search (fast, no key).
 * Everything Gutenberg hosts is free to download legally.
 */
object FreeBooks {
    private const val SITE = "https://www.gutenberg.org"

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10000
        readTimeout = 20000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Ancla/0.3 (Android reading app)")
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").trim()

    /** Blocking. Spanish editions first. */
    fun search(query: String, limit: Int = 8): List<FreeBook> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val conn = get("$SITE/ebooks/search.opds/?query=$q")
        val xml = try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val entry = m.groupValues[1]
            val id = Regex("/ebooks/(\\d+)\\.opds</id>").find(entry)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val rawTitle = unescape(Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(entry)?.groupValues?.get(1).orEmpty())
            // Non-English titles end in "(Spanish)", "(German)"…
            val lang = Regex("\\(([A-Z][a-z]+)\\)$").find(rawTitle)?.groupValues?.get(1) ?: "English"
            val author = unescape(Regex("<content type=\"text\">([^<]*)").find(entry)?.groupValues?.get(1).orEmpty())
            FreeBook(id, rawTitle.removeSuffix("($lang)").trim(), author, lang)
        }.sortedBy { if (it.language == "Spanish") 0 else 1 }.take(limit).toList()
    }

    /** Blocking. Saves the EPUB of Gutenberg book [id] to [target]. */
    fun download(id: Int, target: File) {
        val conn = get("$SITE/ebooks/$id.epub3.images")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }
}
