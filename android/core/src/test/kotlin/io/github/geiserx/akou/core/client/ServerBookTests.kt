// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The key store as a map, with a switch to refuse writes. */
class MemorySecrets(items: Map<String, String> = emptyMap()) : SecretStore {
    private val items = items.toMutableMap()
    var refuseWrites = false

    val all: Map<String, String> get() = synchronized(this) { items.toMap() }

    override fun load(account: String): String? = synchronized(this) { items[account] }

    override fun save(secret: String, account: String): Boolean = synchronized(this) {
        if (refuseWrites) return false
        items[account] = secret
        true
    }

    override fun delete(account: String): Boolean = synchronized(this) {
        items.remove(account)
        true
    }
}

/**
 * The book's rules from Tests/AkouKitTests/ServerBookTests.swift. Its migration tests are not
 * ported: no Android install predates the book.
 */
class ServerBookTests {
    private val readScopes = ServerEntry.Tested(version = "0.7", mode = "app", keyName = "phone", scopes = listOf("read"), live = false, liveEngines = emptyList())

    @Test
    fun testRecordToFollowsAddsAndRemoves() {
        var book = ServerBook()
        val a = ServerEntry(name = "a", url = "https://a.example.com")
        val readOnly = ServerEntry(name = "r", url = "https://r.example.com", tested = readScopes)
        val b = ServerEntry(name = "b", url = "https://b.example.com")
        book = book.upsert(readOnly)
        assertNull(book.recordTo, "a read key cannot take recordings")
        book = book.upsert(a).upsert(b)
        assertEquals(a.id, book.recordTo, "the first server that may take recordings")
        book = book.remove(a.id)
        assertEquals(b.id, book.recordTo, "the next one that may take recordings, never the read-only one")
        book = book.remove(b.id)
        assertNull(book.recordTo)
        assertEquals(listOf(readOnly.id), book.servers.map { it.id })
    }

    /** A Test that finds "Record to"'s key cannot upload moves "Record to" to one that can. */
    @Test
    fun testRecordToLeavesAKeyThatCannotUpload() {
        var a = ServerEntry(name = "a", url = "https://a.example.com")
        val b = ServerEntry(name = "b", url = "https://b.example.com")
        var book = ServerBook().upsert(a).upsert(b)
        assertEquals(a.id, book.recordTo)
        a = a.copy(tested = readScopes.copy(version = null, keyName = "viewer"))
        book = book.upsert(a)
        assertEquals(b.id, book.recordTo)
        assertEquals(listOf(a.id, b.id), book.servers.map { it.id }, "an upsert replaces in place")
    }

    /** Not in the Swift tests: removing a server that is not "Record to" leaves "Record to" alone. */
    @Test
    fun testRemovingAnotherServerKeepsRecordTo() {
        val a = ServerEntry(name = "a", url = "https://a.example.com")
        val b = ServerEntry(name = "b", url = "https://b.example.com")
        val book = ServerBook().upsert(a).upsert(b).remove(b.id)
        assertEquals(a.id, book.recordTo)
    }

    @Test
    fun testScopesDecideUploadAndHistory() {
        fun t(vararg s: String) = readScopes.copy(scopes = s.toList())
        assertTrue(t("jobs").canUpload)
        assertFalse(t("jobs").seesHistory)
        assertTrue(t("read").seesHistory)
        assertFalse(t("read").canUpload)
        assertTrue(t("admin").canUpload && t("admin").seesHistory)
        assertTrue(ServerEntry(name = "n", url = "u").canBeRecordTo, "untested may take recordings")
        assertFalse(ServerEntry(name = "n", url = "u", tested = t()).canBeRecordTo)
    }

    /** The key is never in the book: its JSON holds only the server's id, name, URL and Test. */
    @Test
    fun testTheBookRoundTripsAndNeverHoldsAKey() {
        val secrets = MemorySecrets()
        val a = ServerEntry(name = "a", url = "https://a.example.com", tested = readScopes.copy(scopes = listOf("jobs"), live = true, liveEngines = listOf("parakeet")))
        secrets.save("ak_secret_value", a.id.toString())
        val book = ServerBook().upsert(a).upsert(ServerEntry(name = "b", url = ""))
        val json = book.encoded()
        assertFalse("ak_secret_value" in json)
        assertEquals(book, ServerBook.decode(json))
        val o = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("servers", "recordTo"), o.keys)
        for (s in o.getValue("servers").jsonArray) {
            assertTrue(s.jsonObject.keys.all { it in setOf("id", "name", "url", "tested") }, "${s.jsonObject.keys}")
        }
        assertEquals(setOf("version", "mode", "keyName", "scopes", "live", "liveEngines"), (o.getValue("servers").jsonArray[0].jsonObject.getValue("tested") as JsonObject).keys)
        assertNull(ServerBook.decode("not json"))
        assertEquals(ServerBook(), ServerBook.decode("""{"servers":[]}"""))
    }

    @Test
    fun testNamesAndBaseUrls() {
        assertEquals("akou.example.com", ServerEntry.name(" https://akou.example.com/x "))
        assertEquals("192.168.1.20", ServerEntry.name("http://192.168.1.20:8787"))
        assertEquals("akou", ServerEntry.name(""))
        assertEquals("akou", ServerEntry.name("not a url"))
        assertNull(ServerEntry(name = "n", url = "  ").baseUrl)
        assertEquals("https://a.example.com", ServerEntry(name = "n", url = " https://a.example.com ").baseUrl)
    }
}
