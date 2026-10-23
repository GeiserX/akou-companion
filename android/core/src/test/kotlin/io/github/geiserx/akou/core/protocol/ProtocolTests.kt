// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Port of Tests/AkouKitTests/ProtocolTests.swift. */
class ProtocolTests {
    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun expected(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
        pairs.associate { (k, v) ->
            k to when (v) {
                is String -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> error("unsupported $v")
            } as JsonElement
        },
    )

    /**
     * The iOS test compares one sorted-keys string. kotlinx.serialization cannot sort keys and the
     * server does not care about order, so this checks the key set and every value instead.
     */
    @Test
    fun testHelloOnTheWire() {
        val json = LiveClientMessage.Hello(LiveHello(language = "es", model = "nemotron-3.5-560")).json()
        assertEquals(
            expected("type" to "hello", "v" to 1, "codec" to "ogg-opus", "language" to "es", "model" to "nemotron-3.5-560"),
            obj(json),
        )
        val pcm = LiveClientMessage.Hello(LiveHello(codec = LiveCodec.PCM16)).json()
        assertEquals(
            expected("type" to "hello", "v" to 1, "codec" to "pcm16", "language" to "auto", "model" to "auto"),
            obj(pcm),
        )
    }

    /** The server refuses a hello without v, codec, language or model, so defaults must be written. */
    @Test
    fun testHelloBuiltFromDefaultsKeepsEveryKey() {
        val wire = obj(LiveClientMessage.Hello(LiveHello()).json())
        assertEquals(setOf("type", "v", "codec", "language", "model"), wire.keys)
        assertEquals(
            expected("type" to "hello", "v" to 1, "codec" to "ogg-opus", "language" to "auto", "model" to "auto"),
            wire,
        )
    }

    /** Positive control: with kotlinx.serialization's default config the defaults vanish, so the key-set check above can fail. */
    @Test
    fun testHelloKeySetCheckFailsWhenDefaultsAreDropped() {
        val dropped = obj(Json.encodeToString(LiveHello.serializer(), LiveHello()))
        assertNotEquals(setOf("v", "codec", "language", "model"), dropped.keys)
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun testStopOnTheWire() {
        assertEquals("""{"type":"stop"}""", LiveClientMessage.Stop.json())
    }

    @Test
    fun testClientMessagesRoundTrip() {
        val all = listOf(
            LiveClientMessage.Hello(LiveHello()),
            LiveClientMessage.Hello(LiveHello(codec = LiveCodec.PCM16, language = "en", model = "x")),
            LiveClientMessage.Stop,
        )
        for (m in all) {
            assertEquals(m, AkouJson.decodeFromString(LiveClientMessage.serializer(), m.json()))
        }
        assertFailsWith<IllegalArgumentException> {
            AkouJson.decodeFromString(LiveClientMessage.serializer(), """{"type":"start"}""")
        }
    }

    /** The frames exactly as akou's server builds them: `words` carries only `type` and `tokens`, `ready` may carry a null `tier_ms`. */
    @Test
    fun testTheServersLiteralFramesParse() {
        assertEquals(
            LiveServerMessage.Words(LiveWords(listOf(LiveToken(" hola", 1.23, 0.91)))),
            LiveServerMessage.parse("""{"type":"words","tokens":[{"text":" hola","t":1.23,"conf":0.91}]}"""),
        )
        assertEquals(
            LiveServerMessage.Ready(LiveReady("nemotron-3.5-560", lang = "auto", tierMs = null, loadMs = 12)),
            LiveServerMessage.parse("""{"type":"ready","engine":"nemotron-3.5-560","lang":"auto","tier_ms":null,"load_ms":12}"""),
        )
    }

    @Test
    fun testServerMessagesParse() {
        assertEquals(
            LiveServerMessage.Ready(LiveReady("nemotron-3.5-560", lang = "auto", tierMs = 560, loadMs = 4210)),
            LiveServerMessage.parse("""{"type":"ready","engine":"nemotron-3.5-560","lang":"auto","tier_ms":560,"load_ms":4210}"""),
        )
        assertEquals(
            LiveServerMessage.Words(LiveWords(listOf(LiveToken(" hola", 1.23, 0.91), LiveToken("s", 1.4)))),
            LiveServerMessage.parse("""{"type":"words","tokens":[{"text":" hola","t":1.23,"conf":0.91},{"text":"s","t":1.4}]}"""),
        )
        assertEquals(LiveServerMessage.Closed, LiveServerMessage.parse("""{"type":"closed"}"""))
        assertEquals(
            LiveServerMessage.Error(LiveError("engine_busy", "another session uses nemotron-en-560")),
            LiveServerMessage.parse("""{"type":"error","code":"engine_busy","message":"another session uses nemotron-en-560"}"""),
        )
        // A newer server's message is ignored, not an error.
        assertEquals(LiveServerMessage.Unknown("progress"), LiveServerMessage.parse("""{"type":"progress","x":1}"""))
        assertFailsWith<IllegalArgumentException> { LiveServerMessage.parse("""{"engine":"no type"}""") }
    }

    /** Not in the Swift test: a known type with a newer server's extra field still parses. */
    @Test
    fun testServerMessagesIgnoreUnknownFields() {
        assertEquals(
            LiveServerMessage.Ready(LiveReady("e")),
            LiveServerMessage.parse("""{"type":"ready","engine":"e","new_field":{"a":[1,2]}}"""),
        )
        assertEquals(
            LiveServerMessage.Words(LiveWords(listOf(LiveToken(" a", 0.5)))),
            LiveServerMessage.parse("""{"type":"words","tokens":[{"text":" a","t":0.5,"speaker":"c2"}],"seq":3}"""),
        )
    }

    @Test
    fun testServerMessagesRoundTrip() {
        val all = listOf(
            LiveServerMessage.Ready(LiveReady("e", lang = "es", tierMs = 1120, loadMs = 1)),
            LiveServerMessage.Words(LiveWords(listOf(LiveToken(" a", 0.5, conf = null)))),
            LiveServerMessage.Closed,
            LiveServerMessage.Error(LiveError("bad_page", message = null)),
            LiveServerMessage.Unknown("progress"),
        )
        for (m in all) {
            val text = AkouJson.encodeToString(LiveServerMessage.serializer(), m)
            assertEquals(m, LiveServerMessage.parse(text))
        }
    }

    @Test
    fun testCloseCodes() {
        assertEquals(4400, LiveCloseCode.BAD_PAGE.code)
        assertEquals(4401, LiveCloseCode.KEY_REVOKED.code)
        assertEquals(4409, LiveCloseCode.ENGINE_BUSY.code)
        assertEquals(4500, LiveCloseCode.STREAM_LOST.code)
        assertEquals(LiveCloseCode.NO_LIVE_ENGINE, LiveCloseCode.of(4503))
        assertEquals(listOf(4400, 4401, 4409, 4500, 4503), LiveCloseCode.entries.map { it.code })
        assertNull(LiveCloseCode.of(1000))
    }

    @Test
    fun testServerInfoReadsOnlyWhatItNeeds() {
        val json = """{"name":"akou","version":"0.7.0","mode":"server","presets":[{"name":"auto"}],"capabilities":{"jobs":true,"live":true,"wyoming":false},"retain_days":7}"""
        val info = AkouJson.decodeFromString(ServerInfo.serializer(), json)
        assertEquals("0.7.0", info.version)
        assertTrue(info.supportsLive)
        val old = """{"name":"akou","version":"0.6.1","mode":"server","capabilities":{"jobs":true}}"""
        assertFalse(AkouJson.decodeFromString(ServerInfo.serializer(), old).supportsLive)
    }
}
