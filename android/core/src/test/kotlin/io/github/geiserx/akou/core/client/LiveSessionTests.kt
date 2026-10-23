// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.client.FakeLiveServer.Behaviour
import io.github.geiserx.akou.core.client.FakeLiveServer.Frame
import io.github.geiserx.akou.core.client.LiveSession.State
import io.github.geiserx.akou.core.ogg.FixtureOpusEncoder
import io.github.geiserx.akou.core.ogg.OggOpusWriter
import io.github.geiserx.akou.core.ogg.OggOpusWriterTests
import io.github.geiserx.akou.core.ogg.OggPage
import io.github.geiserx.akou.core.protocol.LiveClientMessage
import io.github.geiserx.akou.core.protocol.LiveHello
import io.github.geiserx.akou.core.protocol.LiveReady
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume

/**
 * Port of Tests/AkouKitTests/LiveSessionTests.swift. The Swift tests run against FakeLiveServer
 * in real time with short backoffs, and so do these. The rules Swift checks only implicitly (the
 * backoff steps, the healthy-session reset, the stop timeout, the queue while connecting, one page
 * in OkHttp at a time) are tested on virtual time with a fake socket in [LiveSessionRulesTests].
 */
class LiveSessionTests {
    private val fakeEngine = State.Live("fake-live")

    /** One client per test, so sockets a test leaves behind cannot hold up the next one's. */
    private val http = OkHttpClient()

    @AfterTest
    fun tearDown() {
        http.dispatcher.executorService.shutdownNow()
        http.connectionPool.evictAll()
    }

    private fun session(base: String, pages: PageMaker, hello: LiveHello = LiveHello(), backoff: Duration) =
        LiveSession(base, "ak_test", hello, pages.headers, LiveSession.Policy(backoff = listOf(backoff)), http)

