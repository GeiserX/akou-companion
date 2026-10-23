// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.upload

import io.github.geiserx.akou.core.client.JobsClient
import io.github.geiserx.akou.core.client.UploadQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The worker's pass: what it sends, and what it reports to the queue for each answer. */
class UploadPassTests {
    @get:Rule val tmp = TemporaryFolder()

    private val server = UUID.randomUUID()
    private val sent = mutableListOf<Request>()

    private fun queue(): UploadQueue {
        val dir = File(tmp.root, "Recordings").apply { mkdirs() }
        return UploadQueue(UploadQueue.FileStore(File(tmp.root, "queue.json")), dir, keepLocalCopy = { true })
    }

    private fun UploadQueue.add(id: String, withFile: Boolean = true, serverId: UUID? = server) {
        if (withFile) File(audioDirectory, "$id.opus").writeBytes(byteArrayOf(1, 2, 3))
        enqueue(JobsClient.Submission(recordingId = id, language = "auto"), "$id.opus", serverId)
    }

    private fun answering(status: Int, body: String, headers: Headers = Headers.headersOf()) = UploadQueue.Transport { r ->
        sent += r
        UploadQueue.Answer.Http(status, headers, body.encodeToByteArray())
    }

    private val clients get() = mapOf(server to JobsClient("https://akou.example.com", "ak_test"))

    @Test
    fun a202IsReportedAsSubmittedWithTheRecordingIdAsIdempotencyKey() = runTest {
        val q = queue().apply { add("rec-1") }
        UploadPass(q, answering(202, """{"id":"job-9","status":"queued","keep_audio":true}""")).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Submitted("job-9"), q.item("rec-1")!!.state)
        val r = sent.single()
        assertEquals("/v1/jobs", r.url.encodedPath)
        assertEquals("rec-1", r.header("Idempotency-Key"))
        assertEquals("Bearer ak_test", r.header("Authorization"))
    }

    @Test
    fun aServerErrorGoesBackToPendingWithItsWait() = runTest {
        val q = queue().apply { add("rec-2") }
        UploadPass(q, answering(503, """{"error":"busy"}""", Headers.headersOf("Retry-After", "30"))).run(setOf(server), server, clients)
        val item = q.item("rec-2")!!
        assertEquals(UploadQueue.State.Pending, item.state)
        assertEquals(1, item.attempts)
        assertTrue(item.lastError!!.contains("503"))
    }

    @Test
    fun aRefusedKeyParksTheRecording() = runTest {
        val q = queue().apply { add("rec-3") }
        UploadPass(q, answering(401, """{"error":"unauthorized"}""")).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Parked(401, "unauthorized"), q.item("rec-3")!!.state)
    }

    @Test
    fun aMissingFileIsParkedAndNothingIsSent() = runTest {
        val q = queue().apply { add("rec-4", withFile = false) }
        UploadPass(q, answering(202, "{}")).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Parked(null, UploadQueue.LOCAL_FILE_CODE), q.item("rec-4")!!.state)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aServerWithoutAKeyLeavesTheRecordingWaiting() = runTest {
        val q = queue().apply { add("rec-5") }
        UploadPass(q, answering(202, "{}")).run(setOf(server), server, emptyMap())
        assertEquals(UploadQueue.State.Pending, q.item("rec-5")!!.state)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun aRecordingWithNoServerGoesToRecordTo() = runTest {
        val q = queue().apply { add("rec-6", serverId = null) }
        UploadPass(q, answering(202, """{"id":"job-6","status":"queued"}""")).run(setOf(server), server, clients)
        assertEquals(server, q.item("rec-6")!!.serverId)
        assertEquals(UploadQueue.State.Submitted("job-6"), q.item("rec-6")!!.state)
    }

    @Test
    fun aStoppedWorkerPutsTheUploadBackToPending() = runTest {
        val q = queue().apply { add("rec-7") }
        val stopping = UploadQueue.Transport { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { UploadPass(q, stopping).run(setOf(server), server, clients) }
        val item = q.item("rec-7")!!
        assertEquals(UploadQueue.State.Pending, item.state)
        assertEquals("the upload was stopped", item.lastError)
    }
}
