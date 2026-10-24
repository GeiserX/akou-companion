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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The worker's pass: what it sends, and what it reports to the queue for each answer. */
class UploadPassTests {
    @get:Rule val tmp = TemporaryFolder()

    private val server = UUID.randomUUID()
    private val sent = mutableListOf<Request>()

    private var keepCopy = true
    private var clock = Instant.ofEpochSecond(1_800_000_000)

    /** The queue as a process opens it: a second call is the same file after a restart. */
    private fun queue(): UploadQueue {
        val dir = File(tmp.root, "Recordings").apply { mkdirs() }
        return UploadQueue(UploadQueue.FileStore(File(tmp.root, "queue.json")), dir, now = { clock }, keepLocalCopy = { keepCopy })
    }

    private fun UploadQueue.add(id: String, withFile: Boolean = true, serverId: UUID? = server) {
        if (withFile) File(audioDirectory, "$id.opus").writeBytes(byteArrayOf(1, 2, 3))
        enqueue(JobsClient.Submission(recordingId = id, language = "auto"), "$id.opus", serverId)
    }

    private fun answering(status: Int, body: String, headers: Headers = Headers.headersOf()) = UploadQueue.Transport { r ->
        sent += r
        if (r.method == "GET") return@Transport UploadQueue.Answer.Unreachable("no read-back in this test")
        UploadQueue.Answer.Http(status, headers, body.encodeToByteArray())
    }

    private val clients get() = mapOf(server to JobsClient("https://akou.example.com", "ak_test"))

    @Test
    fun a202IsReportedAsSubmittedWithTheRecordingIdAsIdempotencyKey() = runTest {
        val q = queue().apply { add("rec-1") }
        UploadPass(q, answering(202, """{"id":"job-9","status":"queued","keep_audio":true}""")).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Submitted("job-9"), q.item("rec-1")!!.state)
        val r = sent.first { it.method == "POST" }
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

    @Test
    fun aStoppedWorkerPutsEveryClaimedUploadBackToPending() = runTest {
        val q = queue().apply { add("rec-8"); add("rec-9") }
        val stopping = UploadQueue.Transport { throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { UploadPass(q, stopping).run(setOf(server), server, clients) }
        assertEquals(listOf(UploadQueue.State.Pending, UploadQueue.State.Pending), q.items.map { it.state })
    }

    @Test
    fun aRequestThatCannotBeSentWaitsAndTheNextOneStillGoes() = runTest {
        val q = queue().apply { add("rec-10"); add("rec-11") }
        var n = 0
        val flaky = UploadQueue.Transport { r ->
            n += 1
            if (n == 1) throw IllegalStateException("no request")
            if (r.method == "GET") return@Transport UploadQueue.Answer.Unreachable("no read-back in this test")
            UploadQueue.Answer.Http(202, Headers.headersOf(), """{"id":"job-11","status":"queued"}""".encodeToByteArray()).also { sent += r }
        }
        UploadPass(q, flaky).run(setOf(server), server, clients)
        val first = q.item("rec-10")!!
        assertEquals(UploadQueue.State.Pending, first.state)
        assertEquals("no request", first.lastError)
        assertEquals(UploadQueue.State.Submitted("job-11"), q.item("rec-11")!!.state)
    }
    /** Answers the upload with [job] and the read-back with [readBack] (status and body), counting both. */
    private fun server(readBack: Pair<Int, String>, job: String = "job-1", keepAudio: Boolean = true) = UploadQueue.Transport { r ->
        sent += r
        if (r.method == "POST") {
            UploadQueue.Answer.Http(202, Headers.headersOf(), """{"id":"$job","status":"queued","keep_audio":$keepAudio}""".encodeToByteArray())
        } else {
            UploadQueue.Answer.Http(readBack.first, Headers.headersOf(), readBack.second.encodeToByteArray())
        }
    }

    private fun kept(job: String = "job-1", keepAudio: Boolean = true) = 200 to """{"id":"$job","status":"done","keep_audio":$keepAudio}"""

    private fun UploadQueue.file(id: String) = File(audioDirectory, "$id.opus")

    @Test
    fun anUploadMadeOfflineIsRetriedAfterARestartWithTheSameKeyAndReachesTheServerOnce() = runTest {
        val first = queue().apply { add("rec-off") }
        val offline = UploadQueue.Transport { r -> sent += r; UploadQueue.Answer.Unreachable("no network") }
        UploadPass(first, offline).run(setOf(server), server, clients)
        val waiting = first.item("rec-off")!!
        assertEquals(UploadQueue.State.Pending, waiting.state)
        assertEquals(clock.plusSeconds(5), waiting.notBefore)

        // The process dies; a new one opens the same file. Before the wait ends, nothing is sent.
        val second = queue()
        UploadPass(second, server(kept())).run(setOf(server), server, clients)
        assertEquals(1, sent.size)
        clock = clock.plusSeconds(5)
        UploadPass(second, server(kept())).run(setOf(server), server, clients)
        val posts = sent.filter { it.method == "POST" }
        assertEquals(listOf("rec-off", "rec-off"), posts.map { it.header("Idempotency-Key") })
        assertEquals(1, sent.count { it.method == "GET" })
        assertEquals(UploadQueue.State.Done("job-1", keptOnServer = true), second.item("rec-off")!!.state)

        // Done: another pass sends nothing more.
        UploadPass(queue(), server(kept())).run(setOf(server), server, clients)
        assertEquals(3, sent.size)
    }

    @Test
    fun theFileIsDeletedOnlyAfterTheReadBackShowsTheSameJobKeptAndTheSettingOff() = runTest {
        keepCopy = false
        val q = queue().apply { add("rec-del") }
        UploadPass(q, server(kept())).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Done("job-1", keptOnServer = true), q.item("rec-del")!!.state)
        assertFalse(q.file("rec-del").exists())
        assertEquals("/v1/jobs/job-1", sent.last().url.encodedPath)
    }

