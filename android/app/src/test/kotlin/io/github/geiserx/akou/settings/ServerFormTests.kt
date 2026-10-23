// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.settings

import io.github.geiserx.akou.core.client.ServerBook
import io.github.geiserx.akou.core.client.ServerEntry
import io.github.geiserx.akou.core.client.ServerProbe
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings screen's save and Test rules (ServerEditorView.swift, ServerEditorTests.swift). */
class ServerFormTests {
    private val id = UUID.randomUUID()
    private val tested = ServerEntry.Tested(version = "1.2.2", mode = "server", keyName = "phone", scopes = listOf("jobs"), live = true, liveEngines = listOf("parakeet"))

    @Test
    fun urlsFollowEndpoint() {
        assertNull(ServerForm.urlProblem("https://akou.example.com"))
        assertNull(ServerForm.urlProblem("  http://192.168.1.20:8787  "))
        assertNull(ServerForm.urlProblem("http://100.101.102.103:8787"))
        assertNotNull(ServerForm.urlProblem(""))
        assertTrue(ServerForm.urlProblem("http://akou.example.com")!!.contains("Plain http is refused"))
        assertTrue(ServerForm.urlProblem("http://localhost:8787")!!.contains("Plain http is refused"))
        assertTrue(ServerForm.urlProblem("http://8.8.8.8")!!.contains("Plain http is refused"))
        assertNotNull(ServerForm.urlProblem("ftp://akou.example.com"))
        assertNotNull(ServerForm.urlProblem("akou.example.com"))
    }

    @Test
    fun theFirstSaveMakesTheServerRecordTo() {
        val s = ServerForm.save(ServerBook(), id, " https://akou.example.com ", " ak_one\n", null)
        assertEquals(id, s.book.recordTo)
        assertEquals("https://akou.example.com", s.entry.url)
        assertEquals("akou.example.com", s.entry.name)
        assertTrue(s.keyChanged)
    }

    @Test
    fun aChangedUrlOrKeyClearsTheTest() {
        val book = ServerBook().upsert(ServerEntry(id, "akou.example.com", "https://akou.example.com", tested))
        val same = ServerForm.save(book, id, "https://akou.example.com", "ak_one ", "ak_one")
        assertEquals(tested, same.entry.tested)
        assertFalse(same.keyChanged, "a trailing space is not a new key")
        assertNull(ServerForm.save(book, id, "https://other.example.com", "ak_one", "ak_one").entry.tested)
        val newKey = ServerForm.save(book, id, "https://akou.example.com", "ak_two", "ak_one")
        assertNull(newKey.entry.tested)
        assertTrue(newKey.keyChanged)
    }

    @Test
    fun aFailedTestForgetsWhatTheLastOneLearned() {
        val book = ServerBook().upsert(ServerEntry(id, "akou.example.com", "https://akou.example.com", tested))
        assertNull(ServerForm.tested(book, id, null).server(id)!!.tested)
        assertEquals(tested, ServerForm.tested(ServerForm.tested(book, id, null), id, tested).server(id)!!.tested)
    }

    @Test
    fun failuresReadPlainly() {
        assertTrue(ServerForm.failure(ServerProbe.Failure.Status(401, "unauthorized")).contains("refused the key"))
        assertTrue(ServerForm.failure(ServerProbe.Failure.NotAkou).contains("not an akou server"))
        assertTrue(ServerForm.failure(java.net.ConnectException("refused")).contains("did not answer"))
        assertTrue(ServerForm.describe(tested).contains("live text with parakeet"))
        assertTrue(ServerForm.describe(tested.copy(scopes = listOf("read"))).contains("cannot upload"))
    }
}
