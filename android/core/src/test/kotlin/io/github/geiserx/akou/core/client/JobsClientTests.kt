// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.AkouJson
import io.github.geiserx.akou.core.protocol.CompanionMetadata
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import java.io.File
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client cases of Tests/AkouKitTests/JobsClientTests.swift. Its model-decoding case,
 * testDecodesAJobAResultAndAPage, is ported in protocol/JobsTests.kt.
 */
class JobsClientTests {
    private val fake = FakeAkouHttp()
    private val dir: File = Files.createTempDirectory("jobs-").toFile()

    @AfterTest
    fun tearDown() {
        fake.close()
        dir.deleteRecursively()
    }

    private fun client() = JobsClient(fake.baseUrl, FakeAkouHttp.KEY)

    private fun audio(bytes: String = "OggS"): File = File(dir, "r1.opus").also { it.writeText(bytes) }

    @Test
    fun testSubmitSendsTheRecordingIdAsIdempotencyKeyKeepAudioAndTheMetadata() = runBlocking<Unit> {
        val a = audio("OggS fake audio")
        val s = JobsClient.Submission(recordingId = "rec-1", title = "Standup", language = "es", workspace = "Work")
        val submitted = client().submit(s, a)

        assertFalse(submitted.existing)
        assertTrue(submitted.job.keepAudio)
        assertEquals(CompanionMetadata(recordingId = "rec-1", workspace = "Work"), submitted.job.metadata)
        val req = fake.seen.first()
        assertEquals("POST", req.method)
        assertEquals("/v1/jobs", req.path)
        assertEquals("rec-1", req.headers["idempotency-key"])
        assertEquals("Bearer ak_test", req.headers["authorization"])
        assertEquals("true", req.fields["keep_audio"])
        assertEquals("Standup", req.fields["title"])
        assertEquals("es", req.fields["language"])
        assertNull(req.fields["model"])
        val meta = Json.parseToJsonElement(req.fields.getValue("metadata")).jsonObject
        assertEquals(1, meta.getValue("companion").jsonPrimitive.int)
        assertEquals("rec-1", meta.getValue("recording_id").jsonPrimitive.content)
        assertEquals("Work", meta.getValue("workspace").jsonPrimitive.content)
        assertEquals("rec-1.opus", req.fileName)
        assertContentEquals("OggS fake audio".encodeToByteArray(), req.fileBytes)
        // Not in the Swift test: the upload's framing headers.
        assertTrue(req.headers.getValue("content-type").startsWith("multipart/form-data; boundary=akou-"))
        assertNotNull(req.headers["content-length"])
        assertNull(req.headers["transfer-encoding"], "a known length, never chunked")
    }

    @Test
    fun testAudioSharedFromAnotherAppGoesUpWithItsOwnNameAndType() = runBlocking<Unit> {
        val memo = File(dir, "memo.m4a").also { it.writeText("ftypM4A fake") }
        val s = JobsClient.Submission(
            recordingId = "rec-3", title = "New Recording 3", audioFileName = "rec-3.m4a", audioContentType = "audio/mp4",
        )
        client().submit(s, memo)

        val req = fake.seen.first()
        assertEquals("rec-3", req.headers["idempotency-key"])
        assertEquals("rec-3.m4a", req.fileName)
        assertEquals("audio/mp4", req.fileContentType)
        assertEquals("New Recording 3", req.fields["title"])
        assertContentEquals("ftypM4A fake".encodeToByteArray(), req.fileBytes)
    }

    /** A queue item written before the shared-audio fields existed still goes up as the app's own Ogg Opus. */
    @Test
    fun testASubmissionQueuedBeforeSharedAudioDecodesWithTheOpusDefaults() = runBlocking<Unit> {
        val old = """{"recordingId":"rec-4","keepAudio":true,"title":"t"}"""
        val s = AkouJson.decodeFromString(JobsClient.Submission.serializer(), old)
        assertNull(s.audioFileName)
        assertNull(s.audioContentType)
        assertEquals("rec-4.opus", s.uploadFileName)
        assertEquals("audio/ogg", s.uploadContentType)
        // Not in the Swift test: the defaults reach the wire, and a nil field is not written back.
        client().submit(s, audio())
        assertEquals("rec-4.opus", fake.seen.last().fileName)
        assertEquals("audio/ogg", fake.seen.last().fileContentType)
        assertFalse("audioFileName" in AkouJson.encodeToString(JobsClient.Submission.serializer(), s))
    }

