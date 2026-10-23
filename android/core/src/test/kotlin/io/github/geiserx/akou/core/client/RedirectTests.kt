// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Answers every request on akou.example.com/v1/keys/me with a 302 to [target], and records every
 * request with the read timeout it ran under, so a test sees whether the key went on to the
 * redirect's host. It sits after the akou interceptors, where Swift's URLProtocol stub sits.
 */
class RedirectStub(private val target: String) : Interceptor {
    data class Seen(val url: String, val authorization: String?, val readTimeoutMillis: Int)

    val seen = mutableListOf<Seen>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        synchronized(seen) { seen += Seen(req.url.toString(), req.header("Authorization"), chain.readTimeoutMillis()) }
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1)
        if (req.url.host == "akou.example.com" && req.url.encodedPath == "/v1/keys/me") {
            return b.code(302).message("Found").header("Location", target).body("".toResponseBody()).build()
        }
        return b.code(200).message("OK")
            .body("""{"id":"k","name":"n","scopes":["jobs"]}""".toResponseBody("application/json".toMediaType())).build()
    }
}

/** Tests/AkouKitTests/RedirectTests.swift. */
class RedirectTests {
    private fun client(stub: RedirectStub): OkHttpClient = AkouHttp.make().newBuilder().addInterceptor(stub).build()

    @Test
    fun testARedirectToAnotherHostIsNotFollowedSoTheKeyStaysHome() = runBlocking<Unit> {
        val stub = RedirectStub("https://elsewhere.example.net/v1/keys/me")
        val probe = ServerProbe("https://akou.example.com", "ak_secret", client(stub))
        val f = assertFailsWith<ServerProbe.Failure> { probe.keyInfo() }
        assertEquals(ServerProbe.Failure.Status(302, null), f)
        assertEquals(listOf("akou.example.com"), stub.seen.map { it.url.toHttpUrl().host }, "nothing reached the other host")
    }

    @Test
    fun testTheSameOriginIsFollowed() = runBlocking<Unit> {
        val stub = RedirectStub("https://akou.example.com/v1/keys/me2")
        val probe = ServerProbe("https://akou.example.com", "ak_secret", client(stub))
        assertEquals("k", probe.keyInfo().id)
        assertEquals(listOf("/v1/keys/me", "/v1/keys/me2"), stub.seen.map { it.url.toHttpUrl().encodedPath })
        assertEquals(listOf("Bearer ak_secret", "Bearer ak_secret"), stub.seen.map { it.authorization })
    }

    /** A relative Location resolves against the request and counts as the same origin. */
    @Test
    fun testARelativeRedirectIsTheSameOrigin() = runBlocking<Unit> {
        val stub = RedirectStub("/v1/keys/me2")
        assertEquals("k", ServerProbe("https://akou.example.com", "ak_secret", client(stub)).keyInfo().id)
        assertEquals(2, stub.seen.size)
    }

    @Test
    fun testTheOriginRule() {
        val base = "https://akou.example.com/v1/jobs".toHttpUrl()
        assertTrue(AkouHttp.allowsRedirect(base, "https://AKOU.example.com:443/x".toHttpUrl()))
        assertFalse(AkouHttp.allowsRedirect(base, "http://akou.example.com/x".toHttpUrl()), "scheme")
        assertFalse(AkouHttp.allowsRedirect(base, "https://akou.example.com:8443/x".toHttpUrl()), "port")
        assertFalse(AkouHttp.allowsRedirect(base, "https://evil.example.com/x".toHttpUrl()), "host")
        assertFalse(AkouHttp.allowsRedirect(base, null))
    }

    /** A sleeping Mac shows its line after 15 s: the probe's requests run under a 15 s read timeout. */
    @Test
    fun testProbesAndHistoryReadsGiveUpAfter15Seconds() = runBlocking<Unit> {
        val stub = RedirectStub("")
        runCatching { ServerProbe("https://other.example.com", "k", client(stub)).keyInfo() }
        runCatching { ServerProbe("https://other.example.com", "k", client(stub)).server() }
        assertEquals(listOf(15_000, 15_000), stub.seen.map { it.readTimeoutMillis })
    }

    /**
     * Not in the Swift test, which stubs the transport: on a real socket OkHttp's own redirect
     * following is off, so a 3xx to another origin (another port here) reaches nobody. The positive
     * control is a plain OkHttp client, which does follow it.
     */
    @Test
    fun testOkHttpsOwnRedirectFollowingIsOff() = runBlocking<Unit> {
        MockWebServer().use { a ->
            MockWebServer().use { b ->
                a.start(InetAddress.getByName("127.0.0.1"), 0)
                b.start(InetAddress.getByName("127.0.0.1"), 0)
                val elsewhere = "http://127.0.0.1:${b.port}/v1/keys/me"
                repeat(2) { a.enqueue(MockResponse.Builder().code(302).setHeader("Location", elsewhere).build()) }
                b.enqueue(MockResponse.Builder().code(200).body("""{"id":"k","scopes":[]}""").build())

                val base = "http://127.0.0.1:${a.port}"
                val f = assertFailsWith<ServerProbe.Failure> { ServerProbe(base, "ak_secret").keyInfo() }
                assertEquals(ServerProbe.Failure.Status(302, null), f)
                assertEquals(0, b.requestCount, "the other origin heard nothing")

                val plain = OkHttpClient().newCall(Request.Builder().url("$base/v1/keys/me").build()).execute()
                plain.use { assertEquals(200, it.code, "the control: a default client follows the redirect") }
                assertEquals(1, b.requestCount)
                assertNull(b.takeRequest(1, TimeUnit.SECONDS)?.headers?.get("Authorization"))
            }
        }
    }
}
