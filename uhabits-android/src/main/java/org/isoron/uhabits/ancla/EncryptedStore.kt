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

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the user's personal data (notes, journal, study log, Dexter's memory) with AES-256-GCM.
 *
 * The key is generated inside the Android Keystore and can't be exported: the app asks the
 * Keystore to encrypt and decrypt, but the key itself never reaches app memory, backups or
 * another device. Every write uses a fresh random IV, and each blob is bound to its purpose
 * ([label], e.g. the file name) as associated data, so one file can't be swapped for another.
 *
 * Format: [MAGIC] + 12-byte IV + ciphertext with its 16-byte tag. Data without the magic header
 * is plain text from before encryption existed, and the stores migrate it on first read.
 */
object EncryptedStore {
    private const val ALIAS = "ancla_personal_data"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 1)

    @Volatile private var cachedKey: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            gen.generateKey()
        }
        cachedKey = key
        return key
    }

    fun isEncrypted(data: ByteArray) = data.size >= MAGIC.size + IV_SIZE && MAGIC.indices.all { data[it] == MAGIC[it] }

    fun encrypt(plain: ByteArray, label: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks a random IV
        cipher.updateAAD(label.toByteArray())
        return MAGIC + cipher.iv + cipher.doFinal(plain)
    }

    /** Throws when [data] was tampered with, belongs to another [label] or the key is gone. */
    fun decrypt(data: ByteArray, label: String): ByteArray {
        require(isEncrypted(data)) { "Not encrypted" }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, MAGIC.size, IV_SIZE))
        cipher.updateAAD(label.toByteArray())
        val start = MAGIC.size + IV_SIZE
        return cipher.doFinal(data, start, data.size - start)
    }

    /** For short values kept in SharedPreferences. */
    fun encryptString(text: String, label: String): String = Base64.encodeToString(encrypt(text.toByteArray(), label), Base64.NO_WRAP)

    fun decryptString(stored: String, label: String): String = String(decrypt(Base64.decode(stored, Base64.NO_WRAP), label))

    /**
     * The decrypted text of [file], or null when it doesn't exist. A plain-text file from an older
     * version is encrypted in place the first time it's read. A file that still can't be decrypted
     * after a few tries is moved aside ([quarantine]) rather than left to be overwritten.
     */
    fun readText(file: File): String? {
        if (!file.exists()) return null
        val data = file.readBytes()
        if (!isEncrypted(data)) {
            val text = String(data)
            runCatching { writeText(file, text) } // if this fails it stays plain and is retried next time
            return text
        }
        repeat(3) { attempt ->
            try {
                return String(decrypt(data, file.name))
            } catch (e: Exception) { // GeneralSecurityException, or the Keystore's ProviderException
                Thread.sleep(150L * (attempt + 1))
            }
        }
        quarantine(file)
        return null
    }

    /** Encrypts [text] into [file] atomically: a temporary file, synced, then renamed over it. */
    fun writeText(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(encrypt(text.toByteArray(), file.name))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    /** Moves an undecryptable [file] aside instead of letting a later write destroy it. */
    private fun quarantine(file: File) {
        if (file.exists()) file.renameTo(File(file.parentFile, file.name + ".unreadable-" + System.currentTimeMillis()))
    }
}
