// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.client.LiveSession.State
import io.github.geiserx.akou.core.protocol.LiveError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The LiveSession rules from LiveSession.swift:49-66 and :137-149 that the Swift tests reach only
 * through real sockets, on virtual time with [FakeSocket], plus the send rule of the port: one
 * page in OkHttp's queue at a time (docs/ANDROID.md, section 5, "OkHttp WebSocket send never waits").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSessionRulesTests {
    private fun page(i: Int) = byteArrayOf(i.toByte())
    private fun end(i: Int) = (i + 1) * 0.2

    private fun TestScope.session(policy: LiveSession.Policy = LiveSession.Policy(), connect: suspend () -> LiveSocket) =
        LiveSession(policy, backgroundScope, testScheduler.timeSource, connect)

    @Test
    fun testTheBackoffIsOneTwoFourEightThenThirtySeconds() = runTest {
        val attempts = ArrayList<Long>()
        val session = session { attempts += currentTime; throw LiveClient.Failure.Handshake(null) }
        val log = EventLog(session.events, backgroundScope)
        session.start()
        advanceTimeBy(76_000)
        runCurrent()
        assertEquals(listOf(1000L, 2000, 4000, 8000, 30_000, 30_000), attempts.zipWithNext { a, b -> b - a })
        assertTrue(log.states.all { it == State.Connecting || it == State.Paused("network") })
        session.stop()
    }

    private fun healthyRun(liveFor: Long): Long = run {
        var waitAfterDrop = -1L
        runTest {
            var calls = 0
            val socket = FakeSocket()
            var droppedAt = 0L
            val session = session {
                calls += 1
                when (calls) {
                    1, 2 -> throw LiveClient.Failure.Handshake(null) // attempt is 2 after these: the next wait would be 4 s
                    3 -> socket
                    else -> {
                        waitAfterDrop = currentTime - droppedAt
                        throw LiveClient.Failure.Handshake(401)
                    }
                }
            }
            session.start()
            advanceTimeBy(3_001) // 0 fails, 1 s fails, 3 s connects
            runCurrent()
            assertEquals(State.Live("fake"), session.state)
            advanceTimeBy(liveFor)
            droppedAt = currentTime
            socket.disconnect(null)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(State.Off("unauthorized"), session.state)
        }
        waitAfterDrop
    }

    @Test
    fun testAHealthySessionStartsTheBackoffOver() {
        assertEquals(1000L, healthyRun(liveFor = 30_000), "30 s live resets the backoff to its first step")
    }

    /** The control: a session that dropped before 30 s keeps climbing the backoff. */
    @Test
    fun testAShortSessionKeepsTheBackoff() {
        assertEquals(4000L, healthyRun(liveFor = 29_000))
    }

    @Test
    fun testStopWaitsAtMostTheStopTimeout() = runTest {
        val socket = FakeSocket() // never answers stop
        val session = session { socket }
        val log = EventLog(session.events, backgroundScope)
        session.start()
        runCurrent()
        val started = currentTime
        session.stop()
        assertEquals(10_000L, currentTime - started)
        assertEquals(1, socket.stops)
        runCurrent()
        assertEquals(State.Off("stopped"), log.states.last())
        assertTrue(log.finished, "events end when stop returns")
    }

    @Test
    fun testStopSendsTheQueueFirstThenStopAndEndsOnTheClose() = runTest {
        val socket = FakeSocket()
        val session = session { socket }
        val log = EventLog(session.events, backgroundScope)
        session.start()
        runCurrent()
        socket.pending = 1
        session.send(page(0), end(0))
        session.send(page(1), end(1))
        val stopped = CompletableDeferred<Unit>()
        backgroundScope.launch {
            session.stop()
            stopped.complete(Unit)
        }
        advanceTimeBy(1_000)
        assertEquals(0, socket.stops, "stop waits until OkHttp has written the queued pages")
        socket.pending = 0
        advanceTimeBy(100)
        assertEquals(listOf(0, 1), socket.sent.map { it[0].toInt() })
        assertEquals(1, socket.stops)
        socket.disconnect(1000)
        runCurrent()
        assertTrue(stopped.isCompleted)
        assertEquals(State.Off("stopped"), log.states.last())
    }

    @Test
    fun testWhileConnectingTheOldestPagesGiveWayPastTheCap() = runTest {
        val socket = FakeSocket()
        val gate = CompletableDeferred<Unit>()
        val session = session { gate.await(); socket }
        val log = EventLog(session.events, backgroundScope)
        session.start()
        for (i in 0 until 30) session.send(page(i), end(i))
        assertEquals(25, session.queueHighWater)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals((5 until 30).toList(), socket.sent.map { it[0].toInt() }, "the newest 25 pages, in order")
        assertEquals(listOf(LiveSession.Event.Gap(0.0, end(4))), log.gaps, "the dropped head is a gap")
        assertEquals(0, session.drops)
        session.cancel()
    }

    /**
     * The port rule: OkHttp's `send` never waits, so the session hands it one page and keeps the
     * rest until `queueSize()` is 0. A page that arrives with 25 queued drops the session with
     * `behind`, and the queue is thrown away, never flushed into the socket.
     */
    @Test
    fun testOnePageInOkHttpAtATimeAndABehindSessionIsNeverFlushed() = runTest {
        val socket = FakeSocket()
        val session = session(LiveSession.Policy(backoff = listOf(5.seconds))) { socket }
        val log = EventLog(session.events, backgroundScope)
        session.start()
        runCurrent()
        socket.pending = 1 // OkHttp is still writing whatever it was handed
        for (i in 0 until 25) {
            session.send(page(i), end(i))
            advanceTimeBy(200)
        }
        assertEquals(1, socket.sent.size, "only the head page is in OkHttp")
        assertEquals(25, session.queueHighWater)
        assertEquals(0, session.drops)
        session.send(page(25), end(25))
        assertEquals(1, session.drops)
        runCurrent()
        assertEquals(State.Paused("behind"), session.state)
        assertTrue(socket.cancelled)
        socket.pending = 0
        advanceTimeBy(1_000)
        assertEquals(1, socket.sent.size, "nothing of the dropped queue went out")
        assertTrue(State.Paused("behind") in log.states)
        session.cancel()
    }

    /** The control for the test above: 24 queued is not behind. */
    @Test
    fun testTwentyFourQueuedIsNotBehind() = runTest {
        val socket = FakeSocket()
        val session = session { socket }
        session.start()
        runCurrent()
        socket.pending = 1
        for (i in 0 until 25) session.send(page(i), end(i))
        assertEquals(0, session.drops)
        assertEquals(State.Live("fake"), session.state)
        socket.pending = 0
        advanceTimeBy(1_000)
        assertEquals((0 until 25).toList(), socket.sent.map { it[0].toInt() })
        session.cancel()
    }

    @Test
    fun testAFalseFromSendIsNetwork() = runTest {
        val socket = FakeSocket().apply { accepts = false }
        val session = session(LiveSession.Policy(backoff = listOf(5.seconds))) { socket }
        session.start()
        runCurrent()
        session.send(page(0), end(0))
        runCurrent()
        assertEquals(State.Paused("network"), session.state)
        session.cancel()
    }

    @Test
    fun testServerWordsAreForwardedAndAnErrorBeforeTheCloseNamesTheReason() = runTest {
        val socket = FakeSocket()
        val session = session(LiveSession.Policy(backoff = listOf(5.seconds))) { socket }
        session.start()
        runCurrent()
        socket.emit(LiveClient.Event.Error(LiveError("stream_lost")))
        socket.disconnect(4500)
        runCurrent()
        assertEquals(State.Paused("stream_lost"), session.state)
        session.cancel()
    }

    @Test
    fun testReasons() {
        assertEquals(setOf("too_fast", "stream_lost", "behind", "network"), LiveSession.RETRYABLE)
        val handshake = mapOf(null to "network", 401 to "unauthorized", 404 to "no_live_route", 503 to "no_live_engine", 502 to "network", 500 to "network", 426 to "http_426")
        for ((status, reason) in handshake) assertEquals(reason, LiveSession.reason(LiveClient.Failure.Handshake(status)), "status $status")
        assertEquals("engine_busy", LiveSession.reason(LiveClient.Failure.Refused(LiveError("engine_busy"), 4409)))
        assertEquals("network", LiveSession.reason(java.io.IOException("reset")))
        val close = mapOf(4400 to "bad_message", 4401 to "key_revoked", 4409 to "engine_busy", 4500 to "stream_lost", 4503 to "no_live_engine", 1000 to "network", null to "network")
        for ((code, reason) in close) {
            assertEquals(reason, LiveSession.reasonForClose(code), "code $code")
            assertEquals(reason, LiveSession.reason(LiveClient.Failure.ClosedBeforeReady(code)), "closed before ready, code $code")
        }
    }
}
