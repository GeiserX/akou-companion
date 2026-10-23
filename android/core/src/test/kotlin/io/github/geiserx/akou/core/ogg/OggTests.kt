// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.geiserx.akou.core.ogg

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Port of the OggTests class in Tests/AkouKitTests/OggTests.swift. */
class OggTests {
    @Test
    fun testCRCCheckValue() {
        // CRC-32 with polynomial 0x04C11DB7, init 0, no reflection, no final XOR, over "123456789".
        assertEquals(0x89A1897Fu, OggCRC.checksum("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0u, OggCRC.checksum(ByteArray(0)))
        // Not in the Swift test: a byte at or above 0x80 is negative in Kotlin and must still index the table.
        assertEquals(0x690CE0EEu, OggCRC.checksum(byteArrayOf(0x80.toByte())))
    }

    /** Positive control for [testCRCCheckValue]: the check value catches a broken table or the zlib CRC. */
    @Test
    fun testCRCCheckValueFailsWithABrokenTable() {
        val check = "123456789".toByteArray(Charsets.US_ASCII)
        val good = OggCRC.table(OggCRC.POLY)
        assertEquals(0x89A1897Fu, OggCRC.checksum(check, 0u, good))

        val flippedPoly = OggCRC.table(OggCRC.POLY xor 0x0000_0100)
        assertNotEquals(0x89A1897Fu, OggCRC.checksum(check, 0u, flippedPoly))

        // One entry off by one bit: "123456789" starts with 0x31, which indexes entry 0x31.
        val oneEntry = good.copyOf().also { it[0x31] = it[0x31] xor 1 }
        assertNotEquals(0x89A1897Fu, OggCRC.checksum(check, 0u, oneEntry))

        // java.util.zip.CRC32 is reflected with a final XOR: its check value is 0xCBF43926.
        val zlib = java.util.zip.CRC32().apply { update(check) }.value
        assertEquals(0xCBF43926L, zlib)
        assertNotEquals(0x89A1897FL, zlib)
    }

    @Test
    fun testOpusHeadPageMatchesFfmpegByteForByte() {
        val file = Fixtures.ffmpegFile()
        val head = OpusHeaders.head(preSkip = 312, inputSampleRate = 16000)
        assertEquals(19, head.size)
        val ours = OggPage(OggPage.Flags.BEGIN_OF_STREAM, 0, 0u, 0u, listOf(head)).encoded()
        assertEquals(47, ours.size)
        assertContentEquals(file.copyOfRange(0, 47), ours)
    }

    @Test
    fun testEveryFfmpegPageReadsAndReencodesByteExact() {
        val file = Fixtures.ffmpegFile()
        val pages = OggPage.readAll(file)
        assertTrue(pages.size > 5)
        assertEquals(OggPage.Flags.BEGIN_OF_STREAM, pages.first().flags)
        assertTrue(OggPage.Flags.END_OF_STREAM in pages.last().flags)
        assertEquals((0 until pages.size).map { it.toUInt() }, pages.map { it.sequence })
        // 200 ms pages of 20 ms packets: 10 packets, granule steps of 9600 at 48 kHz.
        assertEquals(10, pages[2].packets.size)
        assertEquals(9600L, pages[2].granulePosition)
        val rebuilt = pages.fold(ByteArray(0)) { acc, p -> acc + p.encoded() }
        assertContentEquals(file, rebuilt)
    }

    @Test
    fun testAFlippedByteFailsTheChecksum() {
        val file = Fixtures.ffmpegFile()
        val first = OggPage.read(file).length
        val second = OggPage.read(file, first).length
        val audio = first + second
        OggPage.read(file, audio)
        file[audio + 40] = (file[audio + 40].toInt() xor 0x01).toByte()
        assertFailsWith<OggPage.Error.BadChecksum> { OggPage.read(file, audio) }
    }

    /**
     * The Swift test guards Data slices whose indices do not start at 0. A ByteArray has no such
     * slices; the Kotlin hazard is offset arithmetic, so the page is read at an offset and from a copy.
     */
    @Test
    fun testReadingFromASliceThatDoesNotStartAtZero() {
        val file = Fixtures.ffmpegFile()
        val first = OggPage.read(file).length
        assertTrue(first > 0)
        val atOffset = OggPage.read(file, first).page
        val fromCopy = OggPage.read(file.copyOfRange(first, file.size)).page
        assertEquals(atOffset, fromCopy)
        assertEquals(1u, atOffset.sequence)
        assertTrue(atOffset.packets[0].copyOfRange(0, 8).contentEquals("OpusTags".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun testNotAnOggPage() {
        assertFailsWith<OggPage.Error.NotAnOggPage> { OggPage.read(ByteArray(64) { 0x41 }) }
    }

    @Test
    fun testLacingOfLongPackets() {
        val packets = listOf(ByteArray(255) { 1 }, ByteArray(600) { 2 }, ByteArray(0))
        val page = OggPage(granulePosition = 1, serial = 7u, sequence = 3u, packets = packets)
        val encoded = page.encoded()
        // 255 -> [255, 0]; 600 -> [255, 255, 90]; empty -> [0]
        assertEquals(listOf(6, 255, 0, 255, 255, 90, 0), encoded.copyOfRange(26, 33).map { it.toInt() and 0xFF })
        assertEquals(page, OggPage.read(encoded).page)
    }

    // The invariants below are in the plan's OggPage row and have no Swift test of their own.

    @Test
    fun testMoreThan255SegmentsIsRefused() {
        val page = OggPage(granulePosition = 0, serial = 0u, sequence = 0u, packets = List(256) { ByteArray(1) })
        val e = assertFailsWith<OggPage.Error.TooManySegments> { page.encoded() }
        assertEquals(256, e.count)
        // 255 segments still fit.
        OggPage(granulePosition = 0, serial = 0u, sequence = 0u, packets = List(255) { ByteArray(1) }).encoded()
    }

    @Test
    fun testReaderRefusesContinuedPages() {
        val page = OggPage(granulePosition = 0, serial = 0u, sequence = 0u, packets = listOf(ByteArray(10)))
        val continued = OggPage(OggPage.Flags.CONTINUED, 0, 0u, 0u, listOf(ByteArray(10))).encoded()
        assertFailsWith<OggPage.Error.ContinuedPacket> { OggPage.read(continued) }
        // A segment table ending in 255 leaves a packet open on the next page. Build one by hand and
        // fix its CRC, so the refusal is the lacing and not the checksum.
        val open = page.encoded().let { b ->
            b.copyOf(OggPage.HEADER_LENGTH + 1 + 255).also { it[OggPage.HEADER_LENGTH] = 255.toByte() }
        }
        for (i in 22 until 26) open[i] = 0
        val crc = OggCRC.checksum(open).toInt()
        for (i in 0 until 4) open[22 + i] = (crc ushr (8 * i)).toByte()
        assertFailsWith<OggPage.Error.ContinuedPacket> { OggPage.read(open) }
        // Positive control: the same page with the flag clear and whole lacing reads.
        OggPage.read(page.encoded())
    }

    @Test
    fun testTruncatedPagesAreRefused() {
        val file = Fixtures.ffmpegFile()
        assertFailsWith<OggPage.Error.Truncated> { OggPage.read(file.copyOf(26)) }
        assertFailsWith<OggPage.Error.Truncated> { OggPage.read(file.copyOf(46)) }
        assertFailsWith<OggPage.Error.Truncated> { OggPage.read(file, -1) }
        assertFailsWith<OggPage.Error.Truncated> { OggPage.read(file, file.size) }
    }

    @Test
    fun testTheWriterNeverSetsContinued() {
        val w = OggOpusWriter(FixtureOpusEncoder(), serial = 1u)
        val pages = w.headerPages() + List(50) { w.append(FloatArray(OpusEncoder.FRAME_SAMPLES)) }.filterNotNull() + w.finish()
        for (bytes in pages) {
            assertEquals(0, bytes[5].toInt() and OggPage.Flags.CONTINUED.bits)
            assertTrue(bytes[OggPage.HEADER_LENGTH + (bytes[26].toInt() and 0xFF) - 1].toInt() and 0xFF != 255)
        }
    }

    @Test
    fun testOpusTagsCarriesTheVendorAndNoComments() {
        val tags = OpusHeaders.tags("akou-companion")
        val expected = "OpusTags".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(14, 0, 0, 0) + "akou-companion".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 0)
        assertContentEquals(expected, tags)
        // The head is mono: the server refuses any other channel count.
        assertEquals(1, OpusHeaders.head(preSkip = 312, inputSampleRate = 16000)[9].toInt())
    }
}
