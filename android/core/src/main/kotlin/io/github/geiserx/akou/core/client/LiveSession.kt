// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.LiveCloseCode
import io.github.geiserx.akou.core.protocol.LiveHello
import io.github.geiserx.akou.core.protocol.LiveWords
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/** What the recorder needs from live text: [LiveSession] against a server, the demo's scripted text in the demo. */
interface LiveText {
    val events: Flow<LiveSession.Event>
    suspend fun start()
    suspend fun send(page: ByteArray, endsAt: Double)
    suspend fun stop()
}

/**
 * Live text for one recording, across as many sockets as the network needs. The port of
 * LiveSession.swift: the actor becomes a class whose state is touched only on one serial
 * dispatcher, with the same state machine and generation counter.
 *
 * The recorder hands every page to [send] and never waits on the network: the file is the
 * recording, and this only decides what reaches the server (docs/PROTOCOL.md, "Reconnecting").
 *
 * - Pages queue for the socket, at most [Policy.maxQueuedPages] (25 pages of 200 ms, 5 s of audio,
 *   far under the server's 30 s `too_fast` limit). A page that finds the queue full means the
 *   socket is behind: the session is dropped together with its queue, never flushed.
 * - OkHttp's `send` never waits and reports no completion, so this keeps its own queue and hands
 *   OkHttp one page at a time: the head page goes to the socket only when `queueSize()` is 0, and
 *   stays counted in the queue until `queueSize()` is back to 0. That is checked when a page
 *   arrives and every [Policy.sendCheck]. A false from `send` means OkHttp already tore the socket
 *   down: `network`.
 * - After a drop (`behind`, `too_fast`, `stream_lost`, or the network) it waits 1, 2, 4, 8, then
 *   30 s between attempts, and opens a new session: `hello`, the same two header pages, then the
 *   pages made from then on. Pages made while no socket was open stay in the file only; the next
 *   session's first page reports the hole as a [Event.Gap].
 * - Refusals a retry cannot fix (`key_revoked`, `no_live_engine`, `engine_busy`, `unknown_model`,
 *   `unsupported_language`, a refused upgrade) end live text for the recording: `Off(reason)` with
 *   the server's error code.
 */
