// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A clock the test moves by hand. */
class TestClock {
    @Volatile var now: Instant = Instant.ofEpochSecond(1_800_000_000)
        private set

    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

/** The port of Tests/AkouKitTests/UploadQueueTests.swift, against the FakeAkouHttp port. */
class UploadQueueTests {
    private val fake = FakeAkouHttp()
    private val dir: File = Files.createTempDirectory("queue-").toFile()
    private val audioDir = File(dir, "audio").also { it.mkdirs() }
    private val clock = TestClock()
    @Volatile private var keepLocalCopy = false

    @AfterTest
    fun tearDown() {
        fake.close()
        dir.deleteRecursively()
    }

    private fun makeQueue() = UploadQueue(
        store = UploadQueue.FileStore(File(dir, "state/uploads.json")),
        audioDirectory = audioDir,
        now = { clock.now },
        keepLocalCopy = { keepLocalCopy },
    )

    /** A recording on disk, queued. */
    private fun record(queue: UploadQueue, id: String): File {
        val file = File(audioDir, "$id.opus")
        file.writeText("OggS $id")
        queue.enqueue(JobsClient.Submission(recordingId = id, title = "t", workspace = "Work"), fileName = "$id.opus")
        return file
    }

    private fun client(key: String = FakeAkouHttp.KEY) = JobsClient(fake.baseUrl, key)

    private fun drain(queue: UploadQueue) = runBlocking { queue.drain(client(), UploadQueue.OkHttpTransport()) }

    private fun submits() = fake.seen.filter { it.method == "POST" }

    private fun job(id: String) = UploadQueue.Answer.Http(202, Headers.headersOf(), """{"id":"$id","status":"queued","keep_audio":true}""".encodeToByteArray())

