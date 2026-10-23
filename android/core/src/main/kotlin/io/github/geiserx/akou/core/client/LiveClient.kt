// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.LiveClientMessage
import io.github.geiserx.akou.core.protocol.LiveError
import io.github.geiserx.akou.core.protocol.LiveHello
import io.github.geiserx.akou.core.protocol.LiveReady
import io.github.geiserx.akou.core.protocol.LiveServerMessage
import io.github.geiserx.akou.core.protocol.LiveWords
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * What [LiveSession] needs from one open socket. [LiveClient] is the real one; tests use a fake.
 */
interface LiveSocket {
    /** The server's answer to `hello`. */
    val ready: LiveReady

    /** Everything the server sends after `ready`, ending with [LiveClient.Event.Disconnected]. One collector. */
    val events: Flow<LiveClient.Event>

    /**
     * Hands one binary frame to the socket and returns at once. False means the socket is already
     * torn down: the page did not go and never will.
     */
    fun send(page: ByteArray): Boolean

    /** Bytes handed to the socket and not yet written to the network. */
    fun queueSize(): Long

    /** Sends `stop`; false as for [send]. */
    fun stop(): Boolean

    /** Drops the socket without `stop`. */
    fun cancel()
}

/**
 * One live session on akou's `GET /v1/live`: one WebSocket, from `hello` to the close. The port of
 * LiveClient.swift on OkHttp.
 *
 * There is no resume. After a network drop the caller opens a new session and keeps sending from
 * the current page; the pages it missed are in the phone's file and the final pass covers them.
 *
 * Liveness is OkHttp's own ping every [open]'s `pingInterval` (20 s). OkHttp's `WebSocket` has no
 * ping to send by hand, and a text heartbeat would be refused by the server as `bad_message`. A pong
 * that does not come back within the interval fails the socket, which ends [events] with
 * `Disconnected(null)`: LiveSession reads that as `network` and reopens.
 */
