// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests/AkouKitTests/TransportPolicyTests.swift. Where plain `http` may go is decided in one place,
 * `Endpoint`. A network security config cannot express CIDR ranges, so the app permits cleartext
 * and this check holds the other half: every request the client builds gets its URL from Endpoint.
 * (The Swift test's ATS half has no Android counterpart in `core`.)
 */
class TransportPolicyTests {
    private val clientDir = File("src/main/kotlin/io/github/geiserx/akou/core/client")

    @Test
    fun testEveryRequestTheClientBuildsGoesThroughEndpoint() {
        val files = clientDir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue(files.isNotEmpty(), "found the client sources at ${clientDir.absolutePath}")
        var checked = 0
        val bad = mutableListOf<String>()
        for (file in files) {
            val result = scan(file.readLines())
            checked += result.first
            result.second.forEach { bad += "${file.name}:$it" }
        }
        assertEquals(emptyList(), bad, "a request whose URL does not come from Endpoint")
        assertTrue(checked >= 3, "the check found the requests it guards ($checked)")
    }

    /** The positive control: a request built from a bare URL is reported. */
    @Test
    fun testTheScanCatchesARequestThatSkipsEndpoint() {
        val source = """
            val url = "http://akou.example.com/v1/jobs".toHttpUrl()
            fun a() = Request.Builder().url(url).build()
        """.trimIndent().lines()
        val (checked, bad) = scan(source)
        assertEquals(1, checked)
        assertEquals(listOf(2), bad)
        val good = listOf("val u = Endpoint.api(base, \"/v1/jobs\")", "", "", "", "", "Request.Builder().url(u)")
        assertEquals(1 to emptyList(), scan(good))
        // An Endpoint call after the builder does not vouch for its URL.
        val after = listOf("Request.Builder().url(\"http://akou.example.com\".toHttpUrl())", "val u = Endpoint.api(base, \"/v1/jobs\")")
        assertEquals(1 to listOf(1), scan(after))
        // Six lines up is out of the window.
        val far = listOf("val u = Endpoint.api(base, \"/v1/jobs\")", "", "", "", "", "", "Request.Builder().url(u)")
        assertEquals(1 to listOf(7), scan(far))
    }

    /** As in Swift: each `Request.Builder()` needs `Endpoint.` on its line or within the 5 lines before it. */
    private fun scan(lines: List<String>): Pair<Int, List<Int>> {
        var checked = 0
        val bad = mutableListOf<Int>()
        for ((i, line) in lines.withIndex()) {
            if (!line.contains("Request.Builder()")) continue
            checked++
            val window = lines.subList(maxOf(0, i - 5), i + 1).joinToString("\n")
            if (!window.contains("Endpoint.")) bad += i + 1
        }
        return checked to bad
    }
}
