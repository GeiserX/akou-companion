// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * What the upload worker does, kept free of WorkManager so it runs on the JVM: claim the due
 * recordings, send each one's `POST /v1/jobs` (JobsClient's submit request, with its
 * Idempotency-Key), then read back each submitted job (`GET /v1/jobs/{id}`), and report every
 * answer to the queue, which decides what comes next: a wait, a park, or the deletion of the
 * phone's copy once the server keeps the audio.
 */
class UploadPass(
    private val queue: UploadQueue,
    private val transport: UploadQueue.Transport = UploadQueue.OkHttpTransport(),
    /** One line per request sent, with the Idempotency-Key it carried, so a run on a phone shows each attempt. */
    private val log: (String) -> Unit = {},
) {
    /**
     * One pass over the queue, or over the one recording [only] names. [clients] holds a client for
     * each server that has a URL and a key; [servers] is every server the settings know, and
     * [recordTo] takes the items that name none.
     */
    suspend fun run(servers: Set<UUID>, recordTo: UUID?, clients: Map<UUID, JobsClient>, only: String? = null) {
        val routing = UploadQueue.Routing(servers, recordTo, ready = clients.keys)
        val claimed = ArrayDeque(queue.claimDue(routing, only))
        try {
            while (claimed.isNotEmpty()) {
                val item = claimed.first()
                val audio = queue.audioFile(item)
                val client = item.serverId?.let(clients::get)
                if (client == null || !audio.isFile) {
                    queue.uploadCouldNotStart(item.recordingId, if (client == null) "no server for this recording" else "the recording's file is missing")
                } else {
                    val answer = send {
                        client.submitRequest(item.submission, audio).also { log("POST ${it.url.encodedPath} Idempotency-Key ${it.header("Idempotency-Key")}") }
                    }
                    log("POST answer for ${item.recordingId}: ${describe(answer)}")
                    queue.uploadEnded(item.recordingId, answer)
                }
                claimed.removeFirst()
            }
        } catch (e: CancellationException) {
            // WorkManager stopped the worker mid-pass: every claimed item not yet reported goes back to
            // pending; the Idempotency-Key makes a resend safe.
            for (item in claimed) queue.uploadEnded(item.recordingId, UploadQueue.Answer.Unreachable(STOPPED))
            throw e
        }
        // The read-back needs no wait: the server sets keep_audio when it makes the job. A stopped
        // read-back leaves the item submitted and due, so the next run reads it again.
        for (due in queue.dueForConfirm(routing, only)) {
            val client = due.serverId?.let(clients::get) ?: continue
            val answer = send { client.jobRequest(due.jobId) }
            log("read-back of ${due.jobId} for ${due.recordingId}: ${describe(answer)}")
            queue.confirmEnded(due.recordingId, answer)
        }
    }

    /** Never throws but for cancellation: a request that cannot be made or sent is no answer, tried again later. */
    private suspend fun send(request: () -> okhttp3.Request): UploadQueue.Answer = try {
        transport.send(request())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UploadQueue.Answer.Unreachable(e.message ?: e.javaClass.simpleName)
    }

    /** The status, never the body: a job's body can hold transcript text. */
    private fun describe(a: UploadQueue.Answer): String = when (a) {
        is UploadQueue.Answer.Http -> "HTTP ${a.status}"
        is UploadQueue.Answer.Unreachable -> "unreachable (${a.why})"
    }

    private companion object {
        const val STOPPED = "the upload was stopped"
    }
}
