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

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.isoron.uhabits.ancla.EncryptedStore
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Google needs the user on screen once (to pick the account or allow Gmail). */
class NeedsConsent(val intent: PendingIntent) : Exception("Hay que conectar la cuenta en el app")

/**
 * The user's Gmail accounts (a personal one, the university's Google Workspace one…). Only the
 * addresses are kept, encrypted; the access comes from Google sign-in as a short-lived token, so the
 * app never sees a password and the user can revoke it from their Google account at any time.
 */
class Mailbox(private val context: Context, private val file: File = File(context.filesDir, "mail_accounts.json")) {

    fun accounts(): List<String> = synchronized(LOCK) {
        val text = EncryptedStore.readText(file) ?: return emptyList()
        runCatching { JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    }

    fun add(email: String) = synchronized(LOCK) {
        val e = email.trim().lowercase()
        if (e.isNotEmpty() && e !in accounts()) EncryptedStore.writeText(file, JSONArray(accounts() + e).toString())
    }

    fun remove(email: String) = synchronized(LOCK) {
        EncryptedStore.writeText(file, JSONArray(accounts().filter { it != email }).toString())
    }

    /** The account a hint names ("espe", "gmail", the whole address), or the first one. */
    fun pick(hint: String?): String? = pick(accounts(), hint)

    /** Blocking. A Gmail token for [email]; throws [NeedsConsent] when Google must ask first. */
    fun token(email: String?): String = tokenFrom(Tasks.await(Identity.getAuthorizationClient(context).authorize(request(email)), 30, TimeUnit.SECONDS))

    /** Runs [block] with [email]'s token. */
    fun <T> with(email: String, block: (String) -> T): T = block(token(email))

    companion object {
        private val LOCK = Any()

        fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(Gmail.SCOPE)))
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()

        fun tokenFrom(r: AuthorizationResult): String {
            if (r.hasResolution()) throw NeedsConsent(r.pendingIntent ?: throw MailException("Google no pudo pedir permiso"))
            return r.accessToken ?: throw MailException("Google no dio acceso al correo")
        }

        fun pick(accounts: List<String>, hint: String?): String? {
            val h = hint?.trim()?.lowercase().orEmpty()
            if (h.isEmpty() || h == "null") return accounts.firstOrNull()
            return accounts.firstOrNull { it == h } ?: accounts.firstOrNull { h in it } ?: accounts.firstOrNull { it.substringAfter('@').substringBefore('.') in h }
        }
    }
}

/** Mail from one sender that looks like it can go: newsletters, promotions, social notifications. */
data class CleanupGroup(val sender: String, val address: String, val ids: List<String>, val unread: Int, val category: String?, val unsubscribe: String?) {
    val size get() = ids.size
}

object MailCleanup {
    /** What Gmail's search calls bulk mail still in the inbox. */
    const val QUERY = "in:inbox (category:promotions OR category:social OR category:updates OR category:forums OR unsubscribe)"

    private val BULK = setOf("CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_UPDATES", "CATEGORY_FORUMS")

    /**
     * Groups [mails] by sender, keeping only bulk mail (a Gmail bulk category or an unsubscribe
     * header) and never anything the user starred, biggest group first.
     */
    fun groups(mails: List<Mail>): List<CleanupGroup> =
        mails.filter { m -> (m.labels.any { it in BULK } || m.unsubscribe != null) && "STARRED" !in m.labels && "CATEGORY_PERSONAL" !in m.labels }
            .groupBy { it.address }
            .map { (address, list) ->
                CleanupGroup(
                    sender = list.first().sender,
                    address = address,
                    ids = list.map { it.id },
                    unread = list.count { it.unread },
                    category = list.mapNotNull { it.category }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
                    unsubscribe = list.firstNotNullOfOrNull { it.unsubscribe }
                )
            }
            .sortedWith(compareByDescending<CleanupGroup> { it.size }.thenBy { it.sender.lowercase() })

    /** The web link of a List-Unsubscribe header ("<mailto:…>, <https://…>"), if it has one. */
    fun unsubscribeLink(header: String?): String? =
        header?.let { h -> Regex("<(https://[^>]+)>").find(h)?.groupValues?.get(1) }

    fun categoryName(label: String?) = when (label) {
        "CATEGORY_PROMOTIONS" -> "Promociones"
        "CATEGORY_SOCIAL" -> "Social"
        "CATEGORY_UPDATES" -> "Notificaciones"
        "CATEGORY_FORUMS" -> "Foros"
        else -> "Boletines"
    }
}