    @Test
    fun testAnUploadWhoseAnswerWasLostIsRetriedWithTheSameKeyAndMakesOneJob() {
        val queue = makeQueue()
        val file = record(queue, "rec-1")
        fake.script(FakeAkouHttp.Script.AcceptThenDrop)

        drain(queue)
        var item = assertNotNull(queue.item("rec-1"))
        assertEquals(UploadQueue.State.Pending, item.state, "the client never heard the answer")
        assertEquals(1, item.attempts)
        assertEquals(1, fake.jobs.size, "but the server made the job")

        // Not before the backoff.
        drain(queue)
        assertEquals(1, submits().size)

        clock.advance(UploadQueue.FIRST_BACKOFF_S)
        drain(queue)
        item = assertNotNull(queue.item("rec-1"))
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = true), item.state)
        assertEquals(listOf("rec-1", "rec-1"), submits().map { it.headers["idempotency-key"] })
        assertEquals(1, fake.jobs.size, "the retry got the first job back")
        assertFalse(file.exists(), "kept on the server, so the phone's copy goes")
        // The read-back that confirmed it.
        assertEquals("GET", fake.seen.last().method)
        assertEquals("/v1/jobs/j1", fake.seen.last().path)
    }

    @Test
    fun testTheLocalFileStaysWhenTheServerDoesNotKeepTheAudio() {
        fake.ignoreKeepAudio()
        val queue = makeQueue()
        val file = record(queue, "rec-2")
        drain(queue)
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = false), queue.item("rec-2")?.state)
        assertTrue(file.exists())
    }

    @Test
    fun testTheLocalFileStaysWhenTheSettingKeepsACopy() {
        keepLocalCopy = true
        val queue = makeQueue()
        val file = record(queue, "rec-3")
        drain(queue)
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = true), queue.item("rec-3")?.state)
        assertTrue(file.exists())
    }

    /** Not in the Swift test: the setting is read at each read-back, not when the queue is made. */
    @Test
    fun testTheKeepCopySettingIsReadWhenTheRecordingIsConfirmed() {
        keepLocalCopy = true
        val queue = makeQueue()
        val file = record(queue, "rec-3b")
        keepLocalCopy = false
        drain(queue)
        assertFalse(file.exists())
    }

    /** Not in the Swift test: a read-back that names another job is no proof, so the file stays. */
    @Test
    fun testAReadBackOfAnotherJobKeepsTheFileAndRetries() {
        val queue = makeQueue()
        val file = record(queue, "rec-3c")
        queue.claimDue()
        queue.uploadEnded("rec-3c", job("j1"))
        val other = """{"id":"j2","status":"done","keep_audio":true}""".encodeToByteArray()
        queue.confirmEnded("rec-3c", UploadQueue.Answer.Http(200, Headers.headersOf(), other))
        val item = assertNotNull(queue.item("rec-3c"))
        assertEquals(UploadQueue.State.Submitted("j1"), item.state)
        assertEquals(1, item.attempts)
        assertTrue(file.exists())
    }

    @Test
    fun testAFullQueueWaitsForRetryAfter() {
        val queue = makeQueue()
        record(queue, "rec-4")
        fake.script(FakeAkouHttp.Script.QueueFull(retryAfter = 120))
        drain(queue)
        val item = queue.item("rec-4")
        assertEquals(UploadQueue.State.Pending, item?.state)
        assertEquals(clock.now.plusSeconds(120), item?.notBefore, "Retry-After, not the 5 s backoff")

        clock.advance(119)
        drain(queue)
        assertEquals(1, submits().size, "still waiting")

        clock.advance(1)
        drain(queue)
        assertEquals(2, submits().size)
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = true), queue.item("rec-4")?.state)
    }

    /** Not in the Swift test: with no `Retry-After` header, the body's `retry_after_s` sets the wait. */
    @Test
    fun testAFullQueueWithoutTheHeaderWaitsForTheBodysRetryAfter() {
        val queue = makeQueue()
        record(queue, "rec-4b")
        fake.script(FakeAkouHttp.Script.QueueFull(retryAfter = 45, header = false))
        drain(queue)
        assertEquals(clock.now.plusSeconds(45), queue.item("rec-4b")?.notBefore)
    }

    @Test
    fun testAnIdempotencyConflictParksTheItemAndKeepsTheFile() {
        val queue = makeQueue()
        val file = record(queue, "rec-5")
        // The server already holds rec-5 with another file: every retry would answer 422 again.
        val other = File(dir, "other.opus").also { it.writeText("a different file") }
        runBlocking { client().submit(JobsClient.Submission(recordingId = "rec-5"), other) }

        drain(queue)
        assertEquals(UploadQueue.State.Parked(422, "idempotency_conflict"), queue.item("rec-5")?.state)
        assertTrue(file.exists())
        assertNull(queue.nextWake(), "a parked item does not wake the queue")

        clock.advance(3600)
        drain(queue)
        assertEquals(2, submits().size, "no loop: the parked item is not sent again")

        // The way out: retryParked puts it back.
        queue.retryParked()
        assertEquals(UploadQueue.State.Pending, queue.item("rec-5")?.state)
    }

    @Test
    fun testAnotherClientErrorParksToo() {
        val queue = makeQueue()
        record(queue, "rec-6")
        runBlocking { queue.drain(client("ak_wrong"), UploadQueue.OkHttpTransport()) }
        assertEquals(UploadQueue.State.Parked(401, "unauthorized"), queue.item("rec-6")?.state)
    }

    @Test
    fun testAMissingFileParksInsteadOfLooping() {
        val queue = makeQueue()
        val file = record(queue, "rec-7")
        file.delete()
        drain(queue)
        assertEquals(UploadQueue.State.Parked(null, "local_file"), queue.item("rec-7")?.state)
        assertTrue(submits().isEmpty())
    }

    @Test
    fun testTheQueueReloadsFromDiskAndRequeuesAnInterruptedUpload() {
        val queue = makeQueue()
        record(queue, "rec-8")
        record(queue, "rec-9")
        val claimed = queue.claimDue()
        assertEquals(listOf("rec-8", "rec-9"), claimed.map { it.recordingId })
        queue.uploadEnded("rec-9", UploadQueue.Answer.Unreachable("offline"))
        // The app is killed mid-upload: a new queue reads the same file.

        val reloaded = makeQueue()
        val items = reloaded.items
        assertEquals(listOf("rec-8", "rec-9"), items.map { it.recordingId })
        assertEquals(listOf(UploadQueue.State.Uploading, UploadQueue.State.Pending), items.map { it.state })
        assertEquals(1, items[1].attempts)
        assertEquals("Work", items[0].submission.workspace)

        // rec-8's transfer did not survive: it goes back to pending, and the next pass sends it.
        reloaded.requeueInterrupted(except = emptySet())
        clock.advance(UploadQueue.FIRST_BACKOFF_S)
        drain(reloaded)
        assertEquals(
            listOf(UploadQueue.State.Done("j1", keptOnServer = true), UploadQueue.State.Done("j2", keptOnServer = true)),
            reloaded.items.map { it.state },
        )
    }

    @Test
    fun testALiveBackgroundTransferIsLeftAlone() {
        val queue = makeQueue()
        record(queue, "rec-10")
        queue.claimDue()
        queue.requeueInterrupted(except = setOf("rec-10"))
        assertEquals(UploadQueue.State.Uploading, queue.item("rec-10")?.state)
    }

    @Test
    fun testALateAnswerForARequeuedUploadStillCounts() {
        val queue = makeQueue()
        record(queue, "rec-13")
        queue.claimDue()
        queue.requeueInterrupted(except = emptySet())
        queue.uploadEnded("rec-13", job("j9"))
        assertEquals(UploadQueue.State.Submitted("j9"), queue.item("rec-13")?.state)
        // A late failure for an item already moved on changes nothing.
        queue.uploadEnded("rec-13", UploadQueue.Answer.Unreachable("offline"))
        val item = queue.item("rec-13")
        assertEquals(UploadQueue.State.Submitted("j9"), item?.state)
        assertEquals(0, item?.attempts)
    }

    @Test
    fun testEnqueueingTheSameRecordingTwiceKeepsOneItem() {
        val queue = makeQueue()
        record(queue, "rec-11")
        queue.enqueue(JobsClient.Submission(recordingId = "rec-11", title = "other"), fileName = "x.opus")
        val items = queue.items
        assertEquals(1, items.size)
        assertEquals("t", items[0].submission.title)
    }

    @Test
    fun testBackoffDoublesAndStopsAtTheCap() {
        val queue = makeQueue()
        record(queue, "rec-12")
        val waits = mutableListOf<Long>()
        repeat(10) {
            queue.claimDue()
            queue.uploadEnded("rec-12", UploadQueue.Answer.Unreachable("offline"))
            val notBefore = assertNotNull(queue.item("rec-12")?.notBefore)
            val wait = Duration.between(clock.now, notBefore).seconds
            waits += wait
            // Positive control for an earliest-retry time: one second early, nothing is due.
            clock.advance(wait - 1)
            assertTrue(queue.claimDue().isEmpty(), "due before its wait after ${waits.size} failures")
            clock.advance(1)
        }
        assertEquals(listOf<Long>(5, 10, 20, 40, 80, 160, 320, 640, 900, 900), waits)
    }

    /** Not in the Swift test: what the queue writes is what it reads back, every state included. */
    @Test
    fun testEveryStateSurvivesAReload() {
        val queue = makeQueue()
        listOf("p", "u", "s", "d", "x").forEach { record(queue, it) }
        queue.claimDue()
        queue.uploadEnded("p", UploadQueue.Answer.Unreachable("offline"))
        queue.uploadEnded("s", job("j1"))
        queue.uploadEnded("d", job("j2"))
        queue.confirmEnded("d", UploadQueue.Answer.Http(200, Headers.headersOf(), """{"id":"j2","status":"done","keep_audio":false}""".encodeToByteArray()))
        queue.uploadEnded("x", UploadQueue.Answer.Http(422, Headers.headersOf(), """{"error":"idempotency_conflict"}""".encodeToByteArray()))
        assertEquals(queue.items, makeQueue().items)
        assertEquals(
            listOf(
                UploadQueue.State.Pending, UploadQueue.State.Uploading, UploadQueue.State.Submitted("j1"),
                UploadQueue.State.Done("j2", keptOnServer = false), UploadQueue.State.Parked(422, "idempotency_conflict"),
            ),
            makeQueue().items.map { it.state },
        )
    }
    /** Not in the Swift test: a transport with one piece of work per recording claims and reads back that one alone. */
    @Test
    fun testOnlyClaimsAndConfirmsTheOneRecordingAsked() {
        val queue = makeQueue()
        record(queue, "a")
        record(queue, "b")
        assertEquals(listOf("a"), queue.claimDue(only = "a").map { it.recordingId })
        assertEquals(UploadQueue.State.Pending, queue.item("b")?.state)
        queue.uploadEnded("a", job("ja"))
        assertEquals(listOf("b"), queue.claimDue(only = "b").map { it.recordingId })
        queue.uploadEnded("b", job("jb"))
        assertEquals(listOf("jb"), queue.dueForConfirm(only = "b").map { it.jobId })
        assertEquals(2, queue.dueForConfirm().size)
    }
}
