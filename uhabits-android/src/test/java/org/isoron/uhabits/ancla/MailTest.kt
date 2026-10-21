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

package org.isoron.uhabits.ancla

import org.isoron.uhabits.ancla.mail.Gmail
import org.isoron.uhabits.ancla.mail.Mail
import org.isoron.uhabits.ancla.mail.MailCleanup
import org.isoron.uhabits.ancla.mail.Mailbox
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Gmail's messages read right, mails written right, and cleanup that only ever touches bulk mail. */
class MailTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun message(id: String, from: String, labels: List<String>, unsubscribe: String? = null, parts: JSONObject? = null) = JSONObject()
        .put("id", id).put("threadId", "t$id").put("internalDate", "1760000000000").put("snippet", "Hola &amp; adiós")
        .put("labelIds", JSONArray(labels))
        .put(
            "payload",
            (parts ?: JSONObject()).put(
                "headers",
                JSONArray()
                    .put(JSONObject().put("name", "From").put("value", from))
                    .put(JSONObject().put("name", "Subject").put("value", "Informe"))
                    .put(JSONObject().put("name", "Message-ID").put("value", "<abc@mail>"))
                    .apply { unsubscribe?.let { put(JSONObject().put("name", "List-Unsubscribe").put("value", it)) } }
            )
        )

    @Test
    fun aMessageIsReadWithItsHeadersAndPlainText() {
        val parts = JSONObject().put("mimeType", "multipart/alternative").put(
            "parts",
            JSONArray()
                .put(JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", b64("<p>HTML</p>"))))
                .put(JSONObject().put("mimeType", "text/plain").put("body", JSONObject().put("data", b64("Hola Esteban, ¿cómo vas?"))))
        )
        val m = Gmail.parse(message("1", "\"Ana Pérez\" <Ana@Uni.edu>", listOf("INBOX", "UNREAD", "CATEGORY_PERSONAL"), parts = parts))
        assertEquals("ana@uni.edu", m.address)
        assertEquals("Ana Pérez", m.sender)
        assertEquals("Informe", m.subject)
        assertEquals("<abc@mail>", m.messageId)
        assertEquals("Hola & adiós", m.snippet)
        assertEquals("the plain part wins over the HTML", "Hola Esteban, ¿cómo vas?", m.body)
        assertTrue(m.unread)
        assertNull("personal isn't a bulk category", m.category)
    }

    @Test
    fun htmlOnlyMailBecomesText() {
        val html = "<html><head><style>p{color:red}</style></head><body><p>Hola&nbsp;mundo</p><script>alert(1)</script><div>Chao</div></body></html>"
        val parts = JSONObject().put("mimeType", "text/html").put("body", JSONObject().put("data", b64(html)))
        val body = Gmail.parse(message("2", "x@y.com", listOf("INBOX"), parts = parts)).body
        assertTrue(body, body.contains("Hola mundo"))
        assertTrue(body.contains("Chao"))
        assertFalse("no script or style text", body.contains("alert") || body.contains("color"))
    }

    @Test
    fun aWrittenMailIsValidMimeWithAccentsIntact() {
        val raw = Gmail.raw("yo@gmail.com", "profe@espe.edu.ec", "Entrega del informe — viernes", "Estimado profesor:\nEntregaré el informe el viernes.\nSaludos", inReplyTo = "<abc@mail>", references = "<root@mail>")
        val text = String(Base64.getUrlDecoder().decode(raw))
        assertTrue(text.contains("To: profe@espe.edu.ec\r\n"))
        assertTrue("non-ASCII subjects are encoded", text.contains("Subject: =?UTF-8?B?"))
        assertTrue(text.contains("In-Reply-To: <abc@mail>\r\n"))
        assertTrue(text.contains("References: <root@mail> <abc@mail>\r\n"))
        val body = text.substringAfter("\r\n\r\n")
        assertEquals("Estimado profesor:\nEntregaré el informe el viernes.\nSaludos", String(Base64.getMimeDecoder().decode(body)))
    }

    @Test
    fun headersCantBeInjected() {
        assertEquals("Hola Bcc: x@evil.com", Gmail.header("Hola\r\nBcc: x@evil.com"))
        assertEquals("Plain", Gmail.header("Plain"))
        val text = String(Base64.getUrlDecoder().decode(Gmail.raw("yo@gmail.com", "a@b.co\r\nBcc: x@evil.com", "Hola", "Texto")))
        assertFalse("no hidden Bcc header", text.contains("\r\nBcc:"))
    }

    @Test
    fun recipientsMustBeAddresses() {
        assertTrue(Gmail.validRecipients("ana@uni.edu"))
        assertTrue(Gmail.validRecipients("Ana <ana@uni.edu>, b@c.co"))
        assertFalse(Gmail.validRecipients("ana"))
        assertFalse(Gmail.validRecipients(""))
        assertFalse(Gmail.validRecipients("ana@uni"))
        assertEquals("Re: Informe", Gmail.replySubject("Informe"))
        assertEquals("RE: Informe", Gmail.replySubject("RE: Informe"))
    }

    @Test
    fun cleanupGroupsOnlyBulkMailAndNeverStarred() {
        val mails = listOf(
            Gmail.parse(message("a", "Tienda <promo@shop.com>", listOf("INBOX", "CATEGORY_PROMOTIONS", "UNREAD"), "<mailto:u@shop.com>, <https://shop.com/unsub>")),
            Gmail.parse(message("b", "Tienda <promo@shop.com>", listOf("INBOX", "CATEGORY_PROMOTIONS"))),
            Gmail.parse(message("c", "Tienda <promo@shop.com>", listOf("INBOX", "CATEGORY_PROMOTIONS", "STARRED"))),
            Gmail.parse(message("d", "Red <no-reply@social.com>", listOf("INBOX", "CATEGORY_SOCIAL"))),
            Gmail.parse(message("e", "Ana <ana@uni.edu>", listOf("INBOX", "CATEGORY_PERSONAL"))),
            Gmail.parse(message("f", "Boletín <news@blog.com>", listOf("INBOX"), "<https://blog.com/stop>"))
        )
        val groups = MailCleanup.groups(mails)
        assertEquals(listOf("promo@shop.com", "news@blog.com", "no-reply@social.com"), groups.map { it.address })
        assertEquals(listOf("a", "b"), groups.first().ids)
        assertEquals(1, groups.first().unread)
        assertEquals("CATEGORY_PROMOTIONS", groups.first().category)
        assertFalse("a person's mail is never offered", groups.any { it.address == "ana@uni.edu" })
        assertEquals("https://shop.com/unsub", MailCleanup.unsubscribeLink(groups.first().unsubscribe))
        assertNull(MailCleanup.unsubscribeLink("<mailto:u@shop.com>"))
    }

    @Test
    fun theAccountComesFromAHint() {
        val accounts = listOf("esteban@gmail.com", "emalvarez3@espe.edu.ec")
        assertEquals("esteban@gmail.com", Mailbox.pick(accounts, null))
        assertEquals("emalvarez3@espe.edu.ec", Mailbox.pick(accounts, "espe"))
        assertEquals("emalvarez3@espe.edu.ec", Mailbox.pick(accounts, "el correo de la ESPE"))
        assertEquals("esteban@gmail.com", Mailbox.pick(accounts, "gmail"))
        assertNull("an account that isn't connected", Mailbox.pick(accounts, "outlook"))
        assertNull(Mailbox.pick(emptyList(), null))
    }

    @Test
    fun mailsKeepTheirFields() {
        val m = Mail("1", "t", "a@b.co", "", "", 0, "", setOf("CATEGORY_UPDATES"))
        assertEquals("CATEGORY_UPDATES", m.category)
        assertEquals("Notificaciones", MailCleanup.categoryName(m.category))
    }
}
