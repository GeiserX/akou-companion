// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of Tests/AkouKitTests/ServerInfoTests.swift, reading Fixtures/server-0.6.2.json in place. */
class ServerInfoTests {
    private fun fixture(name: String): File {
        val dir = checkNotNull(System.getProperty("akou.fixtures")) { "akou.fixtures is not set" }
        return File(dir, name).also { check(it.isFile) { "missing fixture $it" } }
    }

    /** A `GET /v1/server` answer in akou 0.6.2's shape. */
    @Test
    fun testDecodesLiveEnginesAndRetainDaysFromA062Answer() {
        val info = AkouJson.decodeFromString(ServerInfo.serializer(), fixture("server-0.6.2.json").readText())
        assertEquals("0.6.2", info.version)
        assertTrue(info.supportsLive)
        assertEquals(listOf("nemotron-en-560", "nemotron-3.5-560"), info.liveEngines)
        assertEquals(30, info.retainDays)
    }

    /** A server older than the live route sends neither `live` nor `retain_days`; the desktop app sends `live: null`. */
    @Test
    fun testStillDecodesAnAnswerWithoutLive() {
        val old = AkouJson.decodeFromString(ServerInfo.serializer(), """{"name":"akou","version":"0.5.0","mode":"server","capabilities":{"jobs":true}}""")
        assertNull(old.live)
        assertNull(old.retainDays)
        assertEquals(emptyList(), old.liveEngines)
        assertFalse(old.supportsLive)
        val app = AkouJson.decodeFromString(ServerInfo.serializer(), """{"name":"akou","mode":"app","live":null,"retain_days":30,"capabilities":{"jobs":true,"live":false}}""")
        assertNull(app.live)
        assertEquals(30, app.retainDays)
        assertFalse(app.supportsLive)
    }

    /** Not in the Swift test: live text needs `capabilities.live == true`, even when engines are listed. */
    @Test
    fun testLiveNeedsTheCapabilityNotJustEngines() {
        val info = AkouJson.decodeFromString(ServerInfo.serializer(), """{"name":"akou","capabilities":{"live":null},"live":{"engines":["e"]}}""")
        assertFalse(info.supportsLive)
        assertEquals(listOf("e"), info.liveEngines)
    }
}