class LiveSession internal constructor(
    private val policy: Policy,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val connector: suspend () -> LiveSocket,
) : LiveText {
    /**
     * A session against [url], the live route (`/v1/live` under the server's base URL), with
     * [headerPages] resent on every reopen.
     */
    constructor(
        url: HttpUrl,
        key: String,
        hello: LiveHello,
        headerPages: List<ByteArray>,
        policy: Policy = Policy(),
        client: OkHttpClient = LiveClient.defaultClient,
    ) : this(
        policy,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        TimeSource.Monotonic,
        { LiveClient.open(url, key, hello, headerPages, client, policy.pingInterval) },
    )

    sealed interface State {
        /** Opening a socket. Pages made meanwhile wait in the queue and go out once it is ready. */
        data object Connecting : State

        /** The socket is open and [engine] makes the words. */
        data class Live(val engine: String) : State

        /** No socket, waiting to reopen; [reason] is the server's error code, `behind` or `network`. */
        data class Paused(val reason: String) : State

        /** Live text is over for this recording: `stopped`, or why it cannot go on. */
        data class Off(val reason: String) : State
    }

    sealed interface Event {
        data class Words(val words: LiveWords) : Event

        /**
         * Live text has nothing between these two points of the recording, in seconds; the final
         * transcript fills it. Sent when a session's first page goes out after a hole.
         */
        data class Gap(val from: Double, val to: Double) : Event

        data class StateChanged(val state: State) : Event
    }

    data class Policy(
        /** The most pages waiting for the socket; one more drops the session. */
        val maxQueuedPages: Int = 25,
        /** The waits before each reopen; the last repeats. */
        val backoff: List<Duration> = listOf(1, 2, 4, 8, 30).map { it.seconds },
        /** How long [stop] waits for the server's last words. */
        val stopTimeout: Duration = 10.seconds,
        val pingInterval: Duration = 20.seconds,
        /** How often the head page is checked for having left OkHttp's queue, between page arrivals. */
        val sendCheck: Duration = 20.milliseconds,
    ) {
        init {
            require(maxQueuedPages > 0 && backoff.isNotEmpty())
        }
    }

    private class Page(val data: ByteArray, val start: Double, val end: Double)

    private val confined: CoroutineContext = checkNotNull(scope.coroutineContext[ContinuationInterceptor]) {
        "the session scope needs a dispatcher"
    }
    private val channel = Channel<Event>(Channel.UNLIMITED)
    override val events: Flow<Event> = channel.receiveAsFlow()

    @Volatile
    var state: State = State.Off("idle")
        private set

    /** Bumped whenever a socket is given up, so late callbacks from an old one change nothing. */
    private var generation = 0
    private var socket: LiveSocket? = null
    private val queue = ArrayDeque<Page>()

    /** The head of [queue] is in OkHttp's queue and not yet written. */
    private var headInFlight = false

    /** Where the newest page handed in ends: the recording's position. */
    private var position = 0.0

    /** Where the audio the server has received ends. */
    private var covered = 0.0
    private var sessionSentAudio = false
    private var lastError: String? = null
    private var attempt = 0
    private var liveSince: TimeMark? = null
    private var stopping = false
    private var stopSent = false
    private val stopWaiters = ArrayList<CompletableDeferred<Unit>>()
    private var timer: Job? = null
    private var sendChecker: Job? = null

    /** For tests: sessions dropped for falling behind, and the longest the queue got. */
    @Volatile
    internal var drops = 0
        private set

    @Volatile
    internal var queueHighWater = 0
        private set

    /** Opens the first session. */
    override suspend fun start() = withContext(confined) {
        if (stopping || state != State.Off("idle")) return@withContext
        open()
    }

    /**
     * Hands over the page just written to the file; [endsAt] is where it ends in the recording, in
     * seconds. Never waits for the network.
     */
    override suspend fun send(page: ByteArray, endsAt: Double) = withContext(confined) {
        val p = Page(page, position, endsAt)
        position = endsAt
        if (stopping) return@withContext
        when (state) {
            State.Connecting -> {
                queue.addLast(p)
                if (queue.size > policy.maxQueuedPages) queue.removeFirst()
            }
            is State.Live -> {
                if (queue.size >= policy.maxQueuedPages) {
                    drops += 1
                    drop("behind")
                    return@withContext
                }
                queue.addLast(p)
                pump()
            }
            is State.Paused, is State.Off -> return@withContext
        }
        queueHighWater = maxOf(queueHighWater, queue.size)
    }

    /**
     * The recording ended: sends what is queued, then `stop`, and waits (at most
     * [Policy.stopTimeout]) for the server's last words and `closed`. [events] has finished when
     * it returns, on every path, so a reader can wait for its loop to end without a timeout.
     */
    override suspend fun stop() {
        val waiter = withContext(confined) {
            if (stopping) return@withContext null
            stopping = true
            // Already off, or never started: no session to wait for, but events must still end.
            if (state is State.Off) {
                channel.close()
                return@withContext null
            }
            if (state !is State.Live) {
                end("stopped")
                return@withContext null
            }
            val gen = generation
            timer?.cancel()
            timer = scope.launch {
                delay(policy.stopTimeout)
                stopTimedOut(gen)
            }
            CompletableDeferred<Unit>().also {
                stopWaiters += it
                pump()
            }
        }
        waiter?.await()
    }

    /** Ends live text at once, without waiting for the server. */
    suspend fun cancel() = withContext(confined) {
        if (state is State.Off) return@withContext
        end("cancelled")
    }

    // Sessions. Everything below runs on [confined].

    private fun open() {
        generation += 1
        val gen = generation
        lastError = null
        sessionSentAudio = false
        set(State.Connecting)
        scope.launch { connect(gen) }
    }

    private suspend fun connect(gen: Int) {
        val c = try {
            connector()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen != generation) return
            val reason = reason(e)
            if (reason in RETRYABLE) retry(reason) else end(reason)
            return
        }
        if (gen != generation) {
            c.cancel()
            return
        }
        socket = c
        liveSince = timeSource.markNow()
        set(State.Live(c.ready.engine))
        scope.launch { c.events.collect { handle(it, gen) } }
        if (stopping) return end("stopped")
        sendChecker = scope.launch {
            while (gen == generation) {
                delay(policy.sendCheck)
                if (gen == generation) pump()
            }
        }
        pump()
    }

    private fun handle(event: LiveClient.Event, gen: Int) {
        if (gen != generation) return
        when (event) {
            is LiveClient.Event.Words -> channel.trySend(Event.Words(event.words))
            is LiveClient.Event.Error -> lastError = event.error.code
            LiveClient.Event.Closed -> Unit
            is LiveClient.Event.Disconnected -> {
                // A head page OkHttp finished writing before the close reached the server.
                socket?.let { if (headInFlight && it.queueSize() == 0L) confirmHead() }
                socket = null
                if (stopping) return end("stopped")
                val reason = lastError ?: reasonForClose(event.code)
                if (reason in RETRYABLE) drop(reason) else end(reason)
            }
        }
    }

    private fun confirmHead() {
        val p = queue.removeFirst()
        covered = p.end
        headInFlight = false
    }

    /**
     * Moves the queue along: confirms the head page once OkHttp has written it, hands the next one
     * over, and sends `stop` once stopping and empty. At most one page is ever in OkHttp's queue.
     */
    private fun pump() {
        val c = socket ?: return
        while (true) {
            if (headInFlight) {
                if (c.queueSize() > 0) break
                confirmHead()
            }
            val p = queue.firstOrNull() ?: break
            if (!sessionSentAudio) {
                sessionSentAudio = true
                if (p.start > covered + 1e-6) channel.trySend(Event.Gap(covered, p.start))
            }
            if (!c.send(p.data)) return drop("network")
            headInFlight = true
        }
        if (stopping && !stopSent && queue.isEmpty()) {
            stopSent = true
            if (!c.stop()) end("stopped")
        }
    }

    /** Gives up the socket and its queue, then waits to reopen. */
    private fun drop(reason: String) {
        abandonSocket()
        liveSince?.let { if (it.elapsedNow() >= HEALTHY_SESSION) attempt = 0 }
        liveSince = null
        retry(reason)
    }

    private fun retry(reason: String) {
        if (stopping) return end("stopped")
        set(State.Paused(reason))
        val wait = policy.backoff[minOf(attempt, policy.backoff.size - 1)]
        attempt += 1
        val gen = generation
        timer?.cancel()
        timer = scope.launch {
            delay(wait)
            reopen(gen)
        }
    }

    private fun reopen(gen: Int) {
        if (gen != generation || state !is State.Paused) return
        open()
    }

    private fun stopTimedOut(gen: Int) {
        if (!stopping || gen != generation) return
        end("stopped")
    }

    private fun end(reason: String) {
        abandonSocket()
        timer?.cancel()
        timer = null
        set(State.Off(reason))
        channel.close()
        stopWaiters.forEach { it.complete(Unit) }
        stopWaiters.clear()
    }

    private fun abandonSocket() {
        generation += 1
        sendChecker?.cancel()
        sendChecker = null
        socket?.cancel()
        socket = null
        queue.clear()
        headInFlight = false
    }

    private fun set(new: State) {
        if (new == state) return
        state = new
        channel.trySend(Event.StateChanged(new))
    }

    internal companion object {
        /** Error codes worth a new session; every other refusal is final. */
        val RETRYABLE = setOf("too_fast", "stream_lost", "behind", "network")

        /** A session that stayed open this long starts the backoff over. */
        val HEALTHY_SESSION = 30.seconds

        fun reason(error: Throwable): String = when (error) {
            is LiveClient.Failure.Refused -> error.error.code
            is LiveClient.Failure.Handshake -> when (val s = error.status) {
                null -> "network"
                401 -> "unauthorized"
                404 -> "no_live_route"
                // akou answers the upgrade with a plain 503 when no streaming model is on disk; any
                // other 5xx is a proxy or a restart, worth another try.
                503 -> "no_live_engine"
                else -> if (s >= 500) "network" else "http_$s"
            }
            is LiveClient.Failure.ClosedBeforeReady -> reasonForClose(error.closeCode)
            else -> "network"
        }

        /** A close that came without an error frame. */
        fun reasonForClose(code: Int?): String = when (code?.let { LiveCloseCode.of(it) }) {
            LiveCloseCode.BAD_PAGE -> "bad_message"
            LiveCloseCode.KEY_REVOKED -> "key_revoked"
            LiveCloseCode.ENGINE_BUSY -> "engine_busy"
            LiveCloseCode.STREAM_LOST -> "stream_lost"
            LiveCloseCode.NO_LIVE_ENGINE -> "no_live_engine"
            null -> "network"
        }
    }
}
