// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.URI
import java.util.UUID

/**
 * One akou server the phone knows: its name, its URL and what its last Test learned. Its `ak_` key
 * is never here: it lives in the [SecretStore] under the server's id.
 */
@Serializable
data class ServerEntry(
    @Serializable(with = UuidAsString::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    /** The base URL as typed, for example `https://akou.example.com`. */
    val url: String,
    /** What the last Test learned; null before the first one. */
    val tested: Tested? = null,
) {
    @Serializable
    data class Tested(
        val version: String? = null,
        /** `server` or `app` (a desktop akou, the only mode with calls and dictations). */
        val mode: String? = null,
        val keyName: String? = null,
        val scopes: List<String>,
        val live: Boolean,
        val liveEngines: List<String>,
    ) {
        val canUpload: Boolean get() = "jobs" in scopes || "admin" in scopes
        val seesHistory: Boolean get() = "read" in scopes || "admin" in scopes
    }

    /** The trimmed URL, or null when none was typed. */
    val baseUrl: String? get() = url.trim().ifEmpty { null }

    /** May recordings go here? Until a Test says otherwise, yes; after one, only with `jobs` or `admin`. */
    val canBeRecordTo: Boolean get() = tested?.canUpload ?: true

    companion object {
        /** The host of a URL, for naming a server; "akou" when there is none. */
        fun name(url: String): String {
            val host = runCatching { URI(url.trim()).host }.getOrNull()?.trim('[', ']')
            return if (host.isNullOrEmpty()) "akou" else host
        }
    }
}

/**
 * Every server the phone knows and the one recordings go to ("Record to"): live text, uploads and
 * the widget's snapshot use it. Kept as JSON by the app.
 */
@Serializable
data class ServerBook(
    val servers: List<ServerEntry> = emptyList(),
    @Serializable(with = UuidAsString::class) val recordTo: UUID? = null,
) {
    fun server(id: UUID?): ServerEntry? = id?.let { i -> servers.firstOrNull { it.id == i } }

    val recordToServer: ServerEntry? get() = server(recordTo)

    /**
     * Adds or replaces a server. The first server that may take recordings becomes "Record to";
     * "Record to" whose key turns out unable to upload passes to the next one that can.
     */
    fun upsert(entry: ServerEntry): ServerBook {
        val i = servers.indexOfFirst { it.id == entry.id }
        val next = copy(servers = if (i >= 0) servers.toMutableList().also { it[i] = entry } else servers + entry)
        return if (next.recordToServer?.canBeRecordTo == true) next else next.copy(recordTo = next.firstRecordTo())
    }

    /**
     * Removes a server. When it was "Record to", the next server that may take recordings is. Its
     * key is the caller's to delete; its queued recordings stay, parked.
     */
    fun remove(id: UUID): ServerBook {
        val next = copy(servers = servers.filter { it.id != id })
        return if (recordTo == id) next.copy(recordTo = next.firstRecordTo()) else next
    }

    private fun firstRecordTo(): UUID? = servers.firstOrNull { it.canBeRecordTo }?.id

    fun encoded(): String = AkouJson.encodeToString(serializer(), this)

    companion object {
        fun decode(json: String): ServerBook? = runCatching { AkouJson.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/**
 * Where the keys live: the Android Keystore in the app, a map in the tests. `account` is the
 * server's id as a string.
 */
interface SecretStore {
    fun load(account: String): String?

    fun save(secret: String, account: String): Boolean

    fun delete(account: String): Boolean
}

internal object UuidAsString : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