    /**
     * Not in the Swift test, where URLSession never resends: a POST whose connection drops after the
     * body went out reaches the server once per submit. OkHttp's retryOnConnectionFailure would
     * resend it on a reused connection; the queue's attempts and backoff count each submit as one.
     */
    @Test
    fun testADroppedUploadIsSentOncePerSubmit() = runBlocking<Unit> {
        val c = client()
        // Warm a pooled connection first, so the drop lands on a reused one.
        runCatching { c.job("nope") }
        fake.script(FakeAkouHttp.Script.Drop)
        assertFailsWith<java.io.IOException> { c.submit(JobsClient.Submission(recordingId = "rec-drop"), audio()) }
        assertEquals(1, fake.seen.count { it.method == "POST" }, "the dropped POST was sent once")
        assertFalse(AkouHttp.shared.retryOnConnectionFailure, "OkHttp's own resend is off")
    }

    @Test
    fun testARepeatedSubmitGetsTheFirstJob() = runBlocking<Unit> {
        val a = audio()
        val s = JobsClient.Submission(recordingId = "rec-2")
        val first = client().submit(s, a)
        val again = client().submit(s, a)
        assertEquals(first.job.id, again.job.id)
        assertTrue(again.existing)
        assertEquals(1, fake.jobs.size)
    }

    @Test
    fun testAQueueFullAnswerCarriesRetryAfter() = runBlocking<Unit> {
        fake.script(FakeAkouHttp.Script.QueueFull(retryAfter = 42))
        val f = assertFailsWith<JobsClient.Failure.Status> { client().submit(JobsClient.Submission(recordingId = "rec-3"), audio()) }
        assertEquals(429, f.status)
        assertEquals("queue_full", f.body?.error)
        assertEquals(42.0, f.retryAfter)
    }

    /** The plan's case: a 429 with only `retry_after_s` in the body still sets the wait. */
    @Test
    fun testAQueueFullAnswerWithOnlyTheBodyCarriesRetryAfter() = runBlocking<Unit> {
        fake.script(FakeAkouHttp.Script.QueueFull(retryAfter = 17, header = false))
        val f = assertFailsWith<JobsClient.Failure.Status> { client().submit(JobsClient.Submission(recordingId = "rec-4"), audio()) }
        assertNull(fake.seen.last().headers["retry-after"], "the control: the fake sent no header")
        assertEquals(17.0, f.retryAfter)
    }

    /** JobsClient.swift:193 and :206-211: the header wins, in seconds; anything else falls back to the body. */
    @Test
    fun testRetryAfterReadsSecondsFromTheHeaderElseTheBody() {
        val body = """{"error":"queue_full","retry_after_s":9}""".encodeToByteArray()
        fun wait(vararg h: String) =
            assertFailsWith<JobsClient.Failure.Status> { JobsClient.check(429, headersOf(*h), body) }.retryAfter
        assertEquals(42.0, wait("Retry-After", " 42 "))
        assertEquals(1.5, wait("retry-after", "1.5"))
        assertEquals(9.0, wait("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"), "an HTTP date is not seconds")
        assertEquals(9.0, wait("Retry-After", "-1"))
        assertEquals(9.0, wait())
        assertNull(assertFailsWith<JobsClient.Failure.Status> { JobsClient.check(503, headersOf(), "busy".encodeToByteArray()) }.retryAfter)
        JobsClient.check(204, headersOf(), ByteArray(0))
    }

    @Test
    fun testDeleteSendsTheJsonContentTypeAkouRequires() = runBlocking<Unit> {
        val submitted = client().submit(JobsClient.Submission(recordingId = "rec-del"), audio())
        client().delete(submitted.job.id)
        val req = fake.seen.last()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/jobs/${submitted.job.id}", req.path)
        assertEquals("application/json", req.headers["content-type"])
        assertTrue(fake.jobs.isEmpty())
    }

    /** Not in the Swift test: rename is a JSON PATCH the server's guard lets through. */
    @Test
    fun testRenameSendsJson() = runBlocking<Unit> {
        val submitted = client().submit(JobsClient.Submission(recordingId = "rec-ren"), audio())
        val e = assertFailsWith<JobsClient.Failure.Status> { client().rename(submitted.job.id, "New") }
        assertEquals(404, e.status, "the fake has no PATCH route; it got past the 415 guard")
        val req = fake.seen.last()
        assertEquals("PATCH", req.method)
        assertEquals("application/json", req.headers["content-type"])
    }

    @Test
    fun testRequestsForTheOtherRoutes() {
        val c = JobsClient("https://akou.example.com", "ak_test")
        assertEquals("https://akou.example.com/v1/jobs/j1?wait=60", c.jobRequest("j1", wait = 90).url.toString())
        assertEquals("https://akou.example.com/v1/jobs/j1", c.jobRequest("j1").url.toString())
        val audio = c.audioRequest("j1", 100L..199L)
        assertEquals("/v1/jobs/j1/audio", audio.url.encodedPath)
        assertEquals("bytes=100-199", audio.header("Range"))
        assertEquals("Bearer ak_test", audio.header("Authorization"))
        assertFalse(audio.url.toString().contains("ak_test"), "the key travels in the header, never in the URL")
        // A host name over plain http is refused before any request carries the key.
        assertFailsWith<Endpoint.Failure> { JobsClient("http://akou.example.com", "ak_test").jobRequest("j1") }
    }

