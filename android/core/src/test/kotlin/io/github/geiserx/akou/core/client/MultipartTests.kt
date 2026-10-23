// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.client

import okio.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests/AkouKitTests/MultipartTests.swift. */
class MultipartTests {
    @Test
    fun testWritesFieldsThenTheFileByteForByte() {
        val dir = Files.createTempDirectory("multipart-").toFile()
        try {
            // Every byte value, past 1 MB, so the copy runs over more than one buffer.
            var audio = ByteArray(256) { it.toByte() }
            while (audio.size < 1_300_000) audio += audio
            val audioFile = File(dir, "a.opus").also { it.writeBytes(audio) }

            val m = Multipart(
                fields = listOf("title" to "Standup", "metadata" to """{"companion":1}"""),
                file = Multipart.FilePart("file", "r1.opus", "audio/ogg", audioFile),
                boundary = "B0UND",
            )
            assertEquals("B0UND", m.boundary)
            assertEquals("multipart/form-data; boundary=B0UND", m.contentType().toString())
            val body = Buffer().also { m.writeTo(it) }.readByteArray()
            assertEquals(body.size.toLong(), m.contentLength(), "the declared length is the bytes written")
            val parts = FakeAkouHttp.parts(body, m.boundary)
            assertEquals(listOf("title", "metadata", "file"), parts.map { it.name })
            assertEquals("Standup", parts[0].value.decodeToString())
            assertEquals("""{"companion":1}""", parts[1].value.decodeToString())
            assertEquals("r1.opus", parts[2].fileName)
            assertContentEquals(audio, parts[2].value)
            assertTrue(body.decodeToString(0, 80).startsWith("--B0UND\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nStandup\r\n"))
            assertEquals("\r\n--B0UND--\r\n", body.copyOfRange(body.size - 13, body.size).decodeToString())

            // Not in the Swift test: the file part's headers, and a second write sends the same bytes (a retry).
            assertTrue(body.decodeToString().contains("--B0UND\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r1.opus\"\r\nContent-Type: audio/ogg\r\n\r\n"))
            assertContentEquals(body, Buffer().also { m.writeTo(it) }.readByteArray())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun testANameCannotBreakOutOfItsQuotes() {
        assertEquals("ab", Multipart.quoted("a\"\r\nb"))
    }
}
