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

enum class TranscriptionProvider { ANDROID, GROQ, OPENAI }

/**
 * Stores the user's own API keys encrypted with an AES-GCM key that never leaves the
 * Android Keystore. Keys are only sent to the provider they belong to.
 */
class SecureKeys(context: Context) {
    private val prefs = context.getSharedPreferences("ancla_assistant", Context.MODE_PRIVATE)

    var claudeKey: String
        get() = read("claude")
        set(v) = write("claude", v)

    var groqKey: String
        get() = read("groq")
        set(v) = write("groq", v)

    var openAiKey: String
        get() = read("openai")
        set(v) = write("openai", v)

    var transcription: TranscriptionProvider
        get() = TranscriptionProvider.entries.getOrElse(prefs.getInt("transcription", 0)) { TranscriptionProvider.ANDROID }
        set(v) = prefs.edit().putInt("transcription", v.ordinal).apply()

    /** The provider actually usable now: falls back to Android when the chosen one has no key. */
    val effectiveTranscription: TranscriptionProvider
        get() = when (transcription) {
            TranscriptionProvider.GROQ -> if (groqKey.isNotBlank()) TranscriptionProvider.GROQ else TranscriptionProvider.ANDROID
            TranscriptionProvider.OPENAI -> if (openAiKey.isNotBlank()) TranscriptionProvider.OPENAI else TranscriptionProvider.ANDROID
            TranscriptionProvider.ANDROID -> TranscriptionProvider.ANDROID
        }

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
