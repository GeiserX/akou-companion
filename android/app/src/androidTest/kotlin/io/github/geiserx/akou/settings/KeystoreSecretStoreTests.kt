// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The keys in the AndroidKeyStore (KeyStore.swift's rules: trimmed, an empty save deletes). */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTests {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "keys-test").apply { deleteRecursively() }
    private val store = KeystoreSecretStore(dir, alias = "akou-test-keys")

    @Test
    fun savesTrimmedNeverInTheClearAndDeletesOnEmpty() {
        assertNull(store.load("a"))
        assertTrue(store.save("  ak_secret_value\n", "a"))
        assertEquals("ak_secret_value", store.load("a"))
        val onDisk = File(dir, "a.key").readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("ak_secret_value"), "the file holds ciphertext")
        assertTrue(store.save("", "a"))
        assertNull(store.load("a"))
        assertFalse(File(dir, "a.key").exists())
    }

    @Test
    fun aFileThatNoLongerDecryptsReadsAsNoKey() {
        assertTrue(store.save("ak_other", "b"))
        val f = File(dir, "b.key")
        val bytes = f.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        assertNull(store.load("b"))
        dir.deleteRecursively()
    }
}
