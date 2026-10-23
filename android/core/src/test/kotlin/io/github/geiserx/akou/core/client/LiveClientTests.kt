// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.ogg.FixtureOpusEncoder
import io.github.geiserx.akou.core.ogg.OggOpusWriter
import io.github.geiserx.akou.core.ogg.OggOpusWriterTests
import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.LiveClientMessage
import io.github.geiserx.akou.core.protocol.LiveCloseCode
import io.github.geiserx.akou.core.protocol.LiveError
import io.github.geiserx.akou.core.protocol.LiveHello
import io.github.geiserx.akou.core.protocol.LiveReady
import io.github.geiserx.akou.core.protocol.LiveToken
import io.github.geiserx.akou.core.protocol.LiveWords
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/** Port of Tests/AkouKitTests/LiveClientTests.swift against [FakeLiveServer]. */
class LiveClientTests {
    /** One client per test, so sockets a test leaves behind cannot hold up the next one's. */
    private val http = OkHttpClient()

    @AfterTest
    fun tearDown() {
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    @Test
    fun testASessionFromHelloToClosed() = runBlocking {
        FakeLiveServer().use { server ->
            val url = server.start()
            val writer = OggOpusWriter(FixtureOpusEncoder(), serial = 42u)
            val headers = writer.headerPages()
            val client = withTimeout(5.seconds) {
                LiveClient.open(url, "ak_test", LiveHello(language = "es"), headers, http)
            }
            assertEquals(LiveReady(engine = "fake-live", lang = "auto", tierMs = 560, loadMs = 3), client.ready)

            val sent = ArrayList<ByteArray>()
            for (n in 0 until 30) {
                writer.append(OggOpusWriterTests.sine(n))?.let { page ->
                    sent += page
                    assertTrue(client.send(page))
                }
            }
            assertEquals(3, sent.size)
            assertTrue(client.stop())

            val events = withTimeout(5.seconds) { client.events.toList() }
            assertEquals(
                listOf(
                    LiveClient.Event.Words(LiveWords(listOf(LiveToken(" page1", 0.2, 0.9)))),
                    LiveClient.Event.Words(LiveWords(listOf(LiveToken(" page2", 0.4, 0.9)))),
                    LiveClient.Event.Words(LiveWords(listOf(LiveToken(" page3", 0.6, 0.9)))),
                    LiveClient.Event.Words(LiveWords(listOf(LiveToken(" end", 9.9, 0.8)))),
                    LiveClient.Event.Closed,
                    LiveClient.Event.Disconnected(1000),
                ),
                events,
            )
            assertEquals(listOf("Bearer ak_test"), server.authorization)
            assertEquals(2, server.texts.size)
            assertEquals(
                LiveClientMessage.Hello(LiveHello(language = "es")),
                AkouJson.decodeFromString(LiveClientMessage.serializer(), server.texts[0]),
            )
            assertEquals("""{"type":"stop"}""", server.texts[1])
            // Byte for byte what the phone writes to its file: header pages first, then the audio pages.
            assertEquals((headers + sent).map { it.toList() }, server.binaries.map { it.toList() })
            assertEquals(emptyList(), server.violations)
        }
    }

    @Test
    fun testABadKeyIsRefusedAtTheUpgrade() = runBlocking {
        FakeLiveServer().use { server ->
            val url = server.start()
            val f = assertFailsWith<LiveClient.Failure> {
                withTimeout(5.seconds) { LiveClient.open(url, "ak_wrong", LiveHello(), emptyList(), http) }
            }
            assertEquals(LiveClient.Failure.Handshake(401), f)
            assertEquals(emptyList(), server.texts)
        }
    }

    @Test
    fun testEngineBusyIsReportedWithItsCloseCode() = runBlocking {
        FakeLiveServer(behaviour = FakeLiveServer.Behaviour.EngineBusy).use { server ->
            val url = server.start()
            val f = assertFailsWith<LiveClient.Failure> {
                withTimeout(5.seconds) { LiveClient.open(url, "ak_test", LiveHello(), emptyList(), http) }
            }
            assertEquals(
                LiveClient.Failure.Refused(
                    LiveError("engine_busy", "another session uses another engine"),
                    LiveCloseCode.ENGINE_BUSY.code,
                ),
                f,
            )
        }
    }

    /**
     * No hand-rolled heartbeat: liveness is OkHttp's own pings. A quiet socket on a server that
     * answers stays up and carries no text frame besides `hello`; on a server that stops reading,
     * the missing pong fails the socket with no close code, which LiveSession reads as `network`.
     */
    @Test
    fun testPingsKeepAQuietSocketAndAMissingPongDropsIt() = runBlocking {
        FakeLiveServer().use { server ->
            val client = withTimeout(5.seconds) {
                LiveClient.open(server.start(), "ak_test", LiveHello(), emptyList(), http, pingInterval = 100.milliseconds)
            }
            kotlinx.coroutines.delay(700)
            assertTrue(server.pings >= 3, "only ${server.pings} pings in 700 ms at a 100 ms interval")
            assertEquals(1, server.texts.size, "nothing but hello went out as text: ${server.texts}")
            client.cancel()
        }
        // The control: the same client against a server that never reads after hello.
        FakeLiveServer(behaviour = FakeLiveServer.Behaviour.Stall(afterPages = 0)).use { server ->
            val client = withTimeout(5.seconds) {
                LiveClient.open(server.start(), "ak_test", LiveHello(), emptyList(), http, pingInterval = 100.milliseconds)
            }
            val last = withTimeout(5.seconds) { client.events.first { it is LiveClient.Event.Disconnected } }
            assertIs<LiveClient.Event.Disconnected>(last)
            assertEquals(null, last.code)
            assertEquals(0, server.pings, "the stalled server read no ping")
        }
    }
}
