// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.geiserx.akou.core.client.SecretStore
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.recorder.RecordTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * The server book as JSON in Preferences DataStore, and the keys in the [SecretStore]. A1 edits one
 * server, the book's first; the book already holds several for A5.
 */
class Settings(context: Context, val keys: SecretStore = KeystoreSecretStore(File(context.filesDir, KeystoreSecretStore.DIRECTORY))) {
    private val store = context.applicationContext.settingsStore

    val book: Flow<ServerBook> = store.data.map { prefs -> prefs[BOOK]?.let(ServerBook::decode) ?: ServerBook() }

    suspend fun book(): ServerBook = book.first()

    suspend fun save(book: ServerBook) {
        store.edit { it[BOOK] = book.encoded() }
    }

    fun key(server: UUID): String? = keys.load(server.toString())

    /** "Record to" with its URL and key, or null when either is missing. */
    suspend fun recordTarget(): RecordTarget? {
        val entry = book().recordToServer ?: return null
        val url = entry.baseUrl ?: return null
        val key = key(entry.id)?.takeIf { it.isNotEmpty() } ?: return null
        return RecordTarget(entry.id, url, key)
    }

    private companion object {
        val BOOK = stringPreferencesKey("server_book")
    }
}