    @Test
    fun theFileStaysWhenTheSettingKeepsACopy() = runTest {
        keepCopy = true
        val q = queue().apply { add("rec-keep") }
        UploadPass(q, server(kept())).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Done("job-1", keptOnServer = true), q.item("rec-keep")!!.state)
        assertTrue(q.file("rec-keep").exists())
    }

    @Test
    fun theFileStaysWhenTheServerDoesNotKeepTheAudio() = runTest {
        keepCopy = false
        val q = queue().apply { add("rec-nokeep") }
        UploadPass(q, server(kept(keepAudio = false), keepAudio = false)).run(setOf(server), server, clients)
        assertEquals(UploadQueue.State.Done("job-1", keptOnServer = false), q.item("rec-nokeep")!!.state)
        assertTrue(q.file("rec-nokeep").exists())
    }

    @Test
    fun aReadBackOfAnotherJobKeepsTheFileAndWaits() = runTest {
        keepCopy = false
        val q = queue().apply { add("rec-other") }
        UploadPass(q, server(kept(job = "job-2"))).run(setOf(server), server, clients)
        val item = q.item("rec-other")!!
        assertEquals(UploadQueue.State.Submitted("job-1"), item.state)
        assertNotNull(item.notBefore)
        assertTrue(q.file("rec-other").exists())
    }

    @Test
    fun aJobGoneAtReadBackParksTheRecordingAndKeepsTheFile() = runTest {
        keepCopy = false
        for (status in listOf(404, 410)) {
            val id = "rec-$status"
            val q = queue().apply { add(id) }
            UploadPass(q, server(status to """{"error":"not_found"}""")).run(setOf(server), server, clients, only = id)
            val item = q.item(id)!!
            assertEquals(UploadQueue.State.Parked(status, "not_found"), item.state, "HTTP $status")
            assertTrue(q.file(id).exists(), "HTTP $status keeps the file")
            assertEquals(null, UploadSchedule.next(item, clock), "a parked recording gets no more work")
        }
    }

    @Test
    fun anUploadADeadProcessHadClaimedIsRequeuedAtStartAndSentOnce() = runTest {
        val dead = queue().apply { add("rec-dead") }
        assertEquals(listOf("rec-dead"), dead.claimDue().map { it.recordingId })
        assertEquals(UploadQueue.State.Uploading, queue().item("rec-dead")!!.state)

        // What Uploads does when a process opens the queue: nothing of its own is uploading yet.
        val next = queue().apply { requeueInterrupted() }
        assertEquals(UploadQueue.State.Pending, next.item("rec-dead")!!.state)
        UploadPass(next, server(kept())).run(setOf(server), server, clients)
        assertEquals(listOf("rec-dead"), sent.filter { it.method == "POST" }.map { it.header("Idempotency-Key") })
        assertEquals(UploadQueue.State.Done("job-1", keptOnServer = true), next.item("rec-dead")!!.state)
    }

    @Test
    fun aRunForOneRecordingLeavesTheOthersToTheirOwnWork() = runTest {
        val q = queue().apply { add("rec-a"); add("rec-b") }
        UploadPass(q, server(kept())).run(setOf(server), server, clients, only = "rec-a")
        assertEquals(listOf("rec-a"), sent.filter { it.method == "POST" }.map { it.header("Idempotency-Key") })
        assertEquals(UploadQueue.State.Pending, q.item("rec-b")!!.state)
    }
}
