// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

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
import kotlin.test.assertTrue

/** Answers every request from a table keyed by path and records the requests. */
class StubServer : Dispatcher() {
    val answers = mutableMapOf<String, Pair<Int, String>>()
    val seen = mutableListOf<RecordedRequest>()
    val server = MockWebServer().also {
        it.dispatcher = this
        it.start(InetAddress.getByName("127.0.0.1"), 0)
    }
    val baseUrl get() = "http://127.0.0.1:${server.port}"

    override fun dispatch(request: RecordedRequest): MockResponse {
        synchronized(seen) { seen += request }
        val (status, body) = answers[request.url.encodedPath] ?: (404 to """{"error":"not_found"}""")
        return MockResponse.Builder().code(status).setHeader("Content-Type", "application/json").body(body).build()
    }
}

/** Tests/AkouKitTests/ServerProbeTests.swift. */
class ServerProbeTests {
    private val stub = StubServer()

    @AfterTest
    fun tearDown() = stub.server.close()

    @Test
    fun testProbeReadsTheServerAndTheKey() = runBlocking<Unit> {
        stub.answers["/v1/server"] = 200 to """{"name":"akou","version":"0.7.0","mode":"server","capabilities":{"jobs":true,"live":true}}"""
        stub.answers["/v1/keys/me"] = 200 to """{"id":"k1","name":"phone","scopes":["jobs"]}"""
        val probe = ServerProbe(stub.baseUrl, "ak_x")
        assertTrue(probe.server().supportsLive)
        assertEquals(listOf("jobs"), probe.keyInfo().scopes)
        assertEquals(listOf("Bearer ak_x", "Bearer ak_x"), stub.seen.map { it.headers["Authorization"] })
        assertEquals(listOf("application/json", "application/json"), stub.seen.map { it.headers["Accept"] })
        assertTrue(stub.seen.none { "ak_x" in it.url.toString() }, "the key travels in the header, never in the URL")
    }

    @Test
    fun testAWrongKeyIsA401WithAkousCode() = runBlocking<Unit> {
        stub.answers["/v1/keys/me"] = 401 to """{"error":"unauthorized","message":"a valid bearer token is required"}"""
        val f = assertFailsWith<ServerProbe.Failure> { ServerProbe(stub.baseUrl, "ak_bad").keyInfo() }
        assertEquals(ServerProbe.Failure.Status(401, "unauthorized"), f)
    }

    @Test
    fun testSomethingElseIsNotAkou() = runBlocking<Unit> {
        stub.answers["/v1/server"] = 200 to """{"name":"other","capabilities":{}}"""
        assertEquals(ServerProbe.Failure.NotAkou, assertFailsWith<ServerProbe.Failure> { ServerProbe(stub.baseUrl, "ak_x").server() })
        // Not in the Swift test: a 2xx that is not JSON at all, and an error page that is not akou's.
        stub.answers["/v1/server"] = 200 to "<html>router login</html>"
        assertEquals(ServerProbe.Failure.NotAkou, assertFailsWith<ServerProbe.Failure> { ServerProbe(stub.baseUrl, "ak_x").server() })
        stub.answers["/v1/keys/me"] = 502 to "<html>bad gateway</html>"
        assertEquals(ServerProbe.Failure.Status(502, null), assertFailsWith<ServerProbe.Failure> { ServerProbe(stub.baseUrl, "ak_x").keyInfo() })
    }
}