    /** The plan's per-route timeouts: wait + 30 s for the long poll, never under the 60 s of the other job routes. */
    @Test
    fun testEachJobRouteCarriesItsReadTimeout() {
        val c = JobsClient("https://akou.example.com", "ak_test")
        fun t(r: okhttp3.Request) = r.tag(ReadTimeout::class)?.seconds
        assertEquals(60L, t(c.jobRequest("j1")))
        assertEquals(60L, t(c.jobRequest("j1", wait = 30)))
        assertEquals(75L, t(c.jobRequest("j1", wait = 45)))
        assertEquals(90L, t(c.jobRequest("j1", wait = 600)), "wait is clamped to 60 first")
        assertEquals(60L, t(c.audioRequest("j1")))
        assertEquals("/v1/jobs/j1/audio", c.audioRequest("j1").url.encodedPath)
        assertEquals("/v1/calls/c1/audio/1", c.audioRequestFor("/v1/calls/c1/audio/1").url.encodedPath)
        assertEquals(60L, t(c.submitRequest(JobsClient.Submission(recordingId = "r"), audio())))
    }

    /**
     * The plan's must: a `wait=30` poll whose answer arrives 12 s late survives, where OkHttp's
     * default 10 s read timeout would read the server as unreachable. The positive control sends the
     * same delayed answer to a request without the route's timeout, and it times out.
     */
    @Test
    fun testALongPollSurvivesAnAnswerSlowerThanOkHttpsDefaultTimeout() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val job = """{"id":"j1","status":"done","keep_audio":true}"""
            repeat(2) { server.enqueue(MockResponse.Builder().bodyDelay(12, TimeUnit.SECONDS).body(job).build()) }
            val c = JobsClient("http://127.0.0.1:${server.port}", "ak_test")

            assertEquals("done", c.job("j1", wait = 30).status)

            val untagged = c.jobRequest("j1", wait = 30).newBuilder().tag(ReadTimeout::class, null).build()
            assertNull(untagged.tag(ReadTimeout::class))
            val e = runCatching { AkouHttp.shared.fetch(untagged) }.exceptionOrNull()
            assertIs<SocketTimeoutException>(e, "the control: without the route's timeout the same poll times out")
        }
    }

    /** iOS's id escaping (Endpoint.segment): an id is one escaped path segment in every route. */
    @Test
    fun testAnIdIsOneEscapedPathSegmentInEveryRoute() = runBlocking<Unit> {
        val odd = "a/b?c#d e%f"
        val escaped = "a%2Fb%3Fc%23d%20e%25f"
        val get = JobsClient("https://akou.example.com", "ak_test").jobRequest(odd).url
        assertEquals("https://akou.example.com/v1/jobs/$escaped", get.toString())
        assertNull(get.query, "a ? in the id never starts a query")
        assertNull(get.fragment, "a # in the id never starts a fragment")
        assertEquals(listOf("v1", "jobs", odd), get.pathSegments, "the id stays one segment")
        assertEquals("/v1/jobs/$escaped/audio", JobsClient.audioPath(odd))

        val c = client()
        runCatching { c.result(odd) }
        runCatching { c.rename(odd, "x") }
        runCatching { c.delete(odd) }
        val seen = fake.seen.takeLast(3)
        assertEquals(listOf("GET", "PATCH", "DELETE"), seen.map { it.method })
        assertEquals(listOf("/v1/jobs/$escaped/result", "/v1/jobs/$escaped", "/v1/jobs/$escaped"), seen.map { it.rawPath })
        assertEquals("/v1/jobs/_/audio", JobsClient.audioPath(".."))
    }

    /** The key travels in `Authorization: Bearer`, exactly as iOS sends it, and in no URL of any route. */
    @Test
    fun testTheKeyIsInTheAuthorizationHeaderAndNeverInAURL() = runBlocking<Unit> {
        val c = client()
        val submitted = c.submit(JobsClient.Submission(recordingId = "rec-k"), audio())
        c.job(submitted.job.id)
        runCatching { c.result(submitted.job.id) }
        runCatching { c.list(status = "done", query = "q", cursor = 3, limit = 5) }
        runCatching { c.rename(submitted.job.id, "t") }
        c.delete(submitted.job.id)
        val seen = fake.seen
        assertEquals(6, seen.size)
        assertTrue(seen.all { it.headers["authorization"] == "Bearer ${FakeAkouHttp.KEY}" })
        assertTrue(seen.none { FakeAkouHttp.KEY in it.rawPath || FakeAkouHttp.KEY in (it.query ?: "") })
        assertEquals("status=done&q=q&cursor=3&limit=5", seen[3].query)
        assertNotNull(seen.first().headers["idempotency-key"])
    }
}
