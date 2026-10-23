// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A job as akou's job routes answer it (`POST /v1/jobs`, `GET /v1/jobs/{id}`, each row of
 * `GET /v1/jobs`). Only the fields the phone reads; every other field is ignored, so a newer
 * server never breaks an older app.
 */
@Serializable
data class Job(
    val id: String,
    val title: String? = null,
    /** `queued`, `running`, `done`, `failed` or `cancelled`; kept as text so a new state never fails decoding. */
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val preset: String? = null,
    val model: String? = null,
    val language: String? = null,
    /**
     * Whether the server keeps the uploaded audio until the job is deleted. A server that does not
     * send it is read as false, so the phone never deletes its copy on a guess.
     */
    @SerialName("keep_audio") val keepAudio: Boolean = false,
    /** The companion's own labels, when the job carries them; null for a job another client sent, whatever its shape. */
    @Serializable(with = LenientCompanionMetadata::class) val metadata: CompanionMetadata? = null,
    @Serializable(with = LenientString::class) val error: String? = null,
) {
    /** True once the job will not change any more. */
    val ended: Boolean get() = status == "done" || status == "failed" || status == "cancelled"
}

/**
 * The `metadata` the phone sends with each upload. Workspaces are a desktop-only route, so on a
 * server the workspace is a label the phone keeps here.
 */
@Serializable
data class CompanionMetadata(
    /** The recording's id, the same value as the upload's `Idempotency-Key`. */
    @SerialName("recording_id") val recordingId: String,
    val workspace: String?,
    /**
     * Always 1: marks a job the companion sent. Required on decode, as in Swift: metadata without it
     * is another client's.
     */
    @Required val companion: Int = 1,
)

/** `GET /v1/jobs/{id}/result?format=json`. */
@Serializable
data class JobResult(
    val text: String = "",
    val segments: List<JobSegment> = emptyList(),
    /** Every word in order, with times and confidences when the engine gives them. */
    val words: List<JobWord> = emptyList(),
    val language: String? = null,
    @SerialName("duration_s") val durationS: Double? = null,
)

@Serializable
data class JobSegment(
    /** Seconds into the file. */
    val s: Double,
    val e: Double,
    val text: String,
    val speaker: String? = null,
)

/**
 * One word: `s` and `e` are seconds into the file and `c` the confidence from 0 to 1; each is
 * null from an engine that gives none (Qwen gives no word times).
 */
@Serializable
data class JobWord(
    val w: String,
    val s: Double? = null,
    val e: Double? = null,
    val c: Double? = null,
)

/** `GET /v1/jobs`: newest first; `cursor` is null on the last page. */
@Serializable
data class JobPage(val jobs: List<Job>, val cursor: Int? = null)

/** akou refuses with `{"error": "<code>", "message": "...", ...}`; the extra fields depend on the code. */
@Serializable
data class AkouErrorBody(
    val error: String,
    val message: String? = null,
    /** `idempotency_conflict`: the job the key already names, and the fields that differ. */
    val id: String? = null,
    val fields: List<String>? = null,
    /** `queue_full`: the same seconds as the `Retry-After` header. */
    @SerialName("retry_after_s") val retryAfterS: Int? = null,
)
