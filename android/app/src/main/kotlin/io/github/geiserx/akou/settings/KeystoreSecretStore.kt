// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.geiserx.akou.core.client.SecretStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The servers' `ak_` keys, the port of KeyStore.swift. Each key is encrypted with one AES-GCM key
 * that lives in the AndroidKeyStore and never leaves it, and the ciphertext is one file per server
 * id in [directory]. The AES key needs no user authentication, so a background upload can read it
 * after the first unlock.
 *
 * [directory] is excluded from cloud backup and device transfer (`res/xml/data_extraction_rules.xml`):
 * the keys never back up, as `ThisDeviceOnly` on iOS. A Keystore key can still be lost (some OEMs,
 * some restores); a file that no longer decrypts reads as no key, never as a crash.
 */
class KeystoreSecretStore(private val directory: File, private val alias: String = ALIAS) : SecretStore {
    override fun load(account: String): String? {
        val f = file(account)
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "a stored key could not be read: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Saves [secret] trimmed, replacing the old one; an empty key deletes it. */
    override fun save(secret: String, account: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return delete(account)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            val sealed = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            directory.mkdirs()
            val f = file(account)
            val tmp = File(directory, "${f.name}.tmp")
            tmp.writeBytes(sealed)
            tmp.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "a key could not be saved: ${e.javaClass.simpleName}")
            false
        }
    }

    override fun delete(account: String): Boolean {
        val f = file(account)
        return !f.exists() || f.delete()
    }

    private fun file(account: String) = File(directory, "${account.filter { it.isLetterOrDigit() || it == '-' }}.key")

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        /** Under `filesDir`; `data_extraction_rules.xml` names the same path. */
        const val DIRECTORY = "keys"
        private const val ALIAS = "akou-server-keys"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG = "KeystoreSecretStore"
    }
}
