// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.ogg.OggPage
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A local stand-in for akou's `GET /v1/live`, the port of FakeLiveServer.swift: a raw
 * [ServerSocket] WebSocket server (handshake plus frame parsing), because the stall behaviour must
 * stop reading the socket and MockWebServer always reads.
 *
 * It checks the bearer on the upgrade, answers `hello` with `ready`, echoes one token per audio
 * page and answers `stop` with the last words, `closed` and a normal close. Every frame has exactly
 * the fields akou's server sends (src/main/server/live.ts), no more. It also checks what akou checks
 * on the pages: one binary frame is one whole Ogg page, one serial per session, header pages 0 and
 * 1, then audio pages with consecutive sequence numbers. A page that breaks a rule is answered with
 * `bad_page` and close 4400, and is listed in [violations].
 *
 * Each session (a socket that sent `hello`) takes the next of [behaviours], the last one repeating,
 * so a test can make the first session fail and the reopened one work.
 */
class FakeLiveServer(
    val key: String = "ak_test",
    val behaviours: List<Behaviour> = listOf(Behaviour.Normal),
) : AutoCloseable {
    constructor(key: String = "ak_test", behaviour: Behaviour) : this(key, listOf(behaviour))

    sealed interface Behaviour {
        data object Normal : Behaviour

        /** Answer `hello` with an `engine_busy` error and close 4409. */
        data object EngineBusy : Behaviour

        /** After this many audio pages, report `too_fast` and close 4400, as akou does once more than 30 s of audio waits. */
        data class TooFast(val afterPages: Int) : Behaviour

        /** After this many audio pages, report `key_revoked` and close 4401. */
        data class KeyRevoked(val afterPages: Int) : Behaviour

        /** After this many audio pages, stop reading the socket: a server or network that stalls. Pings go unanswered too. */
        data class Stall(val afterPages: Int) : Behaviour
    }

    /** One frame a session received. */
    sealed interface Frame {
        data class Text(val text: String) : Frame

        class Binary(val data: ByteArray) : Frame {
            override fun equals(other: Any?) = other is Binary && data.contentEquals(other.data)
            override fun hashCode() = data.contentHashCode()
            override fun toString() = "Binary(${data.size} bytes)"
        }
    }

    init {
        require(behaviours.isNotEmpty())
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val released = CountDownLatch(1)
    private val lock = Any()
    private val _authorization = ArrayList<String>()
    private val _texts = ArrayList<String>()
    private val _binaries = ArrayList<ByteArray>()
    private val _sessions = ArrayList<MutableList<Frame>>()
    private val _violations = ArrayList<String>()
    private val _pings = AtomicInteger()

    val authorization: List<String> get() = synchronized(lock) { _authorization.toList() }
    val texts: List<String> get() = synchronized(lock) { _texts.toList() }
    val binaries: List<ByteArray> get() = synchronized(lock) { _binaries.toList() }

    /** Every session's frames in the order they arrived, one entry per `hello`. */
    val sessions: List<List<Frame>> get() = synchronized(lock) { _sessions.map { it.toList() } }

    /** Pages that broke one of akou's page rules. */
    val violations: List<String> get() = synchronized(lock) { _violations.toList() }

    /** Pings received from clients (each one answered with a pong unless the session stalls). */
    val pings: Int get() = _pings.get()

    /** Starts accepting and returns the base URL to give the client; it answers `/v1/live`. */
    fun start(): String {
        thread(isDaemon = true, name = "fake-live-accept") {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                sockets += s
                thread(isDaemon = true, name = "fake-live-conn") { Connection(s).run() }
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    override fun close() {
        released.countDown()
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private inner class Connection(private val socket: Socket) {
        private val input = socket.getInputStream().buffered()
        private val output = socket.getOutputStream()
        private var session: Int? = null
        private var binaryFrames = 0
        private var serial: UInt? = null
        private var lastSequence: UInt? = null
        private var closedByServer = false

        private val behaviour: Behaviour
            get() = behaviours[minOf(session ?: 0, behaviours.size - 1)]
        private val audioPages: Int get() = maxOf(0, binaryFrames - 2)

        fun run() {
            try {
                if (!handshake()) return
                frames()
            } catch (_: Exception) {
                // The client went away or the server is closing.
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun handshake(): Boolean {
            val head = readHead(input) ?: return false
            val lines = head.split("\r\n")
            val path = lines.first().split(" ").getOrNull(1)
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i < 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            val auth = headers["authorization"] ?: ""
            synchronized(lock) { _authorization += auth }
            if (path != "/v1/live") return plain(output, 404, "not_found").let { false }
            if (auth != "Bearer $key") return plain(output, 401, "unauthorized").let { false }
            val wsKey = headers["sec-websocket-key"] ?: return plain(output, 400, "bad_request").let { false }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((wsKey + WS_GUID).toByteArray(Charsets.US_ASCII)),
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            return true
        }

        private fun frames() {
            var opcode = 0
            val message = ByteArrayOutputStream()
            while (true) {
                val f = readFrame(input) ?: return
                when (f.opcode) {
                    OP_CLOSE -> {
                        if (!closedByServer) writeFrame(OP_CLOSE, f.payload)
                        return
                    }
                    OP_PING -> {
                        _pings.incrementAndGet()
                        writeFrame(OP_PONG, f.payload)
                        continue
                    }
                    OP_PONG -> continue
                }
                if (f.opcode != OP_CONTINUATION) {
                    opcode = f.opcode
                    message.reset()
                }
                message.write(f.payload)
                if (!f.fin) continue
                // After the server's close, akou reads nothing more from the session.
                if (closedByServer) continue
                when (opcode) {
                    OP_TEXT -> onText(message.toString(Charsets.UTF_8))
                    OP_BINARY -> onBinary(message.toByteArray())
                }
                val b = behaviour
                if (b is Behaviour.Stall && audioPages >= b.afterPages) {
                    released.await() // never read again: the client's sends back up, its pings go unanswered
                    return
                }
            }
        }

        private fun record(frame: Frame, hello: Boolean = false) = synchronized(lock) {
            if (hello && session == null) {
                session = _sessions.size
                _sessions += mutableListOf<Frame>()
            }
            session?.let { _sessions[it] += frame }
        }

        private fun onText(text: String) {
            synchronized(lock) { _texts += text }
            val type = runCatching {
                ((Json.parseToJsonElement(text) as JsonObject)["type"] as JsonPrimitive).content
            }.getOrNull()
            record(Frame.Text(text), hello = type == "hello")
            when {
                type == "hello" && behaviour == Behaviour.EngineBusy -> {
                    sendText("""{"type":"error","code":"engine_busy","message":"another session uses another engine"}""")
                    close(4409)
                }
                type == "hello" -> sendText("""{"type":"ready","engine":"fake-live","lang":"auto","tier_ms":560,"load_ms":3}""")
                type == "stop" -> {
                    sendText("""{"type":"words","tokens":[{"text":" end","t":9.9,"conf":0.8}]}""")
                    sendText("""{"type":"closed"}""")
                    close(1000)
                }
            }
        }

        private fun onBinary(data: ByteArray) {
            record(Frame.Binary(data))
            synchronized(lock) { _binaries += data }
            binaryFrames += 1
            val n = binaryFrames
            checkPage(data, n)?.let { why ->
                synchronized(lock) { _violations += "session ${session ?: -1} frame $n: $why" }
                sendText("""{"type":"error","code":"bad_page","message":"$why"}""")
                close(4400)
                return
            }
            // The first two binary frames of a session are the OpusHead and OpusTags pages.
            if (n <= 2) return
            val b = behaviour
            when {
                b is Behaviour.TooFast && n - 2 >= b.afterPages -> {
                    sendText("""{"type":"error","code":"too_fast","message":"more than 30 s of audio is waiting for the engine"}""")
                    close(4400)
                }
                b is Behaviour.KeyRevoked && n - 2 >= b.afterPages -> {
                    sendText("""{"type":"error","code":"key_revoked","message":"the key was revoked"}""")
                    close(4401)
                }
                else -> sendText("""{"type":"words","tokens":[{"text":" page${n - 2}","t":${(n - 2).toDouble() / 5},"conf":0.9}]}""")
            }
        }

        /** What akou refuses as `bad_page`; null when the page is fine. */
        private fun checkPage(data: ByteArray, n: Int): String? {
            if (session == null) return "audio before hello"
            val read = try {
                OggPage.read(data)
            } catch (e: Exception) {
                return "not an Ogg page: ${e.message}"
            }
            if (read.length != data.size) return "a frame of ${data.size} bytes holds a page of ${read.length}"
            val page = read.page
            val s = serial
            if (s == null) serial = page.serial else if (s != page.serial) return "serial ${page.serial} after $s"
            if (n <= 2) {
                if (page.sequence != (n - 1).toUInt()) return "header page $n has sequence ${page.sequence}"
            } else {
                val last = lastSequence
                if (last != null && page.sequence != last + 1u) return "sequence ${page.sequence} after $last"
            }
            if (n > 2) lastSequence = page.sequence
            return null
        }

        private fun sendText(text: String) = writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))

        private fun close(code: Int) {
            closedByServer = true
            writeFrame(OP_CLOSE, byteArrayOf((code shr 8).toByte(), code.toByte()))
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) = synchronized(output) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65536 -> {
                    output.write(126)
                    output.write(payload.size shr 8)
                    output.write(payload.size and 0xFF)
                }
                else -> {
                    output.write(127)
                    for (i in 7 downTo 0) output.write(((payload.size.toLong() shr (8 * i)) and 0xFF).toInt())
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    private class RawFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        /** One client frame (clients always mask), or null at the end of the stream. */
        fun readFrame(input: InputStream): RawFrame? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.readByte()
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = ((input.readByte() shl 8) or input.readByte()).toLong()
            if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or input.readByte().toLong() }
            }
            require(length <= 1 shl 20) { "frame of $length bytes" }
            val mask = if (b1 and 0x80 != 0) input.readExactly(4) else null
            val payload = input.readExactly(length.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return RawFrame(fin = b0 and 0x80 != 0, opcode = b0 and 0x0F, payload = payload)
        }

        fun InputStream.readByte(): Int = read().also { if (it < 0) throw EOFException() }

        fun InputStream.readExactly(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = read(out, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            return out
        }
    }
}

/** Reads an HTTP request head up to the blank line, or null if the peer closed first. */
internal fun readHead(input: InputStream): String? {
    val out = ByteArrayOutputStream()
    var tail = 0
    while (out.size() < 16384) {
        val b = input.read()
        if (b < 0) return null
        out.write(b)
        tail = (tail shl 8) or b
        if (tail == 0x0D0A0D0A) return out.toString(Charsets.US_ASCII).trimEnd()
    }
    return null
}

/** Writes a plain HTTP answer with a JSON error body and `Connection: close`. */
internal fun plain(output: OutputStream, status: Int, code: String) {
    val body = """{"error":"$code","message":"refused by the test server"}"""
    output.write(
        ("HTTP/1.1 $status Refused\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n$body").toByteArray(Charsets.US_ASCII),
    )
    output.flush()
}

/**
 * A server that answers every request, the live upgrade included, with one plain HTTP status and a
 * JSON error body, the way akou refuses `GET /v1/live` before any socket exists. It counts the
 * requests, so a test can tell a terminal refusal from one the session retries.
 */
class PlainHttpRefusal(val status: Int, val code: String) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val _requests = AtomicInteger()
    val requests: Int get() = _requests.get()

    fun start(): String {
        thread(isDaemon = true, name = "plain-http-refusal") {
            while (!server.isClosed) {
                val s = try {
                    server.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(isDaemon = true) {
                    s.use {
                        if (readHead(it.getInputStream()) == null) return@thread
                        _requests.incrementAndGet()
                        plain(it.getOutputStream(), status, code)
                    }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    override fun close() = server.close()
}
