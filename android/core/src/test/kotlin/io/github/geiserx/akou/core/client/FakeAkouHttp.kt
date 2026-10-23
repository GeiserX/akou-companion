// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.io.Closeable
import java.net.InetAddress
import java.util.UUID

/**
 * A stand-in for akou's job routes on a MockWebServer, the port of Tests/AkouKitTests/FakeAkouHTTP.swift.
 * It keeps akou's idempotency rule (src/main/api/routes/jobs.ts): a repeated `Idempotency-Key`
 * with the same options answers 200 with the first job, with other options 422
 * `idempotency_conflict`; `keep_audio` and `metadata` are not compared. Each answer carries the
 * fields of akou's `jobView`.
 */
class FakeAkouHttp : Dispatcher(), Closeable {
    /** What the next submit does, in order; when the list is empty a submit is answered normally. */
    sealed interface Script {
        /** Make the job, then drop the connection before the answer: the client never hears of it. */
        data object AcceptThenDrop : Script

        /** 429 `queue_full` with `retry_after_s` in the body, and `Retry-After` unless [header] is false. */
        data class QueueFull(val retryAfter: Int, val header: Boolean = true) : Script

        /** Drop the connection without making a job. */
        data object Drop : Script
    }

    data class Seen(
        val method: String,
        val host: String,
        /** The path, decoded. */
        val path: String,
        /** The path as sent, still percent-encoded. */
        val rawPath: String,
        val query: String?,
        /** Header names in lower case. */
        val headers: Map<String, String>,
        val fields: Map<String, String>,
        val fileName: String?,
        val fileBytes: ByteArray?,
        /** The file part's own Content-Type. */
        val fileContentType: String? = null,
    )

    data class StoredJob(
        val id: String,
        val key: String,
        val options: Map<String, String>,
        val keepAudio: Boolean,
        val metadata: String?,
        val title: String?,
    )

    private val lock = Any()
    private val scripts = ArrayDeque<Script>()
    private val _seen = mutableListOf<Seen>()
    private val _jobs = mutableListOf<StoredJob>()
    private var ignoreKeepAudio = false

    val server = MockWebServer().also {
        it.dispatcher = this
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** `http://127.0.0.1:<port>`: a loopback address, which Endpoint lets through over plain http. */
    val baseUrl: String get() = "http://127.0.0.1:${server.port}"

    fun script(vararg s: Script) = synchronized(lock) { scripts.addAll(s) }

    /** Simulates a server that does not keep uploads: every job answers `keep_audio: false`. */
    fun ignoreKeepAudio() = synchronized(lock) { ignoreKeepAudio = true }

    val seen: List<Seen> get() = synchronized(lock) { _seen.toList() }
    val jobs: List<StoredJob> get() = synchronized(lock) { _jobs.toList() }

    override fun close() = server.close()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val method = request.method
        val headers = request.headers.names().associate { it.lowercase() to request.headers[it]!! }
        val body = request.body?.toByteArray() ?: ByteArray(0)
        val raw = request.target.substringBefore('?')
        var seen = Seen(method, url.host, url.pathSegments.joinToString("/", prefix = "/"), raw, url.query, headers, emptyMap(), null, null)
        val type = headers["content-type"]
        if (type != null && type.startsWith("multipart/form-data")) {
            val fields = mutableMapOf<String, String>()
            for (p in parts(body, type.substringAfterLast("boundary="))) {
                if (p.fileName != null) seen = seen.copy(fileName = p.fileName, fileBytes = p.value, fileContentType = p.contentType)
                else fields[p.name] = p.value.decodeToString()
            }
            seen = seen.copy(fields = fields)
        }
        synchronized(lock) { _seen += seen }

        if (headers["authorization"] != "Bearer $KEY") {
            return answer(401, """{"error":"unauthorized","message":"a valid bearer token is required"}""")
        }
        // akou's guard (src/main/api/guard.ts, bodyRule): every method but GET and HEAD needs a
        // Content-Type, multipart for the upload route and JSON for the rest, body or not.
        if (method != "GET" && method != "HEAD") {
            val t = (type ?: "").substringBefore(';').trim().lowercase()
            val upload = method == "POST" && seen.path == "/v1/jobs"
            if (upload && t != "multipart/form-data") {
                return answer(415, """{"error":"multipart_required","message":"an upload needs Content-Type: multipart/form-data"}""")
            }
            if (!upload && t != "application/json") {
                return answer(415, """{"error":"json_required","message":"requests that change something need Content-Type: application/json"}""")
            }
        }
        val p = seen.path
        return when {
            method == "POST" && p == "/v1/jobs" -> submit(seen)
            method == "DELETE" && p.startsWith("/v1/jobs/") -> {
                val id = p.removePrefix("/v1/jobs/")
                val removed = synchronized(lock) { _jobs.removeIf { it.id == id } }
                if (removed) answer(200, """{"deleted":true}""") else answer(404, """{"error":"not_found","message":"no such job"}""")
            }
            method == "GET" && p.startsWith("/v1/jobs/") -> {
                val id = p.removePrefix("/v1/jobs/")
                val job = synchronized(lock) { _jobs.firstOrNull { it.id == id } }
                if (job == null) answer(404, """{"error":"not_found","message":"no such job"}""") else answer(200, view(job))
            }
            else -> answer(404, """{"error":"not_found","message":"no such route"}""")
        }
    }

