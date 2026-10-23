// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests/AkouKitTests/EndpointTests.swift. */
class EndpointTests {
    @Test
    fun testHttpsBecomesWss() {
        assertEquals("wss://akou.example.com/v1/live", Endpoint.live("https://akou.example.com"))
        assertEquals("wss://example.com/akou/v1/live", Endpoint.live("https://example.com/akou/"))
        assertEquals("https://example.com:8443/v1/server", Endpoint.api("https://example.com:8443", "/v1/server").toString())
    }

    /** Not in the Swift test: a base URL's query and fragment never reach an API URL. */
    @Test
    fun testTheBasePathIsKeptAndItsQueryDropped() {
        assertEquals("https://example.com/akou/v1/jobs", Endpoint.api("https://example.com/akou?x=1#f", "/v1/jobs").toString())
        assertEquals("https://example.com/v1/jobs", Endpoint.api("HTTPS://Example.com/", "/v1/jobs").toString())
    }

    /** The encoded-path case iOS adds with its id escaping. */
    @Test
    fun testAnEncodedPathPassesAndAPlainOneIsEncodedNotTrappedOn() {
        val base = "https://example.com/akou/"
        assertEquals("https://example.com/akou/v1/jobs/a%2Fb", Endpoint.api(base, "/v1/jobs/a%2Fb").toString())
        assertEquals("https://example.com/akou/v1/jobs/a%20b%25", Endpoint.api(base, "/v1/jobs/a b%").toString())
        assertEquals("/akou/v1/jobs/x%3Fy%23z", Endpoint.api(base, "/v1/jobs/x?y#z").encodedPath)
    }

    @Test
    fun testPlainHttpOnlyToPrivateAddresses() {
        assertEquals("ws://192.168.1.20:8787/v1/live", Endpoint.live("http://192.168.1.20:8787"))
        for (ok in listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.0.1", "100.64.0.1", "100.127.255.254", "::1", "fd7a:115c:a1e0::1")) {
            assertTrue(Endpoint.cleartextAllowed(ok), ok)
        }
        for (no in listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:db8::1", "akou.example.com", "localhost")) {
            assertFalse(Endpoint.cleartextAllowed(no), no)
        }
        assertEquals(Endpoint.Failure.CleartextToPublicHost("akou.example.com"), assertFailsWith<Endpoint.Failure> { Endpoint.live("http://akou.example.com") })
        assertFailsWith<Endpoint.Failure.UnsupportedScheme> { Endpoint.live("ftp://192.168.1.1") }
    }

    /**
     * Not in the Swift test (inet_pton gives it for free there): the literal parsers read literals
     * only, so a name that resolves to a private address, or a shorthand form, never passes.
     */
    @Test
    fun testOnlyCanonicalLiteralsPass() {
        for (no in listOf("127.1", "0x7f.0.0.1", "010.0.0.1", "10.0.0.256", "10.0.0", "10.0.0.1.", "", "[::1", "::1::", "1:2:3:4:5:6:7:8:9", "fc00::1::2", "localhost.", "10.0.0.1.nip.io")) {
            assertFalse(Endpoint.cleartextAllowed(no), "'$no'")
        }
        for (ok in listOf("[::1]", "0:0:0:0:0:0:0:1", "FD00::", "fc00:0:0:0:0:0:0:1", "fd00::10.0.0.1")) {
            assertTrue(Endpoint.cleartextAllowed(ok), ok)
        }
        assertFalse(Endpoint.cleartextAllowed("fe80::1"), "link-local is not unique-local")
        assertFalse(Endpoint.cleartextAllowed("::"), "the unspecified address is not loopback")
        // The scheme check runs before anything else and keeps the scheme as typed.
        assertEquals(Endpoint.Failure.UnsupportedScheme("FTP"), assertFailsWith<Endpoint.Failure> { Endpoint.api("FTP://10.0.0.1", "/v1/server") })
        assertEquals(Endpoint.Failure.UnsupportedScheme(null), assertFailsWith<Endpoint.Failure> { Endpoint.api("akou.example.com", "/v1/server") })
        assertEquals(Endpoint.Failure.MissingHost, assertFailsWith<Endpoint.Failure> { Endpoint.api("https://", "/v1/server") })
        // A private IPv6 literal over http keeps its brackets in the URL.
        assertEquals("ws://[fd7a:115c:a1e0::1]:8787/v1/live", Endpoint.live("http://[fd7a:115c:a1e0::1]:8787"))
        assertEquals("http://100.64.0.1/v1/server", Endpoint.api("http://100.64.0.1", "/v1/server").toString())
    }

    @Test
    fun testASegmentIsOneEscapedPathSegment() {
        assertEquals("a%2Fb%3Fc%23d%20e%25f", Endpoint.segment("a/b?c#d e%f"))
        assertEquals("job_01JX-a.b~c", Endpoint.segment("job_01JX-a.b~c"))
        assertEquals("%C3%A9", Endpoint.segment("é"))
        assertEquals("_", Endpoint.segment(".."))
        assertEquals("_", Endpoint.segment("."))
        assertEquals("_", Endpoint.segment(""))
    }
}
