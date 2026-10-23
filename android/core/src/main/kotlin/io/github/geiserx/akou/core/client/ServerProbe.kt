// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.KeyInfo
import io.github.geiserx.akou.core.protocol.ServerInfo
import io.github.geiserx.akou.core.protocol.stringOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** The settings screen's test: is this an akou server, does the key work, and can it show live text. */
class ServerProbe(
    val baseUrl: String,
    private val key: String,
    private val client: OkHttpClient = AkouHttp.shared,
) {
    sealed class Failure(message: String) : Exception(message) {
        /** An answer other than 2xx, with akou's error `code` when the body carried one. */
        data class Status(val status: Int, val code: String?) : Failure("HTTP $status ${code ?: ""}".trim())

        /** A 2xx answer that is not akou's JSON. */
        data object NotAkou : Failure("not an akou server")
    }

    /** `GET /v1/server`. */
    suspend fun server(): ServerInfo {
        val info = get("/v1/server", ServerInfo.serializer())
        if (info.name != "akou") throw Failure.NotAkou
        return info
    }

    /** `GET /v1/keys/me`: the key's id and scopes. A revoked or mistyped key answers 401. */
    suspend fun keyInfo(): KeyInfo = get("/v1/keys/me", KeyInfo.serializer())

    internal fun request(path: String): Request =
        Request.Builder().url(Endpoint.api(baseUrl, path))
            .header(AkouHttp.AUTHORIZATION, AkouHttp.bearer(key))
            .header("Accept", "application/json")
            .tag(ReadTimeout::class, ReadTimeout(AkouHttp.READ_TIMEOUT_S))
            .build()

    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>): T {
        val answer = client.fetch(request(path))
        val text = answer.body.decodeToString()
        if (answer.status !in 200..299) {
            val code = runCatching { AkouJson.decodeFromString(JsonObject.serializer(), text).stringOrNull("error") }.getOrNull()
            throw Failure.Status(answer.status, code)
        }
        return try {
            AkouJson.decodeFromString(strategy, text)
        } catch (_: IllegalArgumentException) {
            throw Failure.NotAkou
        }
    }
}
