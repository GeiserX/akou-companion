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
        val claimed = ArrayDeque(queue.claimDue(routing))
        try {
            while (claimed.isNotEmpty()) {
                val item = claimed.first()
                val audio = queue.audioFile(item)
                val client = item.serverId?.let(clients::get)
                if (client == null || !audio.isFile) {
                    queue.uploadCouldNotStart(item.recordingId, if (client == null) "no server for this recording" else "the recording's file is missing")
                } else {
                    val answer = try {
                        transport.send(client.submitRequest(item.submission, audio))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // No request could be made or sent (a URL Endpoint refuses, a transport fault): try again later.
                        UploadQueue.Answer.Unreachable(e.message ?: e.javaClass.simpleName)
                    }
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
    }

    private companion object {
        const val STOPPED = "the upload was stopped"
    }
}
