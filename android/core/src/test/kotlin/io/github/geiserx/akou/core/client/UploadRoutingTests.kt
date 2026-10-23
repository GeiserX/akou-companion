// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The port of Tests/AkouKitTests/UploadRoutingTests.swift: several servers on one queue. Each
 * recording goes to the server it was recorded for, an old one to "Record to", and a removed
 * server's recordings wait, parked, with their file.
 *
 * The Swift fake tells servers apart by host on one fake. Endpoint refuses plain http to a name,
 * so here each server is its own fake on 127.0.0.1, told apart by port, and each numbers its own
 * jobs from j1.
 */
class UploadRoutingTests {
    private val dir: File = Files.createTempDirectory("routing-").toFile()
    private val audioDir = File(dir, "audio").also { it.mkdirs() }
    private val clock = TestClock()
    private val a = UUID.fromString("AAAAAAAA-0000-0000-0000-000000000001")
    private val b = UUID.fromString("BBBBBBBB-0000-0000-0000-000000000002")
    private val removed = UUID.fromString("CCCCCCCC-0000-0000-0000-000000000003")
    private val fakes = mapOf(a to FakeAkouHttp(), b to FakeAkouHttp(), removed to FakeAkouHttp())
    private val names = mapOf(a to "a", b to "b", removed to "c")

    /** Who was asked what, in order across the servers: "a POST /v1/jobs", "b GET /v1/jobs/j1". */
    private val asked = mutableListOf<String>()
    private val transport = UploadQueue.OkHttpTransport(
        AkouHttp.make(
            OkHttpClient.Builder().addInterceptor { chain ->
                val r = chain.request()
                val who = fakes.entries.first { it.value.server.port == r.url.port }.key
                synchronized(asked) { asked += "${names[who]} ${r.method} ${r.url.encodedPath}" }
                chain.proceed(r)
            },
        ),
    )

