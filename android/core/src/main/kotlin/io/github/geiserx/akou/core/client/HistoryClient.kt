// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouErrorBody
import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.CallDetail
import io.github.geiserx.akou.core.protocol.CallEnhanced
import io.github.geiserx.akou.core.protocol.CallList
import io.github.geiserx.akou.core.protocol.CallNote
import io.github.geiserx.akou.core.protocol.CallNotes
import io.github.geiserx.akou.core.protocol.CallSummary
import io.github.geiserx.akou.core.protocol.CallTranscript
import io.github.geiserx.akou.core.protocol.Dictation
import io.github.geiserx.akou.core.protocol.DictationPage
import io.github.geiserx.akou.core.protocol.JobPage
import kotlinx.serialization.DeserializationStrategy
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The reads of an akou's history: its calls (with transcript, summary, notes and audio), its
 * dictations, and every job. They need a key with the `read` or `admin` scope; calls and
 * dictations exist only on a desktop akou (`mode` `app` in `GET /v1/server`).
 */
class HistoryClient(
    val baseUrl: String,
    private val key: String,
    private val client: OkHttpClient = AkouHttp.shared,
) {
    sealed class Failure(message: String?) : Exception(message) {
        /** 401 or 403 on a history route: this key cannot see history. */
        data class CannotSeeHistory(val status: Int) : Failure("HTTP $status: this key cannot see history")

        /** Any other answer outside 2xx, with akou's error code and message when the body carried them. */
        data class Status(val status: Int, val code: String?, override val message: String?) : Failure(message)

        /** A 2xx answer that is not akou's JSON. */
        data object NotAkou : Failure("not an akou server")
    }

    // Calls

    /** `GET /v1/calls`: the calls on disk, newest first, metadata only. */
    suspend fun calls(limit: Int = 200): List<CallSummary> =
        get("/v1/calls", CallList.serializer(), listOf("limit" to "$limit")).calls

    /** `GET /v1/calls/{id}`. */
    suspend fun call(id: String): CallDetail = get("/v1/calls/${Endpoint.segment(id)}", CallDetail.serializer())

    /**
     * `GET /v1/calls/{id}/transcript`: every line, the final pass where it ran and the live lines
     * elsewhere (`layer=best`), without waiting for a second pass (`review=skip`).
     */
    suspend fun transcript(id: String): CallTranscript = get(
        "/v1/calls/${Endpoint.segment(id)}/transcript", CallTranscript.serializer(),
        listOf("layer" to "best", "format" to "json", "review" to "skip"),
    )

    /** `GET /v1/calls/{id}/enhanced`: the latest summary, or null when the call has none. */
    suspend fun summary(id: String): CallEnhanced.Notes? =
        get("/v1/calls/${Endpoint.segment(id)}/enhanced", CallEnhanced.serializer()).enhanced

    /** `GET /v1/calls/{id}/notes`: the notepad lines. */
    suspend fun notes(id: String): List<CallNote> = get("/v1/calls/${Endpoint.segment(id)}/notes", CallNotes.serializer()).notes

    // Dictations

    /** `GET /v1/dictations`: newest first; pass the page's `nextCursor` back for the next one. */
    suspend fun dictations(cursor: String? = null, limit: Int = 50): DictationPage =
        get("/v1/dictations", DictationPage.serializer(), listOfNotNull("limit" to "$limit", cursor?.let { "cursor" to it }))

    /** `GET /v1/dictations/{id}`. */
    suspend fun dictation(id: String): Dictation = get("/v1/dictations/${Endpoint.segment(id)}", Dictation.serializer())

    // Jobs

    /** `GET /v1/jobs`: newest first; a `read` or `admin` key sees every key's jobs. */
    suspend fun jobs(cursor: Int? = null, limit: Int = 50): JobPage =
        get("/v1/jobs", JobPage.serializer(), listOfNotNull("limit" to "$limit", cursor?.let { "cursor" to "$it" }))

    // Audio

    /** A plain `GET` for an audio path on this server, the key in the `Authorization` header and never in the URL. */
    fun audioRequest(path: String): Request = request(path, emptyList(), accept = "audio/*")

    // Plumbing

    private fun request(path: String, query: List<Pair<String, String>>, accept: String): Request {
        val url = Endpoint.api(baseUrl, path).newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return Request.Builder().url(url)
            .header(AkouHttp.AUTHORIZATION, AkouHttp.bearer(key))
            .header("Accept", accept)
            .tag(ReadTimeout::class, ReadTimeout(AkouHttp.READ_TIMEOUT_S))
            .build()
    }

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, query: List<Pair<String, String>> = emptyList()): T {
        val a = client.fetch(request(path, query, accept = "application/json"))
        val text = a.body.decodeToString()
        if (a.status !in 200..299) {
            if (a.status == 401 || a.status == 403) throw Failure.CannotSeeHistory(a.status)
            val parsed = runCatching { AkouJson.decodeFromString(AkouErrorBody.serializer(), text) }.getOrNull()
            throw Failure.Status(a.status, parsed?.error, parsed?.message)
        }
        return try {
            AkouJson.decodeFromString(strategy, text)
        } catch (_: IllegalArgumentException) {
            throw Failure.NotAkou
        }
    }

    companion object {
        /** The path of one part's audio, Ogg Opus, served with `Range`. */
        fun callAudioPath(id: String, part: Int): String = "/v1/calls/${Endpoint.segment(id)}/audio/$part"

        /**
         * The path of a dictation's audio: Ogg Opus (a WAV for one kept before akou kept Opus), served
         * whole, without `Range`; 404 `no_audio` when none was kept.
         */
        fun dictationAudioPath(id: String): String = "/v1/dictations/${Endpoint.segment(id)}/audio"
    }
}