class LiveClient private constructor(
    private val ws: WebSocket,
    private val connection: Connection,
    override val ready: LiveReady,
) : LiveSocket {
    sealed interface Event {
        data class Words(val words: LiveWords) : Event

        /** The server finished after `stop`. */
        data object Closed : Event

        data class Error(val error: LiveError) : Event

        /**
         * The socket ended. [code] is the WebSocket close code when the server sent one (1000 after
         * `closed`, or a `LiveCloseCode`), null when it failed without one.
         */
        data class Disconnected(val code: Int?) : Event
    }

    sealed class Failure(message: String) : Exception(message) {
        /**
         * The upgrade was refused (401 for a bad key, 404 on a server without the route, 503 with no
         * live engine) or the connection failed before it; [status] is the HTTP status when there was an answer.
         */
        data class Handshake(val status: Int?) : Failure("live upgrade failed${status?.let { " with HTTP $it" } ?: ""}")

        /** The server answered `hello` with an error and closed the socket. */
        data class Refused(val error: LiveError, val closeCode: Int?) : Failure("live session refused: ${error.code}")

        /** The socket closed before `ready` without an error message. */
        data class ClosedBeforeReady(val closeCode: Int?) : Failure("live socket closed before ready (${closeCode ?: "no code"})")
    }

    override val events: Flow<Event> = connection.events.receiveAsFlow()

    /**
     * Sends one audio frame: one Ogg page for `ogg-opus`. Pages within a session must be
     * consecutive (the server closes 4400 on a gap), so a caller that falls behind does not skip
     * pages: it cancels and opens a new session from the current page.
     */
    override fun send(page: ByteArray): Boolean = ws.send(page.toByteString())

    override fun queueSize(): Long = ws.queueSize()

    /** Ends the recording: the server sends the last words, then `closed`, then closes. */
    override fun stop(): Boolean = ws.send(LiveClientMessage.Stop.json())

    /** Drops the socket without `stop`, for example when live text is given up. */
    override fun cancel() {
        ws.cancel()
        connection.finish(null)
    }

    /**
     * Reads the socket on OkHttp's reader thread. Until `ready` it settles [ready]; after it, it
     * maps server messages to [events]. OkHttp calls a listener from one thread at a time.
     */
    private class Connection : WebSocketListener() {
        val ready = CompletableDeferred<LiveReady>()
        val events = Channel<Event>(Channel.UNLIMITED)
        private val finished = AtomicBoolean(false)
        private var opened = false
        private var refusal: LiveError? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = try {
                LiveServerMessage.parse(text)
            } catch (_: IllegalArgumentException) {
                null
            }
            if (!ready.isCompleted) {
                when (message) {
                    is LiveServerMessage.Ready -> ready.complete(message.ready)
                    // The server closes right after an error; the close says with which code.
                    is LiveServerMessage.Error -> refusal = message.error
                    null -> {
                        ready.completeExceptionally(Failure.ClosedBeforeReady(null))
                        webSocket.cancel()
                    }
                    else -> Unit
                }
                return
            }
            when (message) {
                is LiveServerMessage.Words -> events.trySend(Event.Words(message.words))
                LiveServerMessage.Closed -> events.trySend(Event.Closed)
                is LiveServerMessage.Error -> events.trySend(Event.Error(message.error))
                else -> Unit
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 1005 is OkHttp's "the close frame carried no code".
            val c = code.takeUnless { it == NO_STATUS_CODE }
            webSocket.close(NORMAL_CLOSURE, null)
            if (!ready.isCompleted) {
                ready.completeExceptionally(refusal?.let { Failure.Refused(it, c) } ?: Failure.ClosedBeforeReady(c))
            }
            finish(c)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finish(code.takeUnless { it == NO_STATUS_CODE })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!ready.isCompleted) {
                val status = response?.code
                val refused = refusal
                ready.completeExceptionally(
                    when {
                        status != null && status != SWITCHING_PROTOCOLS -> Failure.Handshake(status)
                        refused != null -> Failure.Refused(refused, null)
                        !opened -> Failure.Handshake(null)
                        else -> Failure.ClosedBeforeReady(null)
                    },
                )
            }
            finish(null)
        }

        /** Ends [events] once, with the close code when there was one. */
        fun finish(code: Int?) {
            if (!finished.compareAndSet(false, true)) return
            events.trySend(Event.Disconnected(code))
            events.close()
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val NO_STATUS_CODE = 1005
        private const val SWITCHING_PROTOCOLS = 101

        /** The client used when none is given. */
        val defaultClient: OkHttpClient by lazy { OkHttpClient() }

        /**
         * Opens a session: connects to [url] (the live route, `/v1/live` under the server's base,
         * built by the caller's endpoint rules) with [key] as a bearer on the upgrade, sends `hello`,
         * waits for `ready`, then sends [headerPages] (the OpusHead and OpusTags pages for `ogg-opus`).
         * [client] gets [pingInterval] on a derived client that shares its pool and dispatcher.
         */
        suspend fun open(
            url: HttpUrl,
            key: String,
            hello: LiveHello,
            headerPages: List<ByteArray>,
            client: OkHttpClient = defaultClient,
            pingInterval: Duration = 20.seconds,
        ): LiveClient {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
            val connection = Connection()
            val ws = client.newBuilder()
                .pingInterval(pingInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .build()
                .newWebSocket(request, connection)
            // OkHttp holds the hello until the upgrade succeeds; false means it already gave up.
            if (!ws.send(LiveClientMessage.Hello(hello).json())) {
                ws.cancel()
                throw Failure.Handshake(null)
            }
            val ready = try {
                connection.ready.await()
            } catch (e: CancellationException) {
                ws.cancel()
                throw e
            } catch (e: Failure) {
                ws.cancel()
                throw e
            }
            for (page in headerPages) {
                if (!ws.send(page.toByteString())) {
                    ws.cancel()
                    throw IOException("the live socket closed while sending the header pages")
                }
            }
            return LiveClient(ws, connection, ready)
        }
    }
}
