// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The parts of akou's `GET /v1/server` answer the phone reads. Every other field is ignored, so a
 * newer server never breaks an older app.
 */
@Serializable
data class ServerInfo(
    val name: String,
    val version: String? = null,
    /**
     * `server` on a server, `app` on a desktop app (reachable by key when its network listener is
     * on). Calls and dictations exist only in `app` mode.
     */
    val mode: String? = null,
    val capabilities: Capabilities,
    /**
     * The live door in server mode: the streaming models on disk a `hello` may name. Null in the
     * desktop app and absent on servers older than the route.
     */
    val live: Live? = null,
    /**
     * `server.retain_days`: the days a job and its result stay, counted from its creation. A job
     * sent with `keep_audio=true` is exempt. Absent on older servers.
     */
    @SerialName("retain_days") val retainDays: Int? = null,
) {
    @Serializable
    data class Live(val engines: List<String>)

    @Serializable
    data class Capabilities(
        val jobs: Boolean? = null,
        /** True when `GET /v1/live` exists and a live engine is on disk. Absent on servers older than the route. */
        val live: Boolean? = null,
    )

    /** Whether this server can show live text. */
    val supportsLive: Boolean get() = capabilities.live == true

    /** The streaming models a phone may pin as its live model; empty when the server lists none. */
    val liveEngines: List<String> get() = live?.engines ?: emptyList()
}

/** akou's `GET /v1/keys/me` answer. */
@Serializable
data class KeyInfo(
    val id: String,
    val name: String? = null,
    val scopes: List<String>,
) {
    /** The key may upload recordings: `jobs` or `admin`. */
    val canUpload: Boolean get() = "jobs" in scopes || "admin" in scopes

    /** The key may read the server's history (every call, dictation and job): `read` or `admin`. */
    val seesHistory: Boolean get() = "read" in scopes || "admin" in scopes
}
