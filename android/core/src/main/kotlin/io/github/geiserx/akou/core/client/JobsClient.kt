// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouErrorBody
import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.CompanionMetadata
import io.github.geiserx.akou.core.protocol.Job
import io.github.geiserx.akou.core.protocol.JobPage
import io.github.geiserx.akou.core.protocol.JobResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Every file-job call the phone makes to akou: the upload of a recording, its state and
 * transcript, the list, rename, delete and the kept audio.
 */
class JobsClient(
    val baseUrl: String,
    private val key: String,
    private val client: OkHttpClient = AkouHttp.shared,
) {
    /**
     * What one recording is uploaded with. Persisted in the upload queue, so a retry after an app
     * restart sends exactly the same options and gets the first job back.
     */
    @Serializable
    data class Submission(
        /** The recording's id: the `Idempotency-Key` and `metadata.recording_id`. */
        val recordingId: String,
        val title: String? = null,
        /** `auto` or a BCP 47 code; null leaves it to the server. */
        val language: String? = null,
        /** A recognizer id; null leaves it to `preset` and the server. */
        val model: String? = null,
        /** `lite`, `fast`, `best`, `fusion` or `auto`; null leaves it to the server. */
        val preset: String? = null,
        val workspace: String? = null,
        /** Always true from the app: the phone deletes its copy only when the server keeps one. */
        val keepAudio: Boolean = true,
    ) {
        val metadata: CompanionMetadata get() = CompanionMetadata(recordingId = recordingId, workspace = workspace)
    }

    sealed class Failure(message: String) : Exception(message) {
        /** An answer other than 2xx, with akou's error body when it sent one and `Retry-After` in seconds. */
        data class Status(val status: Int, val body: AkouErrorBody?, val retryAfter: Double?) :
            Failure("HTTP $status ${body?.error ?: ""}".trim())

        /** A 2xx answer that is not akou's JSON. */
        data object NotAkou : Failure("not an akou answer")
    }

    /** A submit's answer: the job, and whether it already existed (200 for a repeated key) or is new (202). */
    data class Submitted(val job: Job, val existing: Boolean)

    // Submit

    /** The request of `POST /v1/jobs`: the multipart body streams [audio] from disk. */
    fun submitRequest(s: Submission, audio: File): Request {
        val fields = buildList {
            s.title?.takeIf { it.isNotEmpty() }?.let { add("title" to it) }
            s.language?.takeIf { it.isNotEmpty() }?.let { add("language" to it) }
            s.model?.takeIf { it.isNotEmpty() }?.let { add("model" to it) }
            s.preset?.takeIf { it.isNotEmpty() }?.let { add("preset" to it) }
            add("keep_audio" to if (s.keepAudio) "true" else "false")
            add("metadata" to AkouJson.encodeToString(CompanionMetadata.serializer(), s.metadata))
        }
        val body = Multipart(fields, Multipart.FilePart("file", "${s.recordingId}.opus", "audio/ogg", audio))
        return Request.Builder().url(Endpoint.api(baseUrl, "/v1/jobs"))
            .post(body)
            .header(AkouHttp.AUTHORIZATION, AkouHttp.bearer(key))
            .header("Accept", "application/json")
            .header("Idempotency-Key", s.recordingId)
            .tag(ReadTimeout::class, ReadTimeout(AkouHttp.JOB_TIMEOUT_S))
            .build()
    }

    /** Uploads a recording and reads the answer. */
    suspend fun submit(s: Submission, audio: File): Submitted {
        val a = client.fetch(submitRequest(s, audio))
        return submitted(a.status, a.headers, a.body)
    }

    // Reading and changing jobs

    /** `GET /v1/jobs/{id}?wait=`: `wait` (0 to 60 seconds) holds the answer until the job ends. */
    suspend fun job(id: String, wait: Int = 0): Job = send(jobRequest(id, wait), Job.serializer())

    /**
     * The request behind [job], for a caller with its own transport. Its read timeout is
     * wait + 30 s, never under the 60 s of the other job routes.
     */
    fun jobRequest(id: String, wait: Int = 0): Request {
        val w = wait.coerceIn(0, 60)
        return request("GET", "/v1/jobs/${Endpoint.segment(id)}", if (w > 0) listOf("wait" to "$w") else emptyList())
            .newBuilder().tag(ReadTimeout::class, ReadTimeout(maxOf(AkouHttp.JOB_TIMEOUT_S, w + 30L))).build()
    }

    /** `GET /v1/jobs/{id}/result?format=json`: 409 `not_done` until the job is done. */
    suspend fun result(id: String): JobResult =
        send(request("GET", "/v1/jobs/${Endpoint.segment(id)}/result", listOf("format" to "json")), JobResult.serializer())

    /** `GET /v1/jobs`: the key's jobs, newest first. Pass the page's `cursor` back for the next one. */
    suspend fun list(status: String? = null, query: String? = null, cursor: Int? = null, limit: Int? = null): JobPage {
        val q = buildList {
            status?.let { add("status" to it) }
            query?.let { add("q" to it) }
            cursor?.let { add("cursor" to "$it") }
            limit?.let { add("limit" to "$it") }
        }
        return send(request("GET", "/v1/jobs", q), JobPage.serializer())
    }

    /** `PATCH /v1/jobs/{id}` `{title}`. */
    suspend fun rename(id: String, title: String): Job {
        val json = AkouJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), mapOf("title" to title))
        return send(request("PATCH", "/v1/jobs/${Endpoint.segment(id)}", body = jsonBody(json.encodeToByteArray())), Job.serializer())
    }

    /**
     * `DELETE /v1/jobs/{id}`: the job, its result and its kept audio. akou refuses every request but
     * GET and HEAD without `Content-Type: application/json` (415 `json_required`), even one with no body.
     */
    suspend fun delete(id: String) {
        val a = client.fetch(request("DELETE", "/v1/jobs/${Endpoint.segment(id)}", body = jsonBody(ByteArray(0))))
        check(a.status, a.headers, a.body)
    }

    /**
     * `GET /v1/jobs/{id}/audio` with an optional byte range. The server answers 409 `not_kept` for a
     * job sent without `keep_audio` and 410 `gone` when the kept file is no longer on disk.
     */
    fun audioRequest(id: String, range: LongRange? = null): Request {
        val req = audioRequestFor(audioPath(id))
        return if (range == null) req else req.newBuilder().header("Range", "bytes=${range.first}-${range.last}").build()
    }

    /** A plain `GET` for any audio path on this server, the key in the header and never in the URL. */
    fun audioRequestFor(path: String): Request = request("GET", path, accept = "audio/*")

    // Plumbing

    private fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        accept: String = "application/json",
        body: RequestBody? = null,
    ): Request {
        val url = Endpoint.api(baseUrl, path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url)
            .method(method, body)
            .header(AkouHttp.AUTHORIZATION, AkouHttp.bearer(key))
            .header("Accept", accept)
            .tag(ReadTimeout::class, ReadTimeout(AkouHttp.JOB_TIMEOUT_S))
            .build()
    }

    /** Exactly `application/json`, with no charset parameter, as iOS sends it. */
    private fun jsonBody(bytes: ByteArray): RequestBody = bytes.toRequestBody(JSON)

    private suspend fun <T> send(req: Request, strategy: DeserializationStrategy<T>): T {
        val a = client.fetch(req)
        check(a.status, a.headers, a.body)
        return decode(strategy, a.body)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** The path of a job's kept audio. */
        fun audioPath(id: String): String = "/v1/jobs/${Endpoint.segment(id)}/audio"

        /** Reads a submit's answer, wherever it came from (this client or a background worker). */
        fun submitted(status: Int, headers: Headers, body: ByteArray): Submitted {
            check(status, headers, body)
            return Submitted(decode(Job.serializer(), body), existing = status == 200)
        }

        internal fun check(status: Int, headers: Headers, body: ByteArray) {
            if (status in 200..299) return
            val parsed = runCatching { AkouJson.decodeFromString(AkouErrorBody.serializer(), body.decodeToString()) }.getOrNull()
            throw Failure.Status(status, parsed, retryAfter(headers) ?: parsed?.retryAfterS?.toDouble())
        }

        internal fun <T> decode(strategy: DeserializationStrategy<T>, body: ByteArray): T = try {
            AkouJson.decodeFromString(strategy, body.decodeToString())
        } catch (_: IllegalArgumentException) {
            throw Failure.NotAkou
        }

        /** `Retry-After` in seconds; akou sends seconds, never an HTTP date. */
        internal fun retryAfter(headers: Headers): Double? =
            headers.values("Retry-After").firstNotNullOfOrNull { v -> v.trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() } }
    }
}
