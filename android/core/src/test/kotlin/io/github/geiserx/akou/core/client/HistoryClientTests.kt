// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import io.github.geiserx.akou.core.protocol.HistoryFixtures
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.net.InetAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stand-in for akou's history routes, the port of FakeHistoryHTTP in
 * Tests/AkouKitTests/HistoryClientTests.swift: every history route needs a `read` or `admin` key,
 * `/v1/keys/me` answers any known key, an unknown key is 401.
 */
class FakeHistoryHttp(var routes: Map<String, String>) : Dispatcher() {
    data class Seen(val path: String, val rawPath: String, val query: String?, val authorization: String?, val accept: String?)

    private val _seen = mutableListOf<Seen>()
    val seen: List<Seen> get() = synchronized(_seen) { _seen.toList() }

    val server = MockWebServer().also {
        it.dispatcher = this
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }
    val baseUrl get() = "http://127.0.0.1:${server.port}"

    fun reset(routes: Map<String, String>) {
        this.routes = routes
        synchronized(_seen) { _seen.clear() }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.url.pathSegments.joinToString("/", prefix = "/")
        val auth = request.headers["Authorization"]
        synchronized(_seen) {
            _seen += Seen(path, request.target.substringBefore('?'), request.url.query, auth, request.headers["Accept"])
        }
        val scopes = auth?.takeIf { it.startsWith("Bearer ") }?.let { KEYS[it.removePrefix("Bearer ")] }
            ?: return answer(401, """{"error":"unauthorized","message":"a valid bearer token is required"}""")
        if (path != "/v1/keys/me" && !("read" in scopes || "admin" in scopes)) {
            return answer(403, """{"error":"forbidden","message":"this key's scope does not reach GET $path; it needs read"}""")
        }
        if (path == "/v1/keys/me") {
            val list = scopes.joinToString(",") { "\"$it\"" }
            return answer(200, """{"id":"k_test","name":"phone","scopes":[$list],"created_at":1760000000000}""")
        }
        val route = routes[path] ?: return answer(404, """{"error":"not_found","message":"no such call"}""")
        return answer(200, route)
    }

    private fun answer(status: Int, body: String) =
        MockResponse.Builder().code(status).setHeader("Content-Type", "application/json").body(body).build()

    companion object {
        /** The keys this server knows, by their scopes. */
        val KEYS = mapOf(
            "ak_read" to listOf("read"),
            "ak_admin" to listOf("admin"),
            "ak_jobs" to listOf("jobs"),
            "ak_both" to listOf("jobs", "read"),
        )
    }
}

/**
 * The HTTP cases of Tests/AkouKitTests/HistoryClientTests.swift. Its model-decoding cases
 * (testAnOddLineNeverCostsTheTranscript, testALineSeeksToA0WhenAkouSendsIt) are in protocol/HistoryTests.kt.
 */
class HistoryClientTests {
    private val fake = FakeHistoryHttp(
        mapOf(
            "/v1/calls" to HistoryFixtures.CALLS,
            "/v1/calls/01JCALLB0000000000000000002" to HistoryFixtures.CALL,
            "/v1/calls/01JCALLB0000000000000000002/transcript" to HistoryFixtures.TRANSCRIPT,
            "/v1/calls/01JCALLB0000000000000000002/enhanced" to HistoryFixtures.ENHANCED,
            "/v1/calls/01JCALLA0000000000000000001/enhanced" to HistoryFixtures.NO_ENHANCED,
            "/v1/calls/01JCALLB0000000000000000002/notes" to HistoryFixtures.NOTES,
            "/v1/dictations" to HistoryFixtures.DICTATIONS,
            "/v1/jobs" to HistoryFixtures.JOBS,
        ),
    )

    @AfterTest
    fun tearDown() = fake.server.close()

    private fun client(key: String = "ak_read") = HistoryClient(fake.baseUrl, key)

    private fun probe(key: String) = ServerProbe(fake.baseUrl, key)

    @Test
    fun testTheKeysScopesDecideWhatThePhoneMayDo() = runBlocking<Unit> {
        val jobs = probe("ak_jobs").keyInfo()
        assertTrue(jobs.canUpload)
        assertFalse(jobs.seesHistory, "a jobs key sees only its own recordings")
        val read = probe("ak_read").keyInfo()
        assertFalse(read.canUpload, "read writes nothing, so it cannot be Record to")
        assertTrue(read.seesHistory)
        val admin = probe("ak_admin").keyInfo()
        assertTrue(admin.canUpload)
        assertTrue(admin.seesHistory)
        val both = probe("ak_both").keyInfo()
        assertEquals(listOf("jobs", "read"), both.scopes)
        assertTrue(both.canUpload && both.seesHistory)
    }

    @Test
    fun testTheCallsListReadsAkousRows() = runBlocking<Unit> {
        val calls = client().calls()
        assertEquals(listOf("01JCALLB0000000000000000002", "01JCALLA0000000000000000001"), calls.map { it.id })
        assertEquals("Weekly sync", calls[0].title)
        assertEquals("Work", calls[0].workspace)
        assertEquals(2, calls[0].parts)
        assertFalse(calls[0].isLive)
        assertTrue(calls[1].isLive, "a recording call with no end is live")
        val req = fake.seen.last()
        assertEquals("limit=200", req.query)
        assertEquals("Bearer ak_read", req.authorization)
        assertEquals("application/json", req.accept)
    }