    private fun submit(seen: Seen): MockResponse {
        val script = synchronized(lock) { scripts.removeFirstOrNull() }
        if (script is Script.QueueFull) {
            val extra = if (script.header) mapOf("Retry-After" to "${script.retryAfter}") else emptyMap()
            return answer(429, """{"error":"queue_full","message":"the queue is full","retry_after_s":${script.retryAfter}}""", extra)
        }
        if (script == Script.Drop) return drop()
        val file = seen.fileBytes ?: return answer(400, """{"error":"bad_field","message":"file is required"}""")
        // The options a repeated key is compared on: the file and the decoding options.
        val options = seen.fields.filterKeys { it in setOf("preset", "model", "language", "diarize") } +
            ("file" to file.contentHashCode().toString())
        val idem = seen.headers["idempotency-key"]
        val (status, job) = synchronized(lock) {
            val existing = idem?.let { k -> _jobs.firstOrNull { it.key == k } }
            if (existing != null) {
                (if (existing.options == options) 200 else 422) to existing
            } else {
                val j = StoredJob(
                    id = "j${_jobs.size + 1}", key = idem ?: UUID.randomUUID().toString(), options = options,
                    keepAudio = !ignoreKeepAudio && seen.fields["keep_audio"] == "true",
                    metadata = seen.fields["metadata"], title = seen.fields["title"],
                )
                _jobs += j
                202 to j
            }
        }
        if (status == 422) {
            return answer(422, """{"error":"idempotency_conflict","message":"this Idempotency-Key was used for a request with another file","id":"${job.id}","fields":["file"]}""")
        }
        if (script == Script.AcceptThenDrop) return drop()
        return answer(status, view(job))
    }

    private fun view(j: StoredJob): String {
        val title = j.title?.let { "\"$it\"" } ?: "null"
        val audio = if (j.keepAudio) ""","audio":"/v1/jobs/${j.id}/audio"""" else ""
        return """{"id":"${j.id}","title":$title,"status":"queued","key_id":"k1","created_at":"2026-10-07T10:00:00.000Z","started_at":null,"finished_at":null,"preset":"fast","model":"parakeet-tdt-0.6b-v3","model_source":"preset","priority":0,"interactive":false,"keep_audio":${j.keepAudio},"language":"auto","languages":[],"diarize":false,"metadata":${j.metadata ?: "null"},"links":{"self":"/v1/jobs/${j.id}","result":"/v1/jobs/${j.id}/result","events":"/v1/events"$audio}}"""
    }

    private fun answer(status: Int, body: String, extra: Map<String, String> = emptyMap()): MockResponse {
        val b = MockResponse.Builder().code(status)
            .setHeader("Content-Type", "application/json")
            .setHeader("Cache-Control", "no-store")
        extra.forEach { (k, v) -> b.setHeader(k, v) }
        return b.body(body).build()
    }

    private fun drop(): MockResponse = MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()

    data class Part(val name: String, val fileName: String?, val value: ByteArray, val contentType: String? = null)

    companion object {
        const val KEY = "ak_test"

        /** A small multipart reader: enough for the bodies [Multipart] makes. */
        fun parts(body: ByteArray, boundary: String): List<Part> {
            val delimiter = "--$boundary".encodeToByteArray()
            val chunks = mutableListOf<ByteArray>()
            var start = 0
            while (true) {
                val at = indexOf(body, delimiter, start)
                if (at < 0) break
                chunks += body.copyOfRange(start, at)
                start = at + delimiter.size
            }
            val out = mutableListOf<Part>()
            for (chunk in chunks.drop(1)) {
                val sep = indexOf(chunk, "\r\n\r\n".encodeToByteArray(), 0)
                if (sep < 0) continue
                val head = chunk.copyOfRange(0, sep).decodeToString()
                var value = chunk.copyOfRange(sep + 4, chunk.size)
                if (value.size >= 2 && value[value.size - 2] == '\r'.code.toByte() && value.last() == '\n'.code.toByte()) {
                    value = value.copyOfRange(0, value.size - 2)
                }
                fun param(n: String): String? = Regex("""(?:^|[ ;])$n="([^"]*)"""").find(head)?.groupValues?.get(1)
                val name = param("name") ?: continue
                val contentType = head.lineSequence().firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()
                out += Part(name, param("filename"), value, contentType)
            }
            return out
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
