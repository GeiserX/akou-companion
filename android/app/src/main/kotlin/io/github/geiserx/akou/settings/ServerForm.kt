// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.settings

import io.github.geiserx.akou.core.client.Endpoint
import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.core.client.ServerEntry
import io.github.geiserx.akou.core.client.ServerProbe
import io.github.geiserx.akou.core.protocol.KeyInfo
import io.github.geiserx.akou.core.protocol.ServerInfo
import java.io.IOException
import java.util.UUID

/**
 * The rules of the one-server settings screen, the port of ServerEditorView.swift's save and Test,
 * kept free of Android so they run on the JVM.
 */
object ServerForm {
    /** Why [url] cannot be a server address, or null when it can. Endpoint is the only gate. */
    fun urlProblem(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "Enter the server's address."
        return try {
            Endpoint.api(trimmed, "/v1/server")
            null
        } catch (e: Endpoint.Failure) {
            refusal(e)
        }
    }

    /** What the screen says for a URL Endpoint refuses. */
    fun refusal(e: Endpoint.Failure): String = when (e) {
        is Endpoint.Failure.UnsupportedScheme -> "The address must start with https:// (or http:// to a private address)."
        Endpoint.Failure.MissingHost -> "That address has no host."
        is Endpoint.Failure.CleartextToPublicHost ->
            "Plain http is refused for ${e.host}. Use https, or plain http only to a private address."
    }

    /** The book after a save, and whether the key must be written. */
    data class Saved(val book: ServerBook, val entry: ServerEntry, val keyChanged: Boolean)

    /**
     * Saves the typed URL and key for server [id]. A changed URL or key clears the last Test, which
     * was about the old ones (ServerEditorView.swift:100-104). Both are trimmed.
     */
    fun save(book: ServerBook, id: UUID, typedUrl: String, typedKey: String, savedKey: String?): Saved {
        val url = typedUrl.trim()
        val key = typedKey.trim()
        val old = book.server(id)
        val keyChanged = key != (savedKey ?: "")
        var entry = old ?: ServerEntry(id = id, name = ServerEntry.name(url), url = url)
        if (entry.url != url || keyChanged) entry = entry.copy(tested = null)
        entry = entry.copy(url = url, name = ServerEntry.name(url))
        return Saved(book.upsert(entry), entry, keyChanged)
    }

    /** The book after a Test: what it learned on success, nothing on failure (ServerEditorView.swift:153-159). */
    fun tested(book: ServerBook, id: UUID, tested: ServerEntry.Tested?): ServerBook {
        val entry = book.server(id) ?: return book
        if (entry.tested == tested) return book
        return book.upsert(entry.copy(tested = tested))
    }

    fun tested(info: ServerInfo, me: KeyInfo) = ServerEntry.Tested(
        version = info.version, mode = info.mode, keyName = me.name ?: me.id, scopes = me.scopes,
        live = info.supportsLive, liveEngines = info.liveEngines,
    )

    /** The line under the Test button after a pass. */
    fun describe(t: ServerEntry.Tested): String {
        val live = if (t.live) "live text with ${t.liveEngines.joinToString(", ")}" else "no live text on this server"
        val upload = if (t.canUpload) "" else " This key cannot upload recordings."
        return "akou ${t.version ?: "?"} (${t.mode ?: "?"}), key ${t.keyName ?: "?"} (${t.scopes.joinToString(", ")}), $live.$upload"
    }

    /** The line under the Test button after a failure. */
    fun failure(e: Throwable): String = when (e) {
        is Endpoint.Failure -> refusal(e)
        is ServerProbe.Failure.Status if e.status == 401 -> "The server refused the key: it is mistyped or was revoked."
        is ServerProbe.Failure.Status -> "The server answered HTTP ${e.status}${e.code?.let { " ($it)" } ?: ""}."
        ServerProbe.Failure.NotAkou -> "That address answers, but it is not an akou server."
        is IOException -> "The server did not answer: ${e.message ?: e.javaClass.simpleName}."
        else -> "Failed: ${e.message ?: e.javaClass.simpleName}."
    }
}