    @Test
    fun testACallsDetailHasItsPartsAndTheirTimes() = runBlocking<Unit> {
        val call = client().call("01JCALLB0000000000000000002")
        assertEquals(listOf(1, 2), call.parts.map { it.part })
        assertEquals("Europe/Madrid", call.tz)
        assertEquals("done", call.final?.state)
    }

    @Test
    fun testTheTranscriptKeepsSpeakersTimesAndParts() = runBlocking<Unit> {
        val t = client().transcript("01JCALLB0000000000000000002")
        assertEquals(listOf("You", "Ana", "Ana"), t.lines.map { it.speaker })
        assertEquals(listOf(1, 1, 2), t.lines.map { it.part })
        assertEquals("Yes, the build went out last night.", t.lines[1].text, "the corrected text, not the annotated one")
        // The best layer, answered at once: no wait for a second pass on a phone.
        assertEquals("layer=best&format=json&review=skip", fake.seen.last().query)
    }

    @Test
    fun testTheSummaryIsThereOrNil() = runBlocking<Unit> {
        val s = client().summary("01JCALLB0000000000000000002")
        assertEquals(2, s?.rev)
        assertTrue(s?.markdown?.startsWith("## Summary") == true)
        assertNull(client().summary("01JCALLA0000000000000000001"))
    }

    @Test
    fun testTheNotepadLines() = runBlocking<Unit> {
        val notes = client().notes("01JCALLB0000000000000000002")
        assertEquals(listOf("Ask about the notes", "Fixed: bild -> build"), notes.map { it.text })
        assertEquals(listOf("human", "agent"), notes.map { it.author })
    }

    @Test
    fun testDictationsPageOnWithTheirCursor() = runBlocking<Unit> {
        val first = client().dictations()
        assertEquals(listOf("dct_02", "dct_01"), first.items.map { it.id })
        assertEquals("dct_01", first.nextCursor)
        assertEquals("limit=50", fake.seen.last().query)
        fake.reset(mapOf("/v1/dictations" to HistoryFixtures.DICTATIONS_LAST))
        val next = client().dictations(cursor = "dct_01")
        assertNull(next.nextCursor)
        assertEquals("limit=50&cursor=dct_01", fake.seen.last().query)
    }

    @Test
    fun testEveryJobWithAnotherKeysToo() = runBlocking<Unit> {
        val page = client().jobs()
        assertEquals(listOf("job_b", "job_a"), page.jobs.map { it.id })
        assertNull(page.jobs[0].metadata, "another client's job carries no companion metadata")
        assertEquals("Work", page.jobs[1].metadata?.workspace)
        assertNull(page.cursor)
        assertEquals("limit=50", fake.seen.last().query)
        client().jobs(cursor = 40, limit = 10)
        assertEquals("limit=10&cursor=40", fake.seen.last().query)
    }

    @Test
    fun testAJobsKeyCannotSeeHistory() = runBlocking<Unit> {
        val f = assertFailsWith<HistoryClient.Failure> { client("ak_jobs").calls() }
        assertEquals(HistoryClient.Failure.CannotSeeHistory(403), f)
        val g = assertFailsWith<HistoryClient.Failure> { client("ak_revoked").dictations() }
        assertEquals(HistoryClient.Failure.CannotSeeHistory(401), g)
        // Never retried in a loop: one request each.
        assertEquals(2, fake.seen.size)
    }

    @Test
    fun testAnotherRefusalKeepsAkousCode() = runBlocking<Unit> {
        val f = assertFailsWith<HistoryClient.Failure> { client().call("01JNOSUCHCALL") }
        assertEquals(HistoryClient.Failure.Status(404, "not_found", "no such call"), f)
    }

    /** Not in the Swift test: a 2xx that is not akou's JSON. */
    @Test
    fun testA2xxThatIsNotAkouIsNotAkou() = runBlocking<Unit> {
        fake.reset(mapOf("/v1/calls" to "<html>router login</html>"))
        assertEquals(HistoryClient.Failure.NotAkou, assertFailsWith<HistoryClient.Failure> { client().calls() })
    }

    @Test
    fun testAnIdNeverAddsAPathSegment() = runBlocking<Unit> {
        runCatching { client().call("../keys") }
        assertEquals("/v1/calls/..%2Fkeys", fake.seen.last().rawPath)
        assertEquals("/v1/calls/a%2Fb/audio/1", HistoryClient.callAudioPath("a/b", part = 1))
        assertEquals("/v1/dictations/_/audio", HistoryClient.dictationAudioPath(".."))
    }

    @Test
    fun testTheKeyIsNeverInAnAudioURL() {
        val r = HistoryClient("https://akou.example.com", "ak_read")
            .audioRequest(HistoryClient.callAudioPath("01JCALLB0000000000000000002", part = 2))
        assertEquals("https://akou.example.com/v1/calls/01JCALLB0000000000000000002/audio/2", r.url.toString())
        assertEquals("Bearer ak_read", r.header("Authorization"))
        assertEquals("audio/*", r.header("Accept"))
    }
}
