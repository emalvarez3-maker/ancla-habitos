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

package org.isoron.uhabits.ancla.mail

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One email, as much of it as was fetched ([body] only when read in full). */
data class Mail(
    val id: String,
    val threadId: String,
    val from: String,
    val to: String,
    val subject: String,
    val date: Long,
    val snippet: String,
    val labels: Set<String>,
    val unsubscribe: String? = null,
    val messageId: String? = null,
    val references: String? = null,
    val body: String = ""
) {
    val unread get() = "UNREAD" in labels

    /** "Ana Pérez <ana@x.com>" → "ana@x.com". */
    val address get() = Gmail.address(from)

    /** "Ana Pérez <ana@x.com>" → "Ana Pérez". */
    val sender get() = Gmail.displayName(from)

    val category get() = labels.firstOrNull { it.startsWith("CATEGORY_") && it != "CATEGORY_PERSONAL" }
}

class MailException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * Gmail's REST API with an OAuth token for the gmail.modify scope: read, label, send, drafts and the
 * trash. That scope cannot delete for good, so nothing done here is beyond the 30-day trash.
 */
object Gmail {
    const val SCOPE = "https://www.googleapis.com/auth/gmail.modify"
    private const val API = "https://gmail.googleapis.com/gmail/v1/users/me"
    private val META = listOf("From", "To", "Subject", "Date", "List-Unsubscribe", "Message-ID", "References")

    fun profile(token: String): String = call("GET", "$API/profile", token).getString("emailAddress")

