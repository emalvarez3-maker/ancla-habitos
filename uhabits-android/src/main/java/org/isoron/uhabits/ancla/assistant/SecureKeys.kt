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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class TranscriptionProvider { ANDROID, GROQ, OPENAI, GEMINI }

enum class AssistantProvider { GEMINI, CLAUDE }

/**
 * Stores the user's own API keys encrypted with an AES-GCM key that never leaves the
 * Android Keystore. Keys are only sent to the provider they belong to.
 */
class SecureKeys(context: Context) {
    private val prefs = context.getSharedPreferences("ancla_assistant", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = read("claude")
        set(v) = write("claude", v)

    var geminiKey: String
        get() = read("gemini")
        set(v) = write("gemini", v)

    var assistant: AssistantProvider
        get() = AssistantProvider.entries.getOrElse(prefs.getInt("assistant", 0)) { AssistantProvider.GEMINI }
        set(v) = prefs.edit().putInt("assistant", v.ordinal).apply()

    /** The chosen assistant if it has a key, else whichever provider has one, else null. */
    val usableAssistant: Pair<AssistantProvider, String>?
        get() {
            val keys = mapOf(AssistantProvider.GEMINI to geminiKey, AssistantProvider.CLAUDE to claudeKey).filterValues { it.isNotBlank() }
            val pick = if (assistant in keys) assistant else keys.keys.firstOrNull() ?: return null
            return pick to keys.getValue(pick)
        }

    var groqKey: String
        get() = read("groq")
        set(v) = write("groq", v)

    var openAiKey: String
        get() = read("openai")
        set(v) = write("openai", v)

    var transcription: TranscriptionProvider
        get() = TranscriptionProvider.entries.getOrElse(prefs.getInt("transcription", TranscriptionProvider.GEMINI.ordinal)) { TranscriptionProvider.GEMINI }
        set(v) = prefs.edit().putInt("transcription", v.ordinal).apply()

    /** The provider actually usable now: falls back to Android when the chosen one has no key. */
    val effectiveTranscription: TranscriptionProvider
        get() = when (transcription) {
            TranscriptionProvider.GROQ -> if (groqKey.isNotBlank()) TranscriptionProvider.GROQ else TranscriptionProvider.ANDROID
            TranscriptionProvider.OPENAI -> if (openAiKey.isNotBlank()) TranscriptionProvider.OPENAI else TranscriptionProvider.ANDROID
            TranscriptionProvider.ANDROID -> TranscriptionProvider.ANDROID
            TranscriptionProvider.GEMINI ->
                if (usableAssistant?.first == AssistantProvider.GEMINI) TranscriptionProvider.GEMINI else TranscriptionProvider.ANDROID
        }

    /** Read replies aloud. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speak", true)
        set(v) = prefs.edit().putBoolean("speak", v).apply()

    /** Brote's voice: a Gemini voice name, or [BroteVoices.PHONE] for Android's own. */
    var voice: String
        get() = prefs.getString("voice", null) ?: BroteVoices.GEMINI.first()
        set(v) = prefs.edit().putString("voice", v).apply()

    /** The phone voice used when Gemini is unavailable, or empty for the automatic pick. */
    var phoneVoice: String
        get() = prefs.getString("phone_voice", null).orEmpty()
        set(v) = prefs.edit().putString("phone_voice", v).apply()

    /** The Gemini key and voice to speak with, or null to use the phone's voice. */
    val cloudVoice: Pair<String, String>?
        get() = voice.takeIf { it != BroteVoices.PHONE }?.let { v -> geminiKey.takeIf { it.isNotBlank() }?.let { it to v } }

    /** "Oye Dexter": listen for the assistant's name while the app is open. */
    var wakeWord: Boolean
        get() = prefs.getBoolean("wake_word", true)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    /** "Oye Dexter" even with the app closed (the DexterService foreground service). */
    var alwaysListening: Boolean
        get() = prefs.getBoolean("always_listening", false)
        set(v) = prefs.edit().putBoolean("always_listening", v).apply()

    /** Jarvis mode: after Dexter speaks, listen again automatically. */
    var conversationMode: Boolean
        get() = prefs.getBoolean("conversation", false)
        set(v) = prefs.edit().putBoolean("conversation", v).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun write(name: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.iv + cipher.doFinal(value.trim().toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    private fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
            String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE))
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        private const val ALIAS = "ancla_api_keys"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
