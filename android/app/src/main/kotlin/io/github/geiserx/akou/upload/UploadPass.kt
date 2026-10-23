// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * What the upload worker does, kept free of WorkManager so it runs on the JVM: claim the due
 * recordings, send each one's `POST /v1/jobs` (JobsClient's submit request, with its
 * Idempotency-Key), and report every answer to the queue, which decides what comes next.
 *
 * A1 makes one pass per recording and retries nothing; A2 drives the queue's own backoff.
 */
class UploadPass(
    private val queue: UploadQueue,
    private val transport: UploadQueue.Transport = UploadQueue.OkHttpTransport(),
) {
    /**
     * One pass over the queue. [clients] holds a client for each server that has a URL and a key;
     * [servers] is every server the settings know, and [recordTo] takes the items that name none.
     */
    suspend fun run(servers: Set<UUID>, recordTo: UUID?, clients: Map<UUID, JobsClient>) {
        val routing = UploadQueue.Routing(servers, recordTo, ready = clients.keys)
        for (item in queue.claimDue(routing)) {
            val audio = queue.audioFile(item)
            val client = item.serverId?.let(clients::get)
            if (client == null || !audio.isFile) {
                queue.uploadCouldNotStart(item.recordingId, if (client == null) "no server for this recording" else "the recording's file is missing")
                continue
            }
            val answer = try {
                transport.send(client.submitRequest(item.submission, audio))
            } catch (e: CancellationException) {
                // WorkManager stopped the worker mid-upload: back to pending, the Idempotency-Key makes a resend safe.
                queue.uploadEnded(item.recordingId, UploadQueue.Answer.Unreachable("the upload was stopped"))
                throw e
            }
            queue.uploadEnded(item.recordingId, answer)
        }
    }
}
