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

import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * A minimal EPUB 2/3 reader: finds the package document, reads the metadata, the spine
 * (reading order) and serves entries by path. Chapters are rendered by a WebView.
 */
class EpubBook(file: File) : Closeable {
    private val zip = ZipFile(file)

    val title: String
    val author: String
    val subjects: List<String>
    /** Zip paths of the chapters, in reading order. */
    val chapters: List<String>
    val coverPath: String?
    /** Approximate visible characters per chapter, used for progress and page estimates. */
    val chapterChars: List<Int>

    init {
        val container = text("META-INF/container.xml") ?: error("Not an EPUB: missing container.xml")
        val opfPath = attr(Regex("<rootfile\\b[^>]*>").find(container)?.value ?: "", "full-path")
            ?: error("Not an EPUB: no rootfile")
        val opf = text(opfPath) ?: error("Not an EPUB: missing $opfPath")
        val base = opfPath.substringBeforeLast('/', "")

        title = element(opf, "dc:title")?.let(::clean)?.ifBlank { null } ?: file.nameWithoutExtension
        author = element(opf, "dc:creator")?.let(::clean) ?: ""
        subjects = Regex("<dc:subject[^>]*>(.*?)</dc:subject>", RegexOption.DOT_MATCHES_ALL).findAll(opf)
            .map { clean(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()

        val items = Regex("<item\\b[^>]*>").findAll(opf).mapNotNull { m ->
            val tag = m.value
            val id = attr(tag, "id") ?: return@mapNotNull null
            val href = attr(tag, "href") ?: return@mapNotNull null
            id to Item(resolve(base, href), attr(tag, "media-type") ?: "", attr(tag, "properties") ?: "")
        }.toMap()

        chapters = Regex("<itemref\\b[^>]*>").findAll(opf)
            .filter { attr(it.value, "linear") != "no" }
            .mapNotNull { attr(it.value, "idref")?.let(items::get) }
            .filter { it.mediaType.contains("html") }
            .map { it.path }
            .filter { zip.getEntry(it) != null }
            .toList()
        if (chapters.isEmpty()) error("EPUB has no readable chapters")

        val coverId = Regex("<meta\\b[^>]*name=\"cover\"[^>]*>").find(opf)?.let { attr(it.value, "content") }
        coverPath = items.values.firstOrNull { it.properties.contains("cover-image") }?.path
            ?: coverId?.let { items[it]?.path }

        chapterChars = chapters.map { path -> visibleChars(text(path) ?: "") }
    }

    private data class Item(val path: String, val mediaType: String, val properties: String)

    val totalChars: Int get() = chapterChars.sum().coerceAtLeast(1)

    /** Rough printed-page estimate (≈1,800 characters per page). */
    val estimatedPages: Int get() = (totalChars / CHARS_PER_PAGE).coerceAtLeast(1)

    /** Fraction of the whole book read at [chapter] and [fraction] of that chapter. */
    fun progressAt(chapter: Int, fraction: Double): Double {
        val c = chapter.coerceIn(0, chapters.size - 1)
        val before = chapterChars.take(c).sum()
        return ((before + fraction.coerceIn(0.0, 1.0) * chapterChars[c]) / totalChars).coerceIn(0.0, 1.0)
    }

    fun read(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun text(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    override fun close() = zip.close()

    companion object {
        const val CHARS_PER_PAGE = 1800

        private fun attr(tag: String, name: String): String? =
            Regex("(?:^|\\s)$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag)?.let { m ->
                m.groupValues[2].ifEmpty { m.groupValues[3] }
            }

        private fun element(xml: String, name: String): String? =
            Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        private fun clean(s: String) = decodeEntities(s.replace(Regex("<[^>]+>"), "")).trim()

        private fun decodeEntities(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")

        fun visibleChars(html: String): Int {
            val body = html.substringAfter("<body", html)
            return body.replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .length
        }

        /** Resolves [href] (URL-encoded, relative) against the directory [base] inside the zip. */
        fun resolve(base: String, href: String): String {
            val clean = URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8")
            val parts = ArrayDeque<String>()
            (if (base.isEmpty()) clean else "$base/$clean").split('/').forEach { p ->
                when (p) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(p)
                }
            }
            return parts.joinToString("/")
        }
    }
}