    /** The ids matching a Gmail search ([query] as in Gmail's own search box), newest first. */
    fun ids(token: String, query: String, max: Int = 25): List<String> {
        val out = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$API/messages?maxResults=${(max - out.size).coerceIn(1, 500)}&q=${enc(query)}" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = call("GET", url, token)
            json.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) out += a.getJSONObject(i).getString("id") }
            page = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null && out.size < max)
        return out.take(max)
    }

    /** The messages matching [query], with headers and snippet (fetched a few at a time). */
    fun list(token: String, query: String, max: Int = 25): List<Mail> {
        val ids = ids(token, query, max)
        if (ids.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(8, ids.size))
        try {
            val jobs = ids.map { id -> pool.submit<Mail> { get(token, id, full = false) } }
            return jobs.map { it.get(30, TimeUnit.SECONDS) }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause as? MailException ?: MailException(e.cause?.message ?: "Gmail")
        } finally {
            pool.shutdownNow()
        }
    }

    fun get(token: String, id: String, full: Boolean): Mail {
        val url = "$API/messages/${enc(id)}?format=" + if (full) "full" else "metadata" + META.joinToString("") { "&metadataHeaders=${enc(it)}" }
        return parse(call("GET", url, token))
    }

    fun send(token: String, raw: String, threadId: String? = null): String =
        call("POST", "$API/messages/send", token, JSONObject().put("raw", raw).apply { threadId?.let { put("threadId", it) } }).getString("id")

    fun draft(token: String, raw: String, threadId: String? = null): String =
        call("POST", "$API/drafts", token, JSONObject().put("message", JSONObject().put("raw", raw).apply { threadId?.let { put("threadId", it) } })).getString("id")

    /** Adds and removes labels on many messages at once ("UNREAD", "INBOX"…). */
    fun modify(token: String, ids: List<String>, add: List<String> = emptyList(), remove: List<String> = emptyList()) {
        ids.chunked(1000).forEach { chunk ->
            call("POST", "$API/messages/batchModify", token, JSONObject().put("ids", JSONArray(chunk)).put("addLabelIds", JSONArray(add)).put("removeLabelIds", JSONArray(remove)))
        }
    }

    /** To the trash, where Gmail keeps them 30 days: never a permanent delete. */
    fun trash(token: String, ids: List<String>) = ids.forEach { call("POST", "$API/messages/${enc(it)}/trash", token, JSONObject()) }

    fun untrash(token: String, ids: List<String>) = ids.forEach { call("POST", "$API/messages/${enc(it)}/untrash", token, JSONObject()) }

    // --- Parsing and building (no network: tested on their own) ---

    fun parse(json: JSONObject): Mail {
        val payload = json.optJSONObject("payload") ?: JSONObject()
        val headers = mutableMapOf<String, String>()
        payload.optJSONArray("headers")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { h -> headers.putIfAbsent(h.getString("name").lowercase(), h.optString("value")) } }
        val labels = json.optJSONArray("labelIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return Mail(
            id = json.getString("id"),
            threadId = json.optString("threadId"),
            from = headers["from"].orEmpty(),
            to = headers["to"].orEmpty(),
            subject = headers["subject"].orEmpty(),
            date = json.optString("internalDate").toLongOrNull() ?: 0L,
            snippet = unescape(json.optString("snippet")),
            labels = labels,
            unsubscribe = headers["list-unsubscribe"]?.takeIf { it.isNotBlank() },
            messageId = headers["message-id"],
            references = headers["references"],
            body = bodyOf(payload)
        )
    }

    /** The readable text of a message: its plain part, or its HTML part as text. */
    fun bodyOf(payload: JSONObject): String {
        fun find(p: JSONObject, mime: String): String? {
            if (p.optString("mimeType").equals(mime, true)) {
                p.optJSONObject("body")?.optString("data")?.takeIf { it.isNotEmpty() }?.let { return decode(it) }
            }
            val parts = p.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) find(parts.getJSONObject(i), mime)?.let { return it }
            return null
        }
        return (find(payload, "text/plain") ?: find(payload, "text/html")?.let(::htmlToText) ?: "").trim()
    }

    fun htmlToText(html: String): String = unescape(
        html.replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</tr>|</li>|</h[1-6]>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
    ).replace(Regex("[ \\t\\x{00A0}]+"), " ").replace(Regex(" *\n[ \n]*"), "\n\n").trim()

    private fun unescape(s: String) = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")

    /** An RFC 2822 message, base64url as Gmail wants it. Replies keep the thread's headers. */
    fun raw(from: String, to: String, subject: String, body: String, inReplyTo: String? = null, references: String? = null, cc: String? = null): String {
        // A line break in any header would let text add headers of its own (a hidden Bcc).
        fun line(s: String) = s.replace(Regex("[\r\n]+"), " ").trim()
        val text = buildString {
            if (from.isNotBlank()) append("From: ").append(line(from)).append("\r\n")
            append("To: ").append(line(to)).append("\r\n")
            if (!cc.isNullOrBlank()) append("Cc: ").append(line(cc)).append("\r\n")
            append("Subject: ").append(header(subject)).append("\r\n")
            if (!inReplyTo.isNullOrBlank()) {
                append("In-Reply-To: ").append(line(inReplyTo)).append("\r\n")
                append("References: ").append(line(listOfNotNull(references, inReplyTo).joinToString(" "))).append("\r\n")
            }
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=UTF-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.toByteArray(Charsets.UTF_8)))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
    }

    /** A header value, encoded only when it isn't plain ASCII. */
    fun header(value: String): String {
        val clean = value.replace(Regex("[\r\n]+"), " ").trim()
        return if (clean.all { it.code in 32..126 }) clean else "=?UTF-8?B?" + Base64.getEncoder().encodeToString(clean.toByteArray(Charsets.UTF_8)) + "?="
    }

    fun decode(data: String): String = String(Base64.getUrlDecoder().decode(data.trim().replace('+', '-').replace('/', '_').trimEnd('=')), Charsets.UTF_8)

    fun address(from: String): String = Regex("<([^>]+)>").find(from)?.groupValues?.get(1)?.trim()?.lowercase() ?: from.trim().lowercase()

    fun displayName(from: String): String {
        val name = from.substringBefore('<').trim().trim('"').trim()
        return name.ifEmpty { address(from) }
    }

    /** Whether [s] looks like one or more email addresses, comma-separated. */
    fun validRecipients(s: String): Boolean =
        s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.let { list -> list.isNotEmpty() && list.all { Regex("^[^@\\s<>]+@[^@\\s<>]+\\.[^@\\s<>]+$").matches(address(it)) } }

    /** "Re: " once, however many replies came before. */
    fun replySubject(subject: String) = if (subject.trimStart().startsWith("re:", ignoreCase = true)) subject.trim() else "Re: ${subject.trim()}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun call(method: String, url: String, token: String, body: JSONObject? = null): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
        }
        try {
            body?.let { b -> conn.outputStream.use { it.write(b.toString().toByteArray()) } }
            val code = conn.responseCode
            if (code == 401) throw MailException("Google pidió volver a conectar la cuenta", unauthorized = true)
            if (code == 403) throw MailException("Google no dio permiso para esta cuenta (403)", unauthorized = true)
            if (code !in 200..299) throw MailException("Gmail respondió $code")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: IOException) {
            throw MailException("Sin conexión con Gmail")
        } finally {
            conn.disconnect()
        }
    }
}
