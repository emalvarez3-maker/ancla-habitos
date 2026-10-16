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

import org.isoron.uhabits.core.models.ReadingTags
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BookSearchResult(val title: String, val author: String, val pages: Int, val tags: Set<String>)

/** Book search through Open Library's public API (no account or key needed). */
object OpenLibrary {
    /** Blocking; call off the main thread. Returns an empty list on network errors. */
    fun search(query: String, limit: Int = 8): List<BookSearchResult> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = URL("https://openlibrary.org/search.json?q=$q&fields=title,author_name,number_of_pages_median,subject&limit=$limit")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "Ancla/0.3 (Android habit tracker)")
        }
        return try {
            if (conn.responseCode != 200) return emptyList()
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val docs = json.optJSONArray("docs") ?: return emptyList()
            (0 until docs.length()).map { i ->
                val d = docs.getJSONObject(i)
                val subjects = d.optJSONArray("subject")?.let { a -> (0 until minOf(a.length(), 30)).map { a.optString(it) } }.orEmpty()
                BookSearchResult(
                    title = d.optString("title"),
                    author = d.optJSONArray("author_name")?.optString(0).orEmpty(),
                    pages = d.optInt("number_of_pages_median", 0),
                    tags = ReadingTags.fromSubjects(subjects)
                )
            }.filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }
}