    @AfterTest
    fun tearDown() {
        fakes.values.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun stateFile() = File(dir, "state/uploads.json")

    private fun makeQueue() = UploadQueue(
        store = UploadQueue.FileStore(stateFile()),
        audioDirectory = audioDir,
        now = { clock.now },
        keepLocalCopy = { true },
    )

    private fun record(queue: UploadQueue, id: String, server: UUID?): File {
        val file = File(audioDir, "$id.opus")
        file.writeText("OggS $id")
        queue.enqueue(JobsClient.Submission(recordingId = id, title = id), fileName = "$id.opus", serverId = server)
        return file
    }

    private fun clients(ids: List<UUID>) = ids.associateWith { JobsClient(fakes.getValue(it).baseUrl, FakeAkouHttp.KEY) }

    private fun drain(queue: UploadQueue, servers: List<UUID>, recordTo: UUID?) =
        runBlocking { queue.drain(clients(servers), recordTo, transport) }

    private fun asked(): List<String> = synchronized(asked) { asked.toList() }

    @Test
    fun testEachRecordingGoesToTheServerItWasRecordedFor() {
        val queue = makeQueue()
        record(queue, "r1", a)
        record(queue, "r2", b)
        drain(queue, listOf(a, b), recordTo = a)
        assertEquals(listOf("a POST /v1/jobs", "b POST /v1/jobs", "a GET /v1/jobs/j1", "b GET /v1/jobs/j1"), asked())
        val items = queue.items
        assertEquals(List(2) { UploadQueue.State.Done("j1", keptOnServer = true) }, items.map { it.state })
        assertEquals(listOf(a, b), items.map { it.serverId })
    }

    /**
     * A queue file whose items name no server (the Kotlin shape with `serverId` absent; Android has
     * no file from before several servers). They go to "Record to", which is written into them, so
     * moving "Record to" later never moves them.
     */
    @Test
    fun testAnOldRecordingGoesToRecordToAndStaysWithIt() {
        File(audioDir, "old.opus").writeText("OggS old")
        stateFile().parentFile.mkdirs()
        stateFile().writeText("""[{"submission":{"recordingId":"old","keepAudio":true},"fileName":"old.opus","state":{"type":"pending"},"attempts":0}]""")
        val queue = makeQueue()
        assertEquals(listOf("old"), queue.items.map { it.recordingId })
        assertNull(queue.items.first().serverId)

        // Uploaded to Record to (b), but not read back yet: the confirm waits for a backoff.
        fakes.getValue(b).script(FakeAkouHttp.Script.AcceptThenDrop)
        drain(queue, listOf(a, b), recordTo = b)
        assertEquals(listOf("b POST /v1/jobs"), asked())
        assertEquals(b, queue.item("old")?.serverId, "Record to is written into the item")

        // Record to moves to a; the retry and the read-back still go to b, where the job is.
        clock.advance(60)
        drain(queue, listOf(a, b), recordTo = a)
        assertEquals(listOf("b POST /v1/jobs", "b POST /v1/jobs", "b GET /v1/jobs/j1"), asked())
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = true), queue.item("old")?.state)
    }

    /**
     * An item that names no server, already uploaded and waiting for its read-back. Android has no
     * queue file from before several servers, so this is the Kotlin shape with `serverId` absent.
     * The first pass writes "Record to" into it, so a change of "Record to" before the read-back
     * still reads it back where the job is.
     */
    @Test
    fun testAnOldItemAwaitingItsReadBackKeepsItsServerWhenRecordToMoves() {
        File(audioDir, "old.opus").writeText("OggS old")
        stateFile().parentFile.mkdirs()
        stateFile().writeText("""[{"submission":{"recordingId":"old","keepAudio":true},"fileName":"old.opus","state":{"type":"submitted","jobId":"j7"},"attempts":0}]""")
        val queue = makeQueue()

        // The first pass, with a not reachable yet: nothing sent, a written in.
        val confirms = queue.dueForConfirm(UploadQueue.Routing(setOf(a, b), recordTo = a, ready = emptySet()))
        assertTrue(confirms.isEmpty())
        assertEquals(a, queue.item("old")?.serverId)

        // Record to moves to b; the read-back still goes to a.
        drain(queue, listOf(a, b), recordTo = b)
        assertEquals(listOf("a GET /v1/jobs/j7"), asked())
    }

    @Test
    fun testARemovedServersRecordingWaitsWithItsFileUntilSentElsewhere() {
        val queue = makeQueue()
        val file = record(queue, "r1", removed)
        drain(queue, listOf(a, b), recordTo = a)
        assertTrue(asked().isEmpty(), "nothing is sent anywhere on a guess")
        val parked = queue.item("r1")!!
        assertEquals(UploadQueue.State.Parked(null, UploadQueue.SERVER_REMOVED_CODE), parked.state)
        assertTrue(parked.waitsForAServer)
        assertEquals("The server this recording was made for was removed. Send it to another server.", parked.lastError)
        assertTrue(file.exists(), "the recording is never dropped")

        // A changed key retries what a server refused, but this one has no server to retry on.
        queue.retryParked()
        assertEquals(UploadQueue.State.Parked(null, UploadQueue.SERVER_REMOVED_CODE), queue.item("r1")?.state)

        queue.reassign("r1", b)
        drain(queue, listOf(a, b), recordTo = a)
        assertEquals(listOf("b POST /v1/jobs", "b GET /v1/jobs/j1"), asked())
        assertEquals(UploadQueue.State.Done("j1", keptOnServer = true), queue.item("r1")?.state)
    }

    /** Uploaded, then its server removed before the read-back: parked, file kept, so it can go elsewhere. */
    @Test
    fun testAServerRemovedBeforeTheReadBackParksTheRecording() {
        val queue = makeQueue()
        val file = record(queue, "r1", a)
        queue.claimDue(UploadQueue.Routing(setOf(a), recordTo = a))
        val job = """{"id":"j9","status":"queued","keep_audio":true}""".encodeToByteArray()
        queue.uploadEnded("r1", UploadQueue.Answer.Http(202, Headers.headersOf(), job))
        drain(queue, listOf(b), recordTo = b)
        assertTrue(asked().isEmpty())
        assertTrue(queue.item("r1")?.waitsForAServer ?: false)
        assertTrue(file.exists())
    }

    /** A server that exists but has no key yet: its recordings wait for it, neither sent elsewhere nor parked. */
    @Test
    fun testAServerWithoutItsKeyYetKeepsItsRecordingsWaiting() {
        val queue = makeQueue()
        record(queue, "r1", b)
        val claimed = queue.claimDue(UploadQueue.Routing(setOf(a, b), recordTo = a, ready = setOf(a)))
        assertTrue(claimed.isEmpty())
        val it = queue.item("r1")
        assertEquals(UploadQueue.State.Pending, it?.state)
        assertEquals(b, it?.serverId)
        drain(queue, listOf(a, b), recordTo = a)
        assertEquals(listOf("b POST /v1/jobs", "b GET /v1/jobs/j1"), asked())
    }

    /** No server at all yet (a fresh install): recordings wait, pending, and nothing is parked. */
    @Test
    fun testWithNoServerYetRecordingsWait() {
        val queue = makeQueue()
        record(queue, "r1", null)
        drain(queue, emptyList(), recordTo = null)
        assertTrue(asked().isEmpty())
        assertEquals(UploadQueue.State.Pending, queue.item("r1")?.state)
    }
}
