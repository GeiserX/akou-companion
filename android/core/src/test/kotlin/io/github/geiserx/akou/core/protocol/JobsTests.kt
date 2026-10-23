// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The model-decoding cases of Tests/AkouKitTests/JobsClientTests.swift (the HTTP cases belong to the client). */
class JobsTests {
    private inline fun <reified T> decode(json: String): T = AkouJson.decodeFromString(json)

    @Test
    fun testDecodesAJobAResultAndAPage() {
        val job = decode<Job>("""{"id":"j1","title":null,"status":"done","keep_audio":true,"metadata":{"some":"other client"},"progress":{"stage":"decode"},"links":{}}""")
        assertTrue(job.keepAudio)
        assertTrue(job.ended)
        assertNull(job.metadata, "another client's metadata is not ours")
        val noKeep = decode<Job>("""{"id":"j2","status":"queued"}""")
        assertFalse(noKeep.keepAudio, "a job that does not say keep_audio is not kept")

        val result = decode<JobResult>("""{"text":"hola","segments":[{"s":0,"e":1.2,"text":"hola","speaker":null}],"words":[{"w":"hola","s":0.1,"e":0.5,"c":0.93},{"w":"qué","s":null,"e":null,"c":null}],"confidence":0.9,"language":"es","duration_s":1.2,"model":"x","skipped":[]}""")
        assertEquals(2, result.words.size)
        assertEquals(0.93, result.words[0].c)
        assertNull(result.words[1].s)

        val page = decode<JobPage>("""{"jobs":[{"id":"j3","status":"running","keep_audio":false}],"cursor":17}""")
        assertEquals(listOf("j3"), page.jobs.map { it.id })
        assertEquals(17, page.cursor)
    }

    /** Not in the Swift test: the edges of the keep_audio and metadata rules. */
    @Test
    fun testKeepAudioAndMetadataLeniency() {
        assertFalse(decode<Job>("""{"id":"j","status":"done","keep_audio":null}""").keepAudio, "null keep_audio is not kept")
        assertNull(decode<Job>("""{"id":"j","status":"done","metadata":"a string"}""").metadata)
        assertNull(decode<Job>("""{"id":"j","status":"done","metadata":[1,2]}""").metadata)
        assertNull(decode<Job>("""{"id":"j","status":"done","metadata":{"companion":1}}""").metadata, "no recording_id: not ours")
        assertNull(decode<Job>("""{"id":"j","status":"failed","error":{"code":"x"}}""").error, "an error that is not text reads as none")
        val ours = decode<Job>("""{"id":"j","status":"done","metadata":{"companion":1,"recording_id":"rec-1","workspace":"Work"},"error":"boom"}""")
        assertEquals(CompanionMetadata("rec-1", "Work"), ours.metadata)
        assertEquals("boom", ours.error)
        // A new status never fails decoding, and is not an end.
        assertFalse(decode<Job>("""{"id":"j","status":"paused_by_admin"}""").ended)
        // id and status are required.
        assertFailsWith<IllegalArgumentException> { decode<Job>("""{"status":"done"}""") }
        assertFailsWith<IllegalArgumentException> { decode<Job>("""{"id":"j"}""") }
    }

    /** What the phone sends as `metadata`: `companion` is always 1 and a missing workspace is left out, as on iOS. */
    @Test
    fun testCompanionMetadataOnTheWire() {
        val with = Json.parseToJsonElement(AkouJson.encodeToString(CompanionMetadata.serializer(), CompanionMetadata("rec-1", "Work"))).jsonObject
        assertEquals(setOf("companion", "recording_id", "workspace"), with.keys)
        assertEquals("1", with["companion"].toString())
        val without = Json.parseToJsonElement(AkouJson.encodeToString(CompanionMetadata.serializer(), CompanionMetadata("rec-1", null))).jsonObject
        assertEquals(setOf("companion", "recording_id"), without.keys)
    }

    @Test
    fun testAkouErrorBody() {
        val body = decode<AkouErrorBody>("""{"error":"queue_full","message":"try later","retry_after_s":42,"depth":9}""")
        assertEquals("queue_full", body.error)
        assertEquals(42, body.retryAfterS)
        val conflict = decode<AkouErrorBody>("""{"error":"idempotency_conflict","id":"j1","fields":["title"]}""")
        assertEquals("j1", conflict.id)
        assertEquals(listOf("title"), conflict.fields)
    }

    @Test
    fun testAnEmptyResultDecodes() {
        val r = decode<JobResult>("""{}""")
        assertEquals("", r.text)
        assertEquals(emptyList(), r.segments)
        assertEquals(emptyList(), r.words)
    }
}