    @Test
    fun testTooFastReopensOnceWithTheHeadersThenTheCurrentPageAndNoBacklog() = runBlocking {
        FakeLiveServer(behaviours = listOf(Behaviour.TooFast(afterPages = 3), Behaviour.Normal)).use { server ->
            val pages = PageMaker(serial = 9u)
            val session = session(server.start(), pages, LiveHello(language = "es"), 400.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { fakeEngine in it.states }

            val first = (0 until 3).map { pages.next().also { p -> session.send(p.data, p.end) } }
            log.expect { State.Paused("too_fast") in it.states }

            // Produced while the session is down: they stay in the file and are never sent.
            val offline = (0 until 2).map { pages.next().also { p -> session.send(p.data, p.end) } }
            log.expect { it.states.count { s -> s == fakeEngine } == 2 }

            val second = (0 until 3).map { pages.next().also { p -> session.send(p.data, p.end) } }
            session.stop()
            log.expect { it.finished }

            assertEquals(
                listOf(State.Connecting, fakeEngine, State.Paused("too_fast"), State.Connecting, fakeEngine, State.Off("stopped")),
                log.states,
            )
            val sessions = server.sessions
            assertEquals(2, sessions.size, "exactly one reopen")
            val hello = Frame.Text(LiveClientMessage.Hello(LiveHello(language = "es")).json())
            val headers = pages.headers.map { Frame.Binary(it) }
            assertEquals(listOf(hello) + headers + first.map { Frame.Binary(it.data) }, sessions.first())
            assertEquals(
                listOf(hello) + headers + second.map { Frame.Binary(it.data) } + Frame.Text("""{"type":"stop"}"""),
                sessions.last(),
            )
            assertEquals(1, log.gaps.size)
            assertEquals(first.last().end, log.gaps.first().from, 1e-9)
            assertEquals(offline.last().end, log.gaps.first().to, 1e-9)
            assertEquals(emptyList(), server.violations)
        }
    }

    @Test
    fun testAStalledServerTripsTheQueueCapAndTheReopenSendsNoBacklog() = runBlocking {
        FakeLiveServer(behaviours = listOf(Behaviour.Stall(afterPages = 0), Behaviour.Normal)).use { server ->
            val pages = PageMaker(serial = 11u)
            val session = session(server.start(), pages, backoff = 50.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { fakeEngine in it.states }

            // Large pages fill the socket's buffers quickly; once the server stops reading, OkHttp's
            // queue stays non-empty, the session's own queue grows and the cap drops the session.
            var sent = 0
            var dropped = false
            while (!dropped && sent < 5000) {
                val p = pages.big(sent)
                sent += 1
                session.send(p.data, p.end)
                dropped = session.drops > 0
            }
            assertTrue(dropped, "the queue cap never tripped after $sent pages")
            assertEquals(25, session.queueHighWater, "the queue reaches the cap and never passes it")
            log.expect { it.states.count { s -> s == fakeEngine } == 2 }

            val after = (sent until sent + 3).map { pages.big(it).also { p -> session.send(p.data, p.end) } }
            session.stop()
            log.expect { it.finished }

            assertEquals(
                listOf(State.Connecting, fakeEngine, State.Paused("behind"), State.Connecting, fakeEngine, State.Off("stopped")),
                log.states,
            )
            val sessions = server.sessions
            assertEquals(2, sessions.size)
            assertEquals(
                pages.headers.map { Frame.Binary(it) } + after.map { Frame.Binary(it.data) } + Frame.Text("""{"type":"stop"}"""),
                sessions.last().drop(1),
                "the new session carries the headers, then only pages made after the drop",
            )
            val gap = assertNotNull(log.gaps.firstOrNull())
            assertEquals(after.first().end - 0.2, gap.to, 1e-9)
            assertTrue(gap.from < gap.to)
            assertEquals(emptyList(), server.violations)
        }
    }

    @Test
    fun testEngineBusyIsTerminal() = runBlocking {
        FakeLiveServer(behaviour = Behaviour.EngineBusy).use { server ->
            val pages = PageMaker(serial = 12u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { State.Off("engine_busy") in it.states }
            delay(300)
            assertEquals(listOf(State.Connecting, State.Off("engine_busy")), log.states)
            assertEquals(1, server.sessions.size, "no reopen")
            val p = pages.next()
            session.send(p.data, p.end)
            session.stop()
            assertEquals(1, server.sessions.size)
        }
    }

    @Test
    fun testKeyRevokedIsTerminal() = runBlocking {
        FakeLiveServer(behaviour = Behaviour.KeyRevoked(afterPages = 1)).use { server ->
            val pages = PageMaker(serial = 13u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { fakeEngine in it.states }
            val p = pages.next()
            session.send(p.data, p.end)
            log.expect { State.Off("key_revoked") in it.states }
            delay(300)
            assertEquals(listOf(State.Connecting, fakeEngine, State.Off("key_revoked")), log.states)
            assertEquals(1, server.sessions.size, "no reopen")
        }
    }

    /**
     * akou answers the upgrade with a plain 503 `no_live_engine` when it has no streaming model:
     * live text ends after one try instead of reopening every 30 s for the whole recording.
     */
    @Test
    fun testA503AtTheUpgradeIsNoLiveEngineAndTerminal() = runBlocking {
        PlainHttpRefusal(503, "no_live_engine").use { server ->
            val pages = PageMaker(serial = 14u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { State.Off("no_live_engine") in it.states }
            delay(300)
            assertEquals(listOf(State.Connecting, State.Off("no_live_engine")), log.states)
            assertEquals(1, server.requests, "no reopen")
            session.stop()
        }
    }

    /** The control for the test above: a 502 from a proxy is not the server saying no, so the session keeps trying. */
    @Test
    fun testA502AtTheUpgradeIsRetried() = runBlocking {
        PlainHttpRefusal(502, "bad_gateway").use { server ->
            val pages = PageMaker(serial = 15u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { server.requests >= 3 }
            assertTrue(State.Paused("network") in log.states)
            assertFalse(log.states.any { it is State.Off })
            session.stop()
        }
    }

    /**
     * The recorder waits for `events` to end after `stop()`, so it must end on every path: here
     * stop comes before start, and a start after it opens nothing.
     */
    @Test
    fun testStopBeforeStartFinishesTheEventsAndNothingOpens() = runBlocking {
        PlainHttpRefusal(502, "bad_gateway").use { server ->
            val pages = PageMaker(serial = 16u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.stop()
            log.expect { it.finished }
            session.start()
            delay(200)
            assertEquals(0, server.requests, "no session after stop")
            assertEquals(emptyList(), log.states)
        }
    }

    /** The same for a session already ended by the server: stop still returns with `events` done. */
    @Test
    fun testStopAfterATerminalRefusalFinishesTheEvents() = runBlocking {
        PlainHttpRefusal(503, "no_live_engine").use { server ->
            val pages = PageMaker(serial = 17u)
            val session = session(server.start(), pages, backoff = 20.milliseconds)
            val log = EventLog(session.events)
            session.start()
            log.expect { State.Off("no_live_engine") in it.states }
            session.stop()
            log.expect { it.finished }
        }
    }

    /**
     * Opt-in: streams the ffmpeg fixture in real time through a real akou server. Runs only with
     * AKOU_URL (the server's base URL) and AKOU_API_KEY set, for example against a throwaway server.
     */
    @Test
    fun testARealServerWhenConfigured() = runBlocking {
        val base = System.getenv("AKOU_URL")
        val key = System.getenv("AKOU_API_KEY")
        Assume.assumeTrue("set AKOU_URL and AKOU_API_KEY to run against a real akou server", base != null && key != null)
        val all = OggPage.readAll(io.github.geiserx.akou.core.ogg.Fixtures.ffmpegFile())
        val encoded = all.map { it.encoded() }
        val session = LiveSession(base!!, key!!, LiveHello(), encoded.take(2), client = http)
        val log = EventLog(session.events)
        session.start()
        log.expect(60.seconds) { it.states.any { s -> s is State.Live } }
        for ((page, data) in all.drop(2).zip(encoded.drop(2))) {
            session.send(data, (page.granulePosition - 312).toDouble() / 48000)
            delay(200)
        }
        session.stop()
        log.expect { it.finished }
        assertEquals(State.Off("stopped"), log.states.last())
        assertTrue(log.gaps.isEmpty())
    }
}

/** Real Ogg Opus pages from the writer, with the second each one ends at. */
class PageMaker(val serial: UInt) {
    class Page(val data: ByteArray, val end: Double)

    // The fixture has 51 packets; the tests need more, so they repeat.
    private val writer = OggOpusWriter(FixtureOpusEncoder(List(20) { FixtureOpusEncoder.fixturePackets() }.flatten()), serial)
    private var frame = 0
    val headers: List<ByteArray> = writer.headerPages()

    fun next(): Page {
        while (true) {
            val page = writer.append(OggOpusWriterTests.sine(frame))
            frame += 1
            if (page != null) return Page(page, writer.seconds)
        }
    }

    /**
     * A 60 KB page in the place of the [index]th 200 ms page: valid Ogg, not valid Opus, for the
     * stalled-server test where only the bytes on the wire matter.
     */
    fun big(index: Int): Page {
        val page = OggPage(
            granulePosition = (index + 1).toLong() * 9600,
            serial = serial,
            sequence = (index + 2).toUInt(),
            packets = listOf(ByteArray(60_000) { index.toByte() }),
        )
        return Page(page.encoded(), (index + 1) * 0.2)
    }
}

/** Collects a session's events as they come, for tests to wait on and inspect. */
class EventLog(flow: Flow<LiveSession.Event>, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    private val _events = CopyOnWriteArrayList<LiveSession.Event>()

    @Volatile
    var finished = false
        private set

    init {
        scope.launch {
            flow.collect { _events += it }
            finished = true
        }
    }

    val events: List<LiveSession.Event> get() = _events.toList()
    val states: List<State> get() = events.filterIsInstance<LiveSession.Event.StateChanged>().map { it.state }
    val gaps: List<LiveSession.Event.Gap> get() = events.filterIsInstance<LiveSession.Event.Gap>()

    suspend fun expect(timeout: Duration = 5.seconds, done: (EventLog) -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (done(this)) return
            delay(10)
        }
        fail("timed out; events so far: $events")
    }
}

/** What the fake socket in the virtual-time tests is handed, and how it answers. */
class FakeSocket(override val ready: LiveReady = LiveReady("fake")) : LiveSocket {
    private val channel = Channel<LiveClient.Event>(Channel.UNLIMITED)
    override val events: Flow<LiveClient.Event> = channel.receiveAsFlow()
    val sent = ArrayList<ByteArray>()
    var stops = 0
    var cancelled = false

    /** What `queueSize()` answers: bytes OkHttp has not written yet. */
    var pending = 0L

    /** What `send` answers: false is OkHttp having torn the socket down. */
    var accepts = true

    override fun send(page: ByteArray): Boolean {
        if (!accepts) return false
        sent += page
        return true
    }

    override fun queueSize(): Long = pending

    override fun stop(): Boolean {
        stops += 1
        return true
    }

    override fun cancel() {
        cancelled = true
        disconnect(null)
    }

    fun disconnect(code: Int?) {
        channel.trySend(LiveClient.Event.Disconnected(code))
        channel.close()
    }

    fun emit(event: LiveClient.Event) {
        channel.trySend(event)
    }
}
