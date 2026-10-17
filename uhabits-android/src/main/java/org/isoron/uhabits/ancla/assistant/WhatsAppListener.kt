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

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

/**
 * Receives the phone's notifications once the user grants notification access, and keeps only
 * WhatsApp's messages, encrypted, in the [WhatsAppVault]. Every other app's notification is ignored
 * without reading its content, and nothing is ever logged but counts.
 */
class WhatsAppListener : NotificationListenerService() {
    private val worker = Executors.newSingleThreadExecutor()
    private val vault by lazy { WhatsAppVault(applicationContext) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in ChatNotifications.PACKAGES) return
        if (!vault.enabled) return
        val raw = read(sbn) ?: return
        worker.execute {
            try {
                val messages = ChatNotifications.parse(raw)
                vault.append(messages)
            } catch (e: Exception) {
                Log.w(TAG, "could not save: ${e.javaClass.simpleName}")
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun read(sbn: StatusBarNotification): RawChatNotification? {
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null
        val messages = (bundles(extras, Notification.EXTRA_HISTORIC_MESSAGES) + bundles(extras, Notification.EXTRA_MESSAGES)).mapNotNull { b ->
            val text = b.getCharSequence(KEY_TEXT)?.toString() ?: return@mapNotNull null
            val person = if (android.os.Build.VERSION.SDK_INT >= 28) b.getParcelable<android.app.Person>(KEY_SENDER_PERSON)?.name else null
            val sender = (person ?: b.getCharSequence(KEY_SENDER))?.toString()
            Triple(sender, text, b.getLong(KEY_TIME))
        }
        return RawChatNotification(
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
            isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION),
            messages = messages,
            postTime = sbn.postTime,
            isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            category = n.category
        )
    }

    @Suppress("DEPRECATION")
    private fun bundles(extras: Bundle, key: String): List<Bundle> =
        (extras.getParcelableArray(key) as Array<Parcelable>?)?.filterIsInstance<Bundle>().orEmpty()

    private companion object {
        const val TAG = "AnclaWhatsApp"

        // Keys of each message bundle inside Notification.EXTRA_MESSAGES (Notification.MessagingStyle.Message).
        const val KEY_TEXT = "text"
        const val KEY_TIME = "time"
        const val KEY_SENDER = "sender"
        const val KEY_SENDER_PERSON = "sender_person"
    }
}
